package com.dinoparty.room;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.RateLimiter;
import com.dinoparty.guest.Guest;
import com.dinoparty.guest.GuestService;
import com.dinoparty.room.persistence.RoomEntity;
import com.dinoparty.room.persistence.RoomParticipantEntity;
import com.dinoparty.room.persistence.RoomParticipantRepository;
import com.dinoparty.room.persistence.RoomRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Room lifecycle and membership. Every mutation of an existing room runs inside {@link RoomMailbox}
 * (one command at a time per room) and a transaction that holds the room row lock, so capacity and
 * host transfer cannot be raced. The authenticated guest id is always passed in by the caller's
 * principal, never read from a request body.
 */
@Service
public class RoomService {

  private static final Logger log = LoggerFactory.getLogger(RoomService.class);
  private static final int DEFAULT_MAX_PLAYERS = 8;
  private static final int CODE_ATTEMPTS = 8;

  private final RoomRepository rooms;
  private final RoomParticipantRepository participants;
  private final GameCatalog games;
  private final SettingsCodec settingsCodec;
  private final RoomCodes codes;
  private final RoomMailbox mailbox;
  private final GuestService guests;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final RoomProperties properties;
  private final TransactionTemplate tx;
  private final TransactionTemplate readOnlyTx;
  private final ObservationRegistry observations;
  private final ApplicationEventPublisher events;
  private final ObjectProvider<MemberPresence> presence;

  RoomService(
      RoomRepository rooms,
      RoomParticipantRepository participants,
      GameCatalog games,
      SettingsCodec settingsCodec,
      RoomCodes codes,
      RoomMailbox mailbox,
      GuestService guests,
      RateLimiter rateLimiter,
      Clock clock,
      RoomProperties properties,
      PlatformTransactionManager transactionManager,
      ObservationRegistry observations,
      ApplicationEventPublisher events,
      ObjectProvider<MemberPresence> presence) {
    this.rooms = rooms;
    this.participants = participants;
    this.games = games;
    this.settingsCodec = settingsCodec;
    this.codes = codes;
    this.mailbox = mailbox;
    this.guests = guests;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.properties = properties;
    this.observations = observations;
    this.events = events;
    this.presence = presence;
    this.tx = new TransactionTemplate(transactionManager);
    this.readOnlyTx = new TransactionTemplate(transactionManager);
    this.readOnlyTx.setReadOnly(true);
  }

  public RoomDetails create(UUID guestId, CreateRoomCommand command) {
    return observed("create", null, () -> doCreate(guestId, command));
  }

  private RoomDetails doCreate(UUID guestId, CreateRoomCommand command) {
    GameInfo game = games.require(command.gameKey());
    Map<String, Object> settings =
        SettingsSchema.resolve(game.key(), game.defaultSettings(), command.settings());
    int maxPlayers = resolveMaxPlayers(game, command.maxPlayers(), 0);

    for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
      try {
        RoomDetails created = tx.execute(status -> insertRoom(guestId, game, settings, maxPlayers));
        if (created != null) {
          return created;
        }
      } catch (DataIntegrityViolationException e) {
        // Either the generated code collided or the guest raced into another room.
        if (participants.existsByGuestIdAndLeftAtIsNull(guestId)) {
          throw alreadyInRoom();
        }
      }
      log.debug("Room code collision, retrying (attempt {})", attempt + 1);
    }
    throw new IllegalStateException("Could not allocate a unique room code");
  }

  public RoomDetails join(UUID guestId, String rawCode) {
    String code = requireCode(rawCode);
    rateLimiter.check("room-join:" + guestId, properties.joinLimitPerMinute(), 60);
    return observed(
        "join", code, () -> mailbox.run(code, () -> tx.execute(status -> joinInTx(guestId, code))));
  }

  public void leave(UUID guestId, String rawCode) {
    leave(guestId, rawCode, LeaveReason.LEFT);
  }

  /**
   * Removes a member; {@code reason} is chosen by the caller (the REST leave endpoint, or the
   * presence sweeper for {@link LeaveReason#TIMEOUT}), never by a client.
   */
  public void leave(UUID guestId, String rawCode, LeaveReason reason) {
    String code = requireCode(rawCode);
    observed(
        "leave",
        code,
        () ->
            mailbox.run(
                code,
                () ->
                    tx.execute(
                        status -> {
                          leaveInTx(guestId, code, reason);
                          return null;
                        })));
  }

  /** Server-authoritative ready flag; only members of a waiting room may change it. */
  public RoomDetails setReady(UUID guestId, String rawCode, boolean ready) {
    String code = requireCode(rawCode);
    return observed(
        "set-ready",
        code,
        () -> mailbox.run(code, () -> tx.execute(status -> readyInTx(guestId, code, ready))));
  }

  /**
   * Runs {@code action} inside the room's mailbox (one command at a time per room). Realtime code
   * uses it so presence changes, snapshots and sequence numbers are ordered with room mutations.
   */
  public <T> T inRoom(String rawCode, Supplier<T> action) {
    return mailbox.run(requireCode(rawCode), action);
  }

  /** Full details for an active member; {@code NOT_IN_ROOM} for everybody else. */
  public RoomDetails getForMember(UUID guestId, String rawCode) {
    String code = requireCode(rawCode);
    return readOnlyTx.execute(
        status -> {
          RoomEntity room = rooms.findByCode(code).orElseThrow(RoomService::notFound);
          if (!isActiveMember(room, guestId)) {
            throw notInRoom();
          }
          return details(room);
        });
  }

  public boolean isMember(UUID guestId, String rawCode) {
    return RoomCodes.normalize(rawCode)
        .flatMap(rooms::findByCode)
        .map(room -> isActiveMember(room, guestId))
        .orElse(false);
  }

  /** The room the guest is currently an active member of, if any. */
  public Optional<RoomRef> activeRoom(UUID guestId) {
    return participants
        .findByGuestIdAndLeftAtIsNull(guestId)
        .flatMap(p -> rooms.findById(p.getRoomId()))
        .map(r -> new RoomRef(r.getId(), r.getCode()));
  }

  /** Looks a room up by code regardless of membership; used to address realtime events. */
  public Optional<RoomRef> find(String rawCode) {
    return RoomCodes.normalize(rawCode)
        .flatMap(rooms::findByCode)
        .map(r -> new RoomRef(r.getId(), r.getCode()));
  }

  /** Members get full details; any other authenticated guest gets a preview. */
  public RoomView get(UUID guestId, String rawCode) {
    String code = requireCode(rawCode);
    return readOnlyTx.execute(
        status -> {
          RoomEntity room = rooms.findByCode(code).orElseThrow(RoomService::notFound);
          return isActiveMember(room, guestId) ? details(room) : preview(room);
        });
  }

  public RoomDetails updateSettings(
      UUID guestId, String rawCode, UpdateRoomSettingsCommand command) {
    String code = requireCode(rawCode);
    return observed(
        "update-settings",
        code,
        () -> mailbox.run(code, () -> tx.execute(status -> updateInTx(guestId, code, command))));
  }

  /**
   * Wraps a room operation in an observation (a timer metric plus a trace span) and puts the room
   * code in the log MDC. Only the operation name is a metric tag; the room code is a span attribute
   * so metric cardinality stays bounded. Guest ids, tokens and settings are deliberately not
   * recorded.
   */
  private <T> T observed(String operation, String roomCode, Supplier<T> body) {
    Observation observation =
        Observation.createNotStarted("dinoparty.room." + operation, observations);
    if (roomCode != null) {
      observation.highCardinalityKeyValue("room.code", roomCode);
    }
    try (MDC.MDCCloseable ignored =
        roomCode == null ? null : MDC.putCloseable("roomCode", roomCode)) {
      return observation.observe(body);
    }
  }

  private RoomDetails insertRoom(
      UUID guestId, GameInfo game, Map<String, Object> settings, int maxPlayers) {
    if (participants.existsByGuestIdAndLeftAtIsNull(guestId)) {
      throw alreadyInRoom();
    }
    Instant now = clock.instant();
    String code = codes.generate();
    if (rooms.existsByCode(code)) {
      // Cheap pre-check; the unique index is still the authority (see create()).
      return null;
    }
    RoomEntity room =
        rooms.saveAndFlush(
            new RoomEntity(
                UUID.randomUUID(),
                code,
                game.key(),
                guestId,
                maxPlayers,
                settingsCodec.write(settings),
                now));
    participants.saveAndFlush(
        new RoomParticipantEntity(UUID.randomUUID(), room.getId(), guestId, now));
    return details(room);
  }

  private RoomDetails joinInTx(UUID guestId, String code) {
    RoomEntity room = rooms.findByCodeForUpdate(code).orElseThrow(RoomService::notFound);
    Optional<RoomParticipantEntity> existing =
        participants.findByRoomIdAndGuestId(room.getId(), guestId);
    if (existing.isPresent() && existing.get().isActive()) {
      return details(room); // idempotent: refreshing or double-clicking must not fail
    }
    switch (room.getStatus()) {
      case WAITING -> {}
      case CLOSED -> throw new DomainException(ErrorCode.ROOM_CLOSED, "This room is closed.");
      default ->
          throw new DomainException(ErrorCode.ROOM_IN_PROGRESS, "This room is already playing.");
    }
    if (participants.existsByGuestIdAndLeftAtIsNull(guestId)) {
      throw alreadyInRoom();
    }
    if (participants.countByRoomIdAndLeftAtIsNull(room.getId()) >= room.getMaxPlayers()) {
      throw new DomainException(
          ErrorCode.ROOM_FULL, "This room already has " + room.getMaxPlayers() + " players.");
    }
    Instant now = clock.instant();
    try {
      if (existing.isPresent()) {
        existing.get().rejoin(now);
        participants.flush();
      } else {
        participants.saveAndFlush(
            new RoomParticipantEntity(UUID.randomUUID(), room.getId(), guestId, now));
      }
    } catch (DataIntegrityViolationException e) {
      // The guest joined a different room between our check and the insert.
      throw alreadyInRoom();
    }
    RoomDetails joined = details(room);
    joined.members().stream()
        .filter(m -> m.guestId().equals(guestId))
        .findFirst()
        .ifPresent(
            member ->
                events.publishEvent(
                    new RoomEvent.MemberJoined(room.getId(), code, member, room.getHostGuestId())));
    return joined;
  }

  private void leaveInTx(UUID guestId, String code, LeaveReason reason) {
    RoomEntity room = rooms.findByCodeForUpdate(code).orElseThrow(RoomService::notFound);
    Optional<RoomParticipantEntity> leaving =
        participants.findByRoomIdAndGuestId(room.getId(), guestId).filter(p -> p.isActive());
    if (leaving.isEmpty()) {
      return; // idempotent no-op
    }
    Instant now = clock.instant();
    leaving.get().leave(now);
    participants.flush();

    events.publishEvent(new RoomEvent.MemberLeft(room.getId(), code, guestId, reason));
    List<RoomParticipantEntity> remaining =
        participants.findByRoomIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(room.getId());
    if (remaining.isEmpty()) {
      room.close(now);
      events.publishEvent(new RoomEvent.Closed(room.getId(), code, "EMPTY"));
    } else if (room.getHostGuestId().equals(guestId)) {
      UUID newHost = nextHost(code, remaining);
      room.transferHost(newHost);
      events.publishEvent(new RoomEvent.HostChanged(room.getId(), code, newHost));
    }
  }

  /** Longest-standing player who is not currently disconnected; else the longest-standing. */
  private UUID nextHost(String code, List<RoomParticipantEntity> remaining) {
    MemberPresence view = presence.getIfAvailable();
    return remaining.stream()
        .map(RoomParticipantEntity::getGuestId)
        .filter(g -> view == null || !view.isDisconnected(code, g))
        .findFirst()
        .orElseGet(() -> remaining.getFirst().getGuestId());
  }

  private RoomDetails readyInTx(UUID guestId, String code, boolean ready) {
    RoomEntity room = rooms.findByCodeForUpdate(code).orElseThrow(RoomService::notFound);
    RoomParticipantEntity member =
        participants
            .findByRoomIdAndGuestId(room.getId(), guestId)
            .filter(RoomParticipantEntity::isActive)
            .orElseThrow(RoomService::notInRoom);
    if (room.getStatus() != RoomStatus.WAITING) {
      throw new DomainException(
          ErrorCode.INVALID_PHASE, "Ready can only be changed while the room is waiting.");
    }
    if (member.isReady() != ready) {
      member.setReady(ready);
      participants.flush();
      events.publishEvent(new RoomEvent.ReadyChanged(room.getId(), code, guestId, ready));
    }
    return details(room);
  }

  private RoomDetails updateInTx(UUID guestId, String code, UpdateRoomSettingsCommand command) {
    RoomEntity room = rooms.findByCodeForUpdate(code).orElseThrow(RoomService::notFound);
    if (!room.getHostGuestId().equals(guestId)) {
      throw new DomainException(ErrorCode.NOT_HOST, "Only the host can change room settings.");
    }
    if (room.getStatus() != RoomStatus.WAITING) {
      throw new DomainException(
          ErrorCode.INVALID_PHASE, "Settings can only be changed while the room is waiting.");
    }
    boolean gameChanges = command.gameKey() != null && !command.gameKey().equals(room.getGameKey());
    GameInfo game =
        games.require(command.gameKey() == null ? room.getGameKey() : command.gameKey());
    long playerCount = participants.countByRoomIdAndLeftAtIsNull(room.getId());

    Map<String, Object> settings;
    if (command.settings() != null || gameChanges) {
      settings = SettingsSchema.resolve(game.key(), game.defaultSettings(), command.settings());
    } else {
      settings = settingsCodec.read(room.getSettings());
    }
    Integer requested = command.maxPlayers();
    if (requested == null) {
      // Keep the current limit unless the new game cannot accommodate it.
      requested = Math.max(game.minPlayers(), Math.min(room.getMaxPlayers(), game.maxPlayers()));
    }
    int maxPlayers = resolveMaxPlayers(game, requested, playerCount);
    room.configure(game.key(), settingsCodec.write(settings), maxPlayers);
    events.publishEvent(
        new RoomEvent.SettingsChanged(
            room.getId(), code, game.key(), settings, game.minPlayers(), maxPlayers));
    return details(room);
  }

  private static int resolveMaxPlayers(GameInfo game, Integer requested, long currentPlayers) {
    int value =
        requested == null
            ? Math.max(game.minPlayers(), Math.min(DEFAULT_MAX_PLAYERS, game.maxPlayers()))
            : requested;
    if (value < game.minPlayers() || value > game.maxPlayers()) {
      throw DomainException.invalid(
          "maxPlayers",
          "must be between " + game.minPlayers() + " and " + game.maxPlayers() + " for this game");
    }
    if (value < currentPlayers) {
      throw DomainException.invalid(
          "maxPlayers", "cannot be lower than the current player count (" + currentPlayers + ")");
    }
    return value;
  }

  private RoomDetails details(RoomEntity room) {
    GameInfo game = games.require(room.getGameKey());
    List<RoomParticipantEntity> active =
        participants.findByRoomIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(room.getId());
    Map<UUID, Guest> byId =
        guests.getAll(active.stream().map(RoomParticipantEntity::getGuestId).toList()).stream()
            .collect(Collectors.toMap(Guest::id, Function.identity()));
    List<RoomMember> members =
        active.stream()
            .map(
                p -> {
                  Guest g = byId.get(p.getGuestId());
                  return new RoomMember(
                      g.id(),
                      g.nickname(),
                      g.avatarKey(),
                      g.avatarColor(),
                      p.isReady(),
                      p.getJoinedAt());
                })
            .toList();
    return new RoomDetails(
        room.getId(),
        room.getCode(),
        room.getStatus(),
        room.getGameKey(),
        room.getHostGuestId(),
        game.minPlayers(),
        room.getMaxPlayers(),
        settingsCodec.read(room.getSettings()),
        members,
        room.getCreatedAt());
  }

  private RoomPreview preview(RoomEntity room) {
    return new RoomPreview(
        room.getCode(),
        room.getGameKey(),
        room.getStatus(),
        (int) participants.countByRoomIdAndLeftAtIsNull(room.getId()),
        room.getMaxPlayers(),
        guests.get(room.getHostGuestId()).nickname());
  }

  private boolean isActiveMember(RoomEntity room, UUID guestId) {
    return participants
        .findByRoomIdAndGuestId(room.getId(), guestId)
        .filter(RoomParticipantEntity::isActive)
        .isPresent();
  }

  private static DomainException notInRoom() {
    return new DomainException(ErrorCode.NOT_IN_ROOM, "You are not a member of this room.");
  }

  private static String requireCode(String rawCode) {
    return RoomCodes.normalize(rawCode).orElseThrow(RoomService::notFound);
  }

  private static DomainException notFound() {
    return new DomainException(ErrorCode.ROOM_NOT_FOUND, "No room with that code exists.");
  }

  private static DomainException alreadyInRoom() {
    return new DomainException(
        ErrorCode.ALREADY_IN_ROOM, "Leave your current room before joining another one.");
  }
}
