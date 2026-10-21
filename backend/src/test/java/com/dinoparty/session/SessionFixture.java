package com.dinoparty.session;

import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.engine.GameModule;
import com.dinoparty.engine.GameRegistry;
import com.dinoparty.engine.RoundRecord;
import com.dinoparty.engine.SubmissionRecord;
import com.dinoparty.games.testgame.TestGameModule;
import com.dinoparty.room.RoomStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** In-memory collaborators so the engine runs without Spring, a database or real time. */
final class SessionFixture {

  static final String CODE = "ABC234";

  /** A clock the test moves by hand. */
  static final class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2026-01-01T12:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  record StatusChange(RoomStatus status, String reason) {}

  /** Room module stand-in: one room, a fair per-room lock like the real mailbox. */
  static final class FakeRoom implements RoomLifecycle {
    final UUID roomId = UUID.randomUUID();
    final List<RoomState.Member> members = new CopyOnWriteArrayList<>();
    final List<StatusChange> changes = new CopyOnWriteArrayList<>();
    volatile RoomStatus status = RoomStatus.WAITING;
    volatile UUID host;
    volatile String gameKey = TestGameModule.KEY;
    volatile Map<String, Object> settings = Map.of();
    private final ReentrantLock lock = new ReentrantLock(true);

    UUID addMember(boolean connected) {
      UUID id = UUID.randomUUID();
      members.add(new RoomState.Member(id, "P" + members.size(), "dino-rex", "#336699", connected));
      if (host == null) {
        host = id;
      }
      return id;
    }

    void setConnected(UUID guest, boolean connected) {
      for (int i = 0; i < members.size(); i++) {
        RoomState.Member m = members.get(i);
        if (m.guestId().equals(guest)) {
          members.set(
              i,
              new RoomState.Member(guest, m.nickname(), m.avatarKey(), m.avatarColor(), connected));
        }
      }
    }

    void removeMember(UUID guest) {
      members.removeIf(m -> m.guestId().equals(guest));
    }

    @Override
    public <T> T inRoom(String roomCode, Supplier<T> action) {
      lock.lock();
      try {
        return action.get();
      } finally {
        lock.unlock();
      }
    }

    @Override
    public RoomState state(String roomCode) {
      return new RoomState(roomId, status, gameKey, host, settings, List.copyOf(members));
    }

    @Override
    public void changeStatus(String roomCode, RoomStatus to, String reason, Instant startsAt) {
      status = to;
      changes.add(new StatusChange(to, reason));
    }

    @Override
    public int resetAfterRestart() {
      return 0;
    }

    List<String> reasons() {
      return changes.stream().map(c -> c.status() + ":" + c.reason()).toList();
    }
  }

  /** The stored history as seen by {@link EndedSessionLookup}; counts its lookups. */
  static final class FakeHistory implements EndedSessionLookup {
    volatile LastSession stored;
    final AtomicInteger lookups = new AtomicInteger();

    @Override
    public Optional<LastSession> latestEnded(UUID roomId) {
      lookups.incrementAndGet();
      return Optional.ofNullable(stored);
    }
  }

  record Published(UUID recipient, String type, Map<String, Object> payload) {
    boolean isPublic() {
      return recipient == null;
    }
  }

  static final class RecordingSink implements SessionEventSink {
    final List<Published> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(String roomCode, UUID roomId, String type, Map<String, Object> payload) {
      events.add(new Published(null, type, payload));
    }

    @Override
    public void publishPrivate(
        String roomCode, UUID roomId, UUID guestId, String type, Map<String, Object> payload) {
      events.add(new Published(guestId, type, payload));
    }

    List<String> publicTypes() {
      return events.stream().filter(Published::isPublic).map(Published::type).toList();
    }

    List<Published> ofType(String type) {
      return events.stream().filter(e -> e.type().equals(type)).toList();
    }
  }

  static final class RecordingRecorder implements SessionRecorder {
    final List<SessionStart> starts = new CopyOnWriteArrayList<>();
    final List<RoundRecord> rounds = new CopyOnWriteArrayList<>();
    final List<SessionResult> results = new CopyOnWriteArrayList<>();
    final List<String> aborts = new CopyOnWriteArrayList<>();
    final List<SubmissionRecord> submissions = new CopyOnWriteArrayList<>();

    /** Every call in order, so tests can check that submissions precede the finish. */
    final List<String> log = new CopyOnWriteArrayList<>();

    volatile boolean failStart;

    @Override
    public void sessionStarted(SessionStart start) {
      if (failStart) {
        throw new IllegalStateException("database down");
      }
      starts.add(start);
    }

    @Override
    public void roundCompleted(UUID sessionId, RoundRecord round) {
      rounds.add(round);
    }

    @Override
    public void sessionFinished(SessionResult result) {
      results.add(result);
      log.add("finished");
    }

    @Override
    public void submissionAccepted(UUID sessionId, SubmissionRecord submission) {
      submissions.add(submission);
      log.add("submission");
    }

    @Override
    public void sessionAborted(UUID sessionId, String reason, Instant at) {
      aborts.add(reason);
    }

    @Override
    public int interruptRunning(Instant at) {
      return 0;
    }
  }

  /** Timer stand-in: wake-ups run only when the test says so. */
  static final class ManualScheduler implements DeadlineScheduler {
    record Armed(String code, UUID instance, Instant at, boolean[] cancelled) {}

    final List<Armed> armed = Collections.synchronizedList(new ArrayList<>());
    final ConcurrentHashMap<String, Integer> armCount = new ConcurrentHashMap<>();
    SessionService target;

    @Override
    public Handle arm(String roomCode, UUID sessionInstance, Instant at) {
      boolean[] cancelled = new boolean[1];
      armed.add(new Armed(roomCode, sessionInstance, at, cancelled));
      armCount.merge(roomCode, 1, Integer::sum);
      return () -> cancelled[0] = true;
    }

    /**
     * Fires every armed wake-up that is due, like a timer thread would, even cancelled ones twice.
     */
    void fireDue(Clock clock, boolean includeCancelled) {
      List<Armed> due;
      synchronized (armed) {
        due = armed.stream().filter(a -> !a.at().isAfter(clock.instant())).toList();
        armed.removeAll(due);
      }
      for (Armed a : due) {
        if (includeCancelled || !a.cancelled()[0]) {
          target.wake(a.code(), a.instance());
        }
      }
    }

    long liveHandles() {
      synchronized (armed) {
        return armed.stream().filter(a -> !a.cancelled()[0]).count();
      }
    }
  }

  final MutableClock clock = new MutableClock();
  final FakeRoom room = new FakeRoom();
  final RecordingSink sink = new RecordingSink();
  final RecordingRecorder recorder = new RecordingRecorder();
  final ManualScheduler scheduler = new ManualScheduler();
  final FakeHistory history = new FakeHistory();
  final SessionService sessions;
  final TestGameModule module;

  SessionFixture() {
    this(new TestGameModule(60_000, 2));
  }

  SessionFixture(GameModule module) {
    this.module = module instanceof TestGameModule t ? t : null;
    room.gameKey = module.definition().key();
    sessions =
        new SessionService(
            new GameRegistry(List.of(module)),
            room,
            sink,
            recorder,
            history,
            scheduler,
            clock,
            new SeededRandomSource(7),
            new SessionProperties(
                Duration.ofSeconds(3), Duration.ofMinutes(15), Duration.ofSeconds(1), false, false),
            new SimpleMeterRegistry());
    scheduler.target = sessions;
  }

  /** Adds {@code n} connected members; the first is the host. */
  List<UUID> players(int n) {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ids.add(room.addMember(true));
    }
    return ids;
  }

  UUID host() {
    return room.host;
  }

  /** Starts and lets the 3 s countdown elapse so the first phase is running. */
  void startAndPlay() {
    sessions.start(CODE, host());
    clock.advance(Duration.ofSeconds(3));
    scheduler.fireDue(clock, false);
  }

  /** The phase id a client would currently use, taken from the latest PHASE_STARTED. */
  String phaseId() {
    List<Published> phases = sink.ofType("PHASE_STARTED");
    return phases.getLast().payload().get("phaseId").toString();
  }
}
