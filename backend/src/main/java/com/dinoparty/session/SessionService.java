package com.dinoparty.session;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.RandomSource;
import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.common.Timestamps;
import com.dinoparty.engine.Audience;
import com.dinoparty.engine.DeadlineBook;
import com.dinoparty.engine.DeadlineFired;
import com.dinoparty.engine.DeadlineRequest;
import com.dinoparty.engine.FinishReason;
import com.dinoparty.engine.GameContext;
import com.dinoparty.engine.GameDefinition;
import com.dinoparty.engine.GameModule;
import com.dinoparty.engine.GameOutcome;
import com.dinoparty.engine.GameRegistry;
import com.dinoparty.engine.GameSetup;
import com.dinoparty.engine.OutboundEvent;
import com.dinoparty.engine.PhaseChange;
import com.dinoparty.engine.PlayerAction;
import com.dinoparty.engine.PlayerInfo;
import com.dinoparty.engine.PlayerStatus;
import com.dinoparty.engine.PlayerStatusChange;
import com.dinoparty.engine.Ranking;
import com.dinoparty.engine.RoundRecord;
import com.dinoparty.engine.ScoreDelta;
import com.dinoparty.engine.ScoreLedger;
import com.dinoparty.engine.SubmissionRecord;
import com.dinoparty.engine.Transition;
import com.dinoparty.engine.TurnOrder;
import com.dinoparty.room.RoomStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Runs the lifecycle of the one authoritative game of each room: countdown, phases, deadlines,
 * actions, finish, rematch and abort. Every public method executes inside the room mailbox (it
 * enters it itself; the lock is reentrant), so session state is only touched by one thread at a
 * time and needs no other synchronization. Game rules live in {@link GameModule}s; this class owns
 * identity, ordering, timing and delivery (docs/game-engine.md).
 */
@Service
public class SessionService {

  private static final Logger log = LoggerFactory.getLogger(SessionService.class);

  static final String START_COUNTDOWN = "start-countdown";
  static final String RESULTS_TIMEOUT = "results-timeout";
  static final String PHASE_END = "phase-end";
  private static final int MAX_DRAIN = 1000;

  private final GameRegistry registry;
  private final RoomLifecycle rooms;
  private final SessionEventSink sink;
  private final SessionRecorder recorder;
  private final EndedSessionLookup history;
  private final DeadlineScheduler scheduler;
  private final Clock clock;
  private final RandomSource random;
  private final SessionProperties properties;
  private final MeterRegistry meters;
  private final ConcurrentHashMap<String, ActiveSession> sessions = new ConcurrentHashMap<>();

  /**
   * Per room: its newest session that is no longer running, empty once a new one runs. Touched only
   * inside the room mailbox; an absent entry means "not known in memory" (e.g. after a restart).
   */
  private final ConcurrentHashMap<String, Optional<LastSession>> lastSessions =
      new ConcurrentHashMap<>();

  public SessionService(
      GameRegistry registry,
      RoomLifecycle rooms,
      SessionEventSink sink,
      SessionRecorder recorder,
      EndedSessionLookup history,
      DeadlineScheduler scheduler,
      Clock clock,
      RandomSource random,
      SessionProperties properties,
      MeterRegistry meters) {
    this.registry = registry;
    this.rooms = rooms;
    this.sink = sink;
    this.recorder = recorder;
    this.history = history;
    this.scheduler = scheduler;
    this.clock = clock;
    this.random = random;
    this.properties = properties;
    this.meters = meters;
    meters.gauge("dinoparty.engine.sessions.active", sessions, Map::size);
  }

  // ---------------------------------------------------------------- lifecycle entry points

  /** Host starts the game: validates, enters {@code STARTING} and arms the countdown. */
  public void start(String code, UUID caller) {
    run(code, () -> doStart(code, caller));
  }

  /** Host (or any voter) answers the rematch question while the room shows results. */
  public void rematchVote(String code, UUID guest, boolean vote) {
    run(
        code,
        () -> {
          ActiveSession s = live(code);
          if (s == null || s.status != SessionStatus.FINISHED) {
            requireMember(code, guest);
            throw new DomainException(ErrorCode.INVALID_PHASE, "There is nothing to vote on.");
          }
          RosterSlot slot = s.roster.get(guest);
          if (slot == null || slot.status == PlayerStatus.LEFT) {
            throw new DomainException(ErrorCode.NOT_IN_ROOM, "You are not part of this game.");
          }
          s.votes.put(guest, vote);
          sink.publish(code, s.roomId, "REMATCH_VOTES_UPDATED", Map.of("votes", tally(s)));
          evaluateRematch(s);
        });
  }

  /** Authorizes a game command from the in-memory roster; no database access while a game runs. */
  public void authorize(String code, UUID guest) {
    run(
        code,
        () -> {
          ActiveSession s = sessions.get(code);
          if (s != null && s.status != SessionStatus.STARTING) {
            RosterSlot slot = s.roster.get(guest);
            if (slot == null || slot.status == PlayerStatus.LEFT) {
              throw new DomainException(ErrorCode.NOT_IN_ROOM, "You are not part of this game.");
            }
            return;
          }
          requireMember(code, guest);
        });
  }

  /** The room id of an active session, so the transport can address errors without a lookup. */
  public Optional<UUID> activeRoomId(String code) {
    return inRoom(code, () -> Optional.ofNullable(sessions.get(code)).map(s -> s.roomId));
  }

  /**
   * Applies a validated game command: deadlines first, then the phase guard, then the module. The
   * caller already authorized the guest and de-duplicated the action id.
   */
  public void submit(
      String code, UUID guest, String type, String phaseId, Map<String, Object> payload) {
    run(
        code,
        () -> {
          ActiveSession s = live(code);
          if (s == null || s.status != SessionStatus.RUNNING) {
            requireMember(code, guest);
            throw new DomainException(ErrorCode.INVALID_PHASE, "No game is running.");
          }
          try (MDC.MDCCloseable ignored = scope(s)) {
            RosterSlot slot = s.roster.get(guest);
            if (slot == null || slot.status == PlayerStatus.LEFT) {
              throw new DomainException(ErrorCode.NOT_IN_ROOM, "You are not part of this game.");
            }
            var spec = s.module.definition().commandsByType().get(type);
            if (spec == null) {
              throw new DomainException(ErrorCode.INVALID_ACTION, "This action is not available.");
            }
            if (phaseId == null) {
              throw DomainException.invalid("phaseId", "is required for game commands");
            }
            if (!s.phaseId.toString().equalsIgnoreCase(phaseId)) {
              count("commands", "stale");
              throw new DomainException(
                  ErrorCode.STALE_ACTION, "That action belongs to an earlier phase.");
            }
            if (!spec.phases().isEmpty() && !spec.phases().contains(s.phase)) {
              throw new DomainException(
                  ErrorCode.INVALID_PHASE, "That action is not allowed in this phase.");
            }
            Transition transition;
            try {
              transition = s.module.handle(s.state, new PlayerAction(guest, type, payload), ctx(s));
            } catch (DomainException e) {
              count("commands", "rejected");
              throw e;
            } catch (RuntimeException e) {
              log.error("Game module failed handling {}", type, e);
              count("commands", "error");
              throw new DomainException(ErrorCode.SERVER_ERROR, "Something went wrong.");
            }
            if (!applyOrAbort(s, transition)) {
              throw new DomainException(ErrorCode.SERVER_ERROR, "Something went wrong.");
            }
            count("commands", "accepted");
          }
        });
  }

  /** A member connected or disconnected (after the realtime layer updated its presence view). */
  public void onConnection(String code, UUID guest, boolean connected) {
    run(
        code,
        () -> {
          ActiveSession s = live(code);
          if (s == null) {
            return;
          }
          try (MDC.MDCCloseable ignored = scope(s)) {
            if (s.status == SessionStatus.STARTING) {
              if (!connected) {
                recheckCountdown(s);
              }
              return;
            }
            RosterSlot slot = s.roster.get(guest);
            PlayerStatus next = connected ? PlayerStatus.ACTIVE : PlayerStatus.DISCONNECTED;
            if (slot == null || slot.status == PlayerStatus.LEFT || slot.status == next) {
              return;
            }
            slot.status = next;
            if (s.status == SessionStatus.RUNNING) {
              notifyModule(s, new PlayerStatusChange(guest, next));
            } else {
              evaluateRematch(s);
            }
          }
        });
  }

  /** A member left, timed out or was kicked; called after the room change committed. */
  public void onMemberLeft(String code, UUID guest) {
    run(
        code,
        () -> {
          ActiveSession s = live(code);
          if (s == null) {
            return;
          }
          try (MDC.MDCCloseable ignored = scope(s)) {
            if (rooms.state(code).status() == RoomStatus.CLOSED) {
              dropSession(s, "ROOM_CLOSED");
              return;
            }
            if (s.status == SessionStatus.STARTING) {
              recheckCountdown(s);
              return;
            }
            RosterSlot slot = s.roster.get(guest);
            if (slot == null || slot.status == PlayerStatus.LEFT) {
              return;
            }
            slot.status = PlayerStatus.LEFT;
            s.votes.remove(guest);
            if (s.status == SessionStatus.FINISHED) {
              sink.publish(code, s.roomId, "REMATCH_VOTES_UPDATED", Map.of("votes", tally(s)));
              evaluateRematch(s);
              return;
            }
            if (!notifyModule(s, new PlayerStatusChange(guest, PlayerStatus.LEFT))
                || s.status != SessionStatus.RUNNING) {
              return;
            }
            long active =
                s.roster.values().stream().filter(r -> r.status != PlayerStatus.LEFT).count();
            if (active < s.module.definition().minActivePlayers()) {
              if (s.completedRounds > 0) {
                finish(s, FinishReason.INSUFFICIENT_PLAYERS);
              } else {
                abort(s, "NO_COMPLETED_ROUND");
              }
            }
          }
        });
  }

  /**
   * The host changed: modules see the new host from their next call on, and a standing "yes" vote
   * of the new host may now reset the room.
   */
  public void onHostChanged(String code, UUID newHost) {
    run(
        code,
        () -> {
          ActiveSession s = live(code);
          if (s == null) {
            return;
          }
          s.host = newHost;
          if (s.status == SessionStatus.FINISHED) {
            evaluateRematch(s);
          }
        });
  }

  /** The room closed; everything about its session is dropped without telling anybody. */
  public void onRoomClosed(String code) {
    run(
        code,
        () -> {
          ActiveSession s = sessions.get(code);
          if (s != null) {
            dropSession(s, "ROOM_CLOSED");
          }
          lastSessions.remove(code);
        });
  }

  /** Scheduler wake-up. Carries no state: it only makes the session look at its own deadlines. */
  public void wake(String code, UUID instanceId) {
    run(
        code,
        () -> {
          ActiveSession s = sessions.get(code);
          if (s == null || !s.instanceId.equals(instanceId)) {
            return; // the session ended or was replaced after this wake-up was armed
          }
          s.handle = null;
          s.armedAt = null;
          try (MDC.MDCCloseable ignored = scope(s)) {
            drain(s);
          }
        });
  }

  /** Safety net for a lost timer: drains sessions whose earliest deadline is long overdue. */
  public void watchdog() {
    for (String code : List.copyOf(sessions.keySet())) {
      try {
        run(
            code,
            () -> {
              ActiveSession s = sessions.get(code);
              if (s == null) {
                return;
              }
              Optional<DeadlineBook.Entry> earliest = s.deadlines.earliest();
              if (earliest.isPresent()
                  && earliest
                      .get()
                      .at()
                      .plus(properties.watchdogTolerance())
                      .isBefore(clock.instant())) {
                log.warn(
                    "Deadline {} missed its timer; draining from the watchdog",
                    earliest.get().id());
                meters.counter("dinoparty.engine.deadline.missed").increment();
                drain(s);
              }
            });
      } catch (RuntimeException e) {
        log.warn("Session watchdog failed for room {}", code, e);
      }
    }
  }

  /** Graceful shutdown: abort every running game so rooms are consistent after the restart. */
  public void shutdown() {
    for (String code : List.copyOf(sessions.keySet())) {
      try {
        run(
            code,
            () -> {
              ActiveSession s = sessions.get(code);
              if (s == null) {
                return;
              }
              switch (s.status) {
                case STARTING -> cancelStart(s, "GAME_ABORTED");
                case RUNNING -> abort(s, "SERVER_SHUTDOWN");
                case FINISHED -> reset(s, "GAME_ABORTED");
              }
            });
      } catch (RuntimeException e) {
        log.warn("Could not abort session of room {} during shutdown", code, e);
      }
    }
  }

  // ---------------------------------------------------------------- read side

  /** Session facts for the room DTO. */
  public RoomSessionInfo info(String code) {
    return inRoom(
        code,
        () -> {
          ActiveSession s = live(code);
          LastSession last = lastSession(code, s);
          if (s == null || s.sessionId == null) {
            return new RoomSessionInfo(null, null, last);
          }
          return new RoomSessionInfo(
              s.sessionId, s.ledger == null ? null : s.ledger.totals(), last);
        });
  }

  /**
   * The viewer-specific game part of a snapshot, or null when no game is running or finished. Built
   * after due deadlines were applied, so it never shows an overdue phase.
   */
  public Map<String, Object> gameSnapshot(String code, UUID viewer) {
    return inRoom(
        code,
        () -> {
          ActiveSession s = live(code);
          if (s == null || s.status == SessionStatus.STARTING) {
            return null;
          }
          RosterSlot slot = s.roster.get(viewer);
          if (slot == null || slot.status == PlayerStatus.LEFT) {
            return null;
          }
          Map<String, Object> snapshot = new LinkedHashMap<>();
          snapshot.put("sessionId", s.sessionId);
          snapshot.put("gameKey", s.module.definition().key());
          snapshot.put("status", s.status == SessionStatus.FINISHED ? "FINISHED" : "RUNNING");
          snapshot.put("order", s.order);
          snapshot.put("phase", s.phase);
          snapshot.put("phaseId", s.phaseId);
          snapshot.put(
              "phaseStartedAt",
              s.phaseStartedAt == null ? null : Timestamps.format(s.phaseStartedAt));
          snapshot.put("deadlineAt", s.deadlineAt == null ? null : Timestamps.format(s.deadlineAt));
          snapshot.put("scores", s.ledger == null ? null : s.ledger.totals());
          snapshot.put("players", players(s));
          boolean finished = s.status == SessionStatus.FINISHED;
          snapshot.put("results", finished ? Map.of("standings", s.standings) : null);
          snapshot.put("rematch", finished ? Map.of("votes", tally(s)) : null);
          Map<String, Object> view = s.module.view(s.state, viewer, ctx(s));
          view.forEach(snapshot::putIfAbsent);
          return snapshot;
        });
  }

  /**
   * The room's newest session that is no longer running. Known in memory once a game of this
   * process started or ended; otherwise (after a restart) read once from the history. A failed read
   * is not cached, so the next view tries again.
   */
  private LastSession lastSession(String code, ActiveSession live) {
    Optional<LastSession> known = lastSessions.get(code);
    if (known == null) {
      try {
        UUID roomId = live != null ? live.roomId : rooms.state(code).roomId();
        known = history.latestEnded(roomId);
        lastSessions.put(code, known);
      } catch (RuntimeException e) {
        log.warn("Could not read the last session of the room", e);
        return null;
      }
    }
    return known.orElse(null);
  }

  /** True while the room has a session in any stage (countdown, running, results). */
  public boolean isActive(String code) {
    return inRoom(code, () -> sessions.containsKey(code));
  }

  /** Number of live sessions; for tests and metrics only. */
  public int activeCount() {
    return sessions.size();
  }

  // ---------------------------------------------------------------- start / countdown

  private void doStart(String code, UUID caller) {
    drainIfAny(code);
    RoomState room = rooms.state(code);
    if (!room.hostGuestId().equals(caller)) {
      throw new DomainException(ErrorCode.NOT_HOST, "Only the host can start the game.");
    }
    switch (room.status()) {
      case STARTING -> {
        return; // idempotent: the countdown is already running
      }
      case WAITING -> {}
      case CLOSED -> throw new DomainException(ErrorCode.ROOM_CLOSED, "This room is closed.");
      default ->
          throw new DomainException(ErrorCode.INVALID_PHASE, "A game is already in progress.");
    }
    GameModule module =
        registry
            .find(room.gameKey())
            .orElseThrow(
                () ->
                    new DomainException(
                        ErrorCode.GAME_NOT_FOUND, "This game is not available yet."));
    GameDefinition definition = module.definition();
    if (room.connectedCount() < definition.minPlayers()
        || room.members().size() > definition.maxPlayers()) {
      throw new DomainException(
          ErrorCode.NOT_ENOUGH_PLAYERS,
          "At least " + definition.minPlayers() + " connected players are needed.");
    }
    // Settings and the module's setup (e.g. the word pool) are resolved and frozen here, before
    // anything exists: a rejection leaves no session, no countdown and an unchanged room.
    Map<String, Object> settings = room.settings();
    GameSetup setup = prepare(module, settings, caller);
    Instant startsAt = clock.instant().plus(properties.startCountdown());
    ActiveSession s = new ActiveSession(code, room.roomId(), module, newId());
    s.settings = settings;
    s.setup = setup;
    s.deadlines.set(START_COUNTDOWN, DeadlineBook.Kind.ENGINE, null, startsAt);
    sessions.put(code, s);
    try {
      rooms.changeStatus(code, RoomStatus.STARTING, "HOST_STARTED", startsAt);
    } catch (RuntimeException e) {
      sessions.remove(code);
      throw e;
    }
    try (MDC.MDCCloseable ignored = scope(s)) {
      log.info("Game countdown started game={} startsAt={}", definition.key(), startsAt);
    }
    count("lifecycle", "starting");
    rearm(s);
  }

  private GameSetup prepare(GameModule module, Map<String, Object> settings, UUID host) {
    GameSetup setup;
    try {
      setup = module.prepare(settings, host);
    } catch (DomainException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Game module failed to prepare a start", e);
      throw new DomainException(ErrorCode.SERVER_ERROR, "Something went wrong.");
    }
    if (setup == null) {
      log.error("Game module returned no setup");
      throw new DomainException(ErrorCode.SERVER_ERROR, "Something went wrong.");
    }
    return setup;
  }

  private void recheckCountdown(ActiveSession s) {
    GameDefinition definition = s.module.definition();
    RoomState room = rooms.state(s.code);
    if (room.status() == RoomStatus.STARTING && room.connectedCount() < definition.minPlayers()) {
      cancelStart(s, "START_CANCELLED");
    }
  }

  private void cancelStart(ActiveSession s, String reason) {
    discard(s);
    log.info("Game countdown cancelled reason={}", reason);
    count("lifecycle", "cancelled");
    try {
      rooms.changeStatus(s.code, RoomStatus.WAITING, reason, null);
    } catch (RuntimeException e) {
      log.warn("Could not return room to waiting after cancelled start", e);
    }
  }

  private void completeCountdown(ActiveSession s) {
    GameDefinition definition = s.module.definition();
    RoomState room = rooms.state(s.code);
    if (room.status() != RoomStatus.STARTING) {
      discard(s);
      return;
    }
    if (room.connectedCount() < definition.minPlayers()) {
      cancelStart(s, "START_CANCELLED");
      return;
    }
    List<UUID> members = room.members().stream().map(RoomState.Member::guestId).toList();
    long seed = ByteBuffer.wrap(random.nextBytes(8)).getLong();
    s.random = new SeededRandomSource(seed);
    s.order = TurnOrder.shuffle(members, s.random);
    room.members()
        .forEach(
            m ->
                s.roster.put(
                    m.guestId(),
                    new RosterSlot(
                        new PlayerInfo(m.guestId(), m.nickname(), m.avatarKey(), m.avatarColor()),
                        m.connected() ? PlayerStatus.ACTIVE : PlayerStatus.DISCONNECTED)));
    s.sessionId = newId();
    s.host = room.hostGuestId();
    s.startedAt = clock.instant();
    s.ledger = definition.scored() ? new ScoreLedger(s.order) : null;
    try (MDC.MDCCloseable ignored = scope(s)) {
      try {
        recorder.sessionStarted(
            new SessionStart(
                s.sessionId,
                s.roomId,
                definition.key(),
                s.settings,
                s.rosterInfos(),
                seed,
                s.startedAt));
      } catch (RuntimeException e) {
        log.error("Could not store the new game session; returning to the lobby", e);
        discard(s);
        sink.publish(
            s.code,
            s.roomId,
            "GAME_ABORTED",
            Map.of("sessionId", s.sessionId, "reason", "INTERNAL_ERROR"));
        rooms.changeStatus(s.code, RoomStatus.WAITING, "GAME_ABORTED", null);
        count("lifecycle", "aborted");
        return;
      }
      rooms.changeStatus(s.code, RoomStatus.PLAYING, "COUNTDOWN_DONE", null);
      s.status = SessionStatus.RUNNING;
      lastSessions.put(s.code, Optional.empty());
      log.info("Game started game={} players={}", definition.key(), s.order.size());
      count("lifecycle", "started");
      Map<String, Object> started = new LinkedHashMap<>();
      started.put("sessionId", s.sessionId);
      started.put("gameKey", definition.key());
      started.put("settings", s.settings);
      started.put("order", s.order);
      sink.publish(s.code, s.roomId, "GAME_STARTED", started);
      Transition first;
      try {
        first = s.module.init(ctx(s));
      } catch (RuntimeException e) {
        log.error("Game module failed to initialize", e);
        abort(s, "INTERNAL_ERROR");
        return;
      }
      applyOrAbort(s, first);
    }
  }

  // ---------------------------------------------------------------- deadlines

  private void drainIfAny(String code) {
    ActiveSession s = sessions.get(code);
    if (s != null) {
      drain(s);
    }
  }

  /** Looks up the session after applying every deadline that is already due ([C-6]). */
  private ActiveSession live(String code) {
    ActiveSession s = sessions.get(code);
    if (s != null) {
      drain(s);
      s = sessions.get(code);
    }
    return s;
  }

  private void drain(ActiveSession s) {
    Instant now = clock.instant();
    for (int i = 0; i < MAX_DRAIN; i++) {
      Optional<DeadlineBook.Entry> due = s.deadlines.pollDue(now);
      if (due.isEmpty()) {
        rearm(s);
        return;
      }
      DeadlineBook.Entry entry = due.get();
      Timer.builder("dinoparty.engine.deadline.lag")
          .register(meters)
          .record(Duration.between(entry.at(), now).abs());
      fire(s, entry);
      if (sessions.get(s.code) != s) {
        return;
      }
    }
    log.error("Deadline drain did not settle; aborting the session");
    abort(s, "INTERNAL_ERROR");
  }

  private void fire(ActiveSession s, DeadlineBook.Entry entry) {
    switch (entry.id()) {
      case START_COUNTDOWN -> {
        if (s.status == SessionStatus.STARTING) {
          completeCountdown(s);
        }
      }
      case RESULTS_TIMEOUT -> {
        if (s.status == SessionStatus.FINISHED) {
          reset(s, "RESULTS_TIMEOUT");
        }
      }
      default -> {
        // A deadline of a phase that no longer exists is ignored (stale-timer protection).
        if (s.status != SessionStatus.RUNNING || !Objects.equals(entry.phaseId(), s.phaseId)) {
          return;
        }
        Transition transition;
        try (MDC.MDCCloseable ignored = scope(s)) {
          transition =
              s.module.onDeadline(
                  s.state,
                  new DeadlineFired(entry.id(), entry.phaseId(), entry.generation()),
                  ctx(s));
        } catch (RuntimeException e) {
          log.error("Game module failed on deadline {}", entry.id(), e);
          abort(s, "INTERNAL_ERROR");
          return;
        }
        applyOrAbort(s, transition);
      }
    }
  }

  private void rearm(ActiveSession s) {
    Optional<DeadlineBook.Entry> earliest = s.deadlines.earliest();
    if (earliest.isEmpty()) {
      cancelHandle(s);
      return;
    }
    Instant at = earliest.get().at();
    if (s.handle != null && at.equals(s.armedAt)) {
      return;
    }
    cancelHandle(s);
    s.handle = scheduler.arm(s.code, s.instanceId, at);
    s.armedAt = at;
  }

  private static void cancelHandle(ActiveSession s) {
    if (s.handle != null) {
      s.handle.cancel();
      s.handle = null;
      s.armedAt = null;
    }
  }

  // ---------------------------------------------------------------- transitions

  private boolean notifyModule(ActiveSession s, PlayerStatusChange change) {
    Transition transition;
    try {
      transition = s.module.onPlayerStatus(s.state, change, ctx(s));
    } catch (RuntimeException e) {
      log.error("Game module failed on player status change", e);
      abort(s, "INTERNAL_ERROR");
      return false;
    }
    return applyOrAbort(s, transition);
  }

  /** Applies a module transition; a module bug aborts the session and returns false. */
  private boolean applyOrAbort(ActiveSession s, Transition transition) {
    try {
      validate(s, transition);
    } catch (RuntimeException e) {
      log.error("Game module returned an invalid transition: {}", e.getMessage());
      abort(s, "INTERNAL_ERROR");
      return false;
    }
    apply(s, transition);
    return true;
  }

  /** Checks everything that can fail before any state is changed. */
  private void validate(ActiveSession s, Transition t) {
    GameDefinition definition = s.module.definition();
    for (OutboundEvent event : concat(t.closing(), t.events())) {
      switch (event.audience()) {
        case Audience.Public p -> {
          if (!definition.publicEvents().contains(event.type())) {
            throw new IllegalStateException("Event " + event.type() + " is not declared public");
          }
        }
        case Audience.Private pr -> {
          if (!definition.privateEvents().contains(event.type())) {
            throw new IllegalStateException("Event " + event.type() + " is not declared private");
          }
          if (!s.roster.keySet().containsAll(pr.recipients())) {
            throw new IllegalStateException("Private event for a guest outside the roster");
          }
        }
      }
    }
    if (!t.scores().isEmpty()) {
      if (s.ledger == null) {
        throw new IllegalStateException("Scores in an unscored game");
      }
      Map<UUID, Integer> projected = s.ledger.totals();
      for (ScoreDelta delta : t.scores()) {
        s.ledger.check(delta);
        projected.merge(delta.guestId(), delta.points(), Math::addExact); // overflow across deltas
      }
    }
    for (DeadlineRequest request : t.deadlines()) {
      if (request.id() == null || request.id().isBlank() || request.at() == null) {
        throw new IllegalStateException("Invalid deadline request");
      }
      if (request.id().equals(START_COUNTDOWN)
          || request.id().equals(RESULTS_TIMEOUT)
          || request.id().equals(PHASE_END)) {
        throw new IllegalStateException("Deadline id is reserved by the engine");
      }
    }
  }

  private void apply(ActiveSession s, Transition t) {
    Instant now = clock.instant();
    if (t.next() != null) {
      s.state = t.next();
    }
    t.phase()
        .ifPresent(
            change -> {
              s.deadlines.removeModuleEntries();
              s.phase = change.name();
              s.phaseId = s.nextPhaseId != null ? s.nextPhaseId : newId();
              s.nextPhaseId = null;
              s.phaseStartedAt = now;
              s.deadlineAt = change.deadlineAt();
              if (change.deadlineAt() != null) {
                s.deadlines.set(
                    PHASE_END, DeadlineBook.Kind.PHASE_END, s.phaseId, change.deadlineAt());
              }
            });
    for (DeadlineRequest request : t.deadlines()) {
      s.deadlines.set(request.id(), DeadlineBook.Kind.INTERNAL, s.phaseId, request.at());
    }
    if (!t.scores().isEmpty()) { // an unscored game has no ledger; validate() rejects its deltas
      t.scores().forEach(s.ledger::apply);
    }
    for (SubmissionRecord submission : t.submissions()) {
      recorder.submissionAccepted(s.sessionId, submission);
    }
    for (RoundRecord round : t.rounds()) {
      s.completedRounds++;
      recorder.roundCompleted(s.sessionId, round);
    }
    t.closing().forEach(e -> publish(s, e));
    t.phase().ifPresent(change -> publishPhaseStarted(s, change));
    t.events().forEach(e -> publish(s, e));
    if (t.finished()) {
      finish(s, FinishReason.COMPLETED);
    } else {
      rearm(s);
    }
  }

  private void publishPhaseStarted(ActiveSession s, PhaseChange change) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("phase", change.name());
    payload.put("phaseId", s.phaseId);
    payload.put(
        "deadlineAt", change.deadlineAt() == null ? null : Timestamps.format(change.deadlineAt()));
    change.data().forEach(payload::putIfAbsent);
    sink.publish(s.code, s.roomId, "PHASE_STARTED", payload);
  }

  private void publish(ActiveSession s, OutboundEvent event) {
    switch (event.audience()) {
      case Audience.Public p -> sink.publish(s.code, s.roomId, event.type(), event.payload());
      case Audience.Private pr ->
          pr.recipients().stream()
              .sorted()
              .forEach(
                  g -> sink.publishPrivate(s.code, s.roomId, g, event.type(), event.payload()));
    }
  }

  // ---------------------------------------------------------------- finish / abort / reset

  private void finish(ActiveSession s, FinishReason reason) {
    GameDefinition definition = s.module.definition();
    GameOutcome outcome;
    try {
      outcome = s.module.outcome(s.state, reason, ctx(s));
    } catch (RuntimeException e) {
      log.error("Game module failed to produce an outcome", e);
      abort(s, "INTERNAL_ERROR");
      return;
    }
    Instant now = clock.instant();
    List<Map<String, Object>> standings = new ArrayList<>();
    List<SessionResult.Standing> rows = new ArrayList<>();
    if (definition.scored()) {
      for (Ranking.Entry entry : Ranking.of(s.ledger.totals(), s.order)) {
        Map<String, Object> detail = outcome.detail().getOrDefault(entry.guestId(), Map.of());
        Map<String, Object> standing = new LinkedHashMap<>();
        standing.put("rank", entry.rank());
        standing.put("guestId", entry.guestId());
        standing.put("score", entry.score());
        standing.put("detail", detail);
        standings.add(standing);
        rows.add(
            new SessionResult.Standing(
                entry.rank(),
                entry.guestId(),
                s.roster.get(entry.guestId()).info.nickname(),
                entry.score(),
                detail));
      }
    }
    s.status = SessionStatus.FINISHED;
    s.standings = List.copyOf(standings);
    s.endReason = reason.name();
    s.deadlines.clear();
    s.votes.clear();
    Map<String, Object> finished = new LinkedHashMap<>();
    finished.put("sessionId", s.sessionId);
    finished.put("endReason", reason.name());
    finished.put("standings", s.standings);
    finished.put("resultsReady", false);
    sink.publish(s.code, s.roomId, "GAME_FINISHED", finished);
    recorder.sessionFinished(new SessionResult(s.sessionId, reason.name(), now, rows));
    lastSessions.put(
        s.code, Optional.of(new LastSession(s.sessionId, "FINISHED", reason.name(), now)));
    log.info("Game finished reason={} rounds={}", reason, s.completedRounds);
    count("lifecycle", "finished");
    s.deadlines.set(
        RESULTS_TIMEOUT, DeadlineBook.Kind.ENGINE, null, now.plus(properties.resultsTimeout()));
    try {
      rooms.changeStatus(s.code, RoomStatus.RESULTS, "GAME_FINISHED", null);
    } catch (RuntimeException e) {
      log.warn("Could not move room to results", e);
    }
    rearm(s);
  }

  /** Ends a running game without results; the room returns to waiting. */
  private void abort(ActiveSession s, String reason) {
    if (s.status == SessionStatus.STARTING) {
      cancelStart(s, "GAME_ABORTED");
      return;
    }
    if (s.status == SessionStatus.FINISHED) {
      reset(s, "GAME_ABORTED");
      return;
    }
    discard(s);
    sink.publish(
        s.code, s.roomId, "GAME_ABORTED", Map.of("sessionId", s.sessionId, "reason", reason));
    Instant now = clock.instant();
    recorder.sessionAborted(s.sessionId, reason, now);
    lastSessions.put(s.code, Optional.of(new LastSession(s.sessionId, "ABORTED", reason, now)));
    log.warn("Game aborted reason={}", reason);
    count("lifecycle", "aborted");
    try {
      rooms.changeStatus(s.code, RoomStatus.WAITING, "GAME_ABORTED", null);
    } catch (RuntimeException e) {
      log.warn("Could not return room to waiting after abort", e);
    }
  }

  /** Forgets a session whose room is gone; a running game is recorded as aborted. */
  private void dropSession(ActiveSession s, String reason) {
    boolean running = s.status == SessionStatus.RUNNING;
    discard(s);
    if (running) {
      recorder.sessionAborted(s.sessionId, reason, clock.instant());
      count("lifecycle", "aborted");
    }
    log.info("Session dropped reason={}", reason);
  }

  /** Results are over: the room goes back to waiting with the same members and settings. */
  private void reset(ActiveSession s, String reason) {
    discard(s);
    log.info("Room reset reason={}", reason);
    count("lifecycle", "reset");
    try {
      rooms.changeStatus(s.code, RoomStatus.WAITING, reason, null);
    } catch (RuntimeException e) {
      log.warn("Could not reset room to waiting", e);
    }
  }

  private void discard(ActiveSession s) {
    sessions.remove(s.code, s);
    cancelHandle(s);
    s.deadlines.clear();
  }

  // ---------------------------------------------------------------- rematch

  private void evaluateRematch(ActiveSession s) {
    RoomState room = rooms.state(s.code);
    if (room.status() == RoomStatus.CLOSED) {
      dropSession(s, "ROOM_CLOSED");
      return;
    }
    boolean hostYes = Boolean.TRUE.equals(s.votes.get(room.hostGuestId()));
    List<UUID> connected =
        s.roster.entrySet().stream()
            .filter(e -> e.getValue().status == PlayerStatus.ACTIVE)
            .map(Map.Entry::getKey)
            .toList();
    boolean everyone =
        !connected.isEmpty()
            && connected.stream().allMatch(g -> Boolean.TRUE.equals(s.votes.get(g)));
    if (hostYes || everyone) {
      reset(s, "REMATCH");
    }
  }

  private Map<UUID, Boolean> tally(ActiveSession s) {
    Map<UUID, Boolean> votes = new LinkedHashMap<>();
    for (UUID guest : s.order) {
      RosterSlot slot = s.roster.get(guest);
      if (slot.status != PlayerStatus.LEFT && s.votes.containsKey(guest)) {
        votes.put(guest, s.votes.get(guest));
      }
    }
    return votes;
  }

  // ---------------------------------------------------------------- helpers

  private List<Map<String, Object>> players(ActiveSession s) {
    List<Map<String, Object>> players = new ArrayList<>();
    for (UUID guest : s.order) {
      Map<String, Object> player = new LinkedHashMap<>();
      player.put("guestId", guest);
      player.put("status", s.roster.get(guest).status.name());
      players.add(player);
    }
    return players;
  }

  private void requireMember(String code, UUID guest) {
    if (!rooms.state(code).hasMember(guest)) {
      throw new DomainException(ErrorCode.NOT_IN_ROOM, "You are not a member of this room.");
    }
  }

  private GameContext ctx(ActiveSession s) {
    // Minted per module call and consumed by apply() if that call's transition starts a phase.
    s.nextPhaseId = newId();
    return new GameContext(
        s.sessionId,
        clock,
        s.random,
        s.rosterInfos(),
        s.order,
        s.settings,
        s.setup,
        s.nextPhaseId,
        s.host);
  }

  private <T> T inRoom(String code, Supplier<T> action) {
    return rooms.inRoom(code, action);
  }

  private void run(String code, Runnable action) {
    rooms.inRoom(
        code,
        () -> {
          action.run();
          return null;
        });
  }

  private static MDC.MDCCloseable scope(ActiveSession s) {
    if (s.phaseId != null) {
      MDC.put("phaseId", s.phaseId.toString());
    }
    return MDC.putCloseable("sessionId", Objects.toString(s.sessionId, ""));
  }

  private UUID newId() {
    byte[] bytes = random.nextBytes(16);
    bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
    bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    return new UUID(buffer.getLong(), buffer.getLong());
  }

  private void count(String name, String result) {
    meters.counter("dinoparty.engine." + name, "result", result).increment();
  }

  private static <T> List<T> concat(List<T> a, List<T> b) {
    List<T> all = new ArrayList<>(a);
    all.addAll(b);
    return all;
  }
}
