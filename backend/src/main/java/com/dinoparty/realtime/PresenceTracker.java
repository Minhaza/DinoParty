package com.dinoparty.realtime;

import com.dinoparty.common.Timestamps;
import com.dinoparty.room.LeaveReason;
import com.dinoparty.room.MemberPresence;
import com.dinoparty.room.RoomRef;
import com.dinoparty.room.RoomService;
import com.dinoparty.session.SessionService;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Who is connected and who is inside the disconnect grace period.
 *
 * <p>Socket facts ({@link #connected}, {@link #disconnected}) update the in-memory connection table
 * immediately and in order; their effect on a room (events, timers) is applied afterwards by {@link
 * #reconcile}, which runs inside the room mailbox and derives the transition from the current
 * state. Because it is state-based, late or reordered notifications cannot produce a duplicate or
 * wrong event. A member who has never opened a socket is treated as connected and is never timed
 * out (REST-only clients, see docs/api-contracts.md §2.2).
 */
@Component
public class PresenceTracker implements MemberPresence {

  private static final Logger log = LoggerFactory.getLogger(PresenceTracker.class);

  private record Pending(UUID roomId, Instant graceEndsAt) {}

  private final RoomService rooms;
  private final SessionService sessions;
  private final RoomEventPublisher publisher;
  private final ConnectionDirectory connections;
  private final Clock clock;
  private final RealtimeProperties properties;
  private final Executor executor;
  private final ConcurrentHashMap<String, ConcurrentHashMap<UUID, Pending>> disconnected =
      new ConcurrentHashMap<>();

  /**
   * Closed sockets whose reconcile has not run yet, per guest. Between the two the guest is neither
   * connected nor in a grace period, and must still not look gone (see {@link #isLive}).
   */
  private final ConcurrentHashMap<UUID, Integer> unreconciled = new ConcurrentHashMap<>();

  PresenceTracker(
      RoomService rooms,
      SessionService sessions,
      RoomEventPublisher publisher,
      ConnectionDirectory connections,
      Clock clock,
      RealtimeProperties properties,
      @Qualifier("presenceExecutor") Executor executor) {
    this.rooms = rooms;
    this.sessions = sessions;
    this.publisher = publisher;
    this.connections = connections;
    this.clock = clock;
    this.properties = properties;
    this.executor = executor;
  }

  /** Registers the guest's newest connection; returns the one it replaced, if any. */
  Optional<ConnectionDirectory.Connection> connected(ConnectionDirectory.Connection connection) {
    Optional<ConnectionDirectory.Connection> replaced = connections.register(connection);
    executor.execute(() -> reconcile(connection.guestId()));
    return replaced;
  }

  /** Called when a socket closes; ignored when that socket was already replaced by a newer one. */
  void disconnected(UUID guestId, String wsSessionId) {
    unreconciled.merge(guestId, 1, Integer::sum);
    if (!connections.unregister(guestId, wsSessionId)) {
      settled(guestId);
      return;
    }
    try {
      executor.execute(
          () -> {
            try {
              reconcile(guestId);
            } finally {
              settled(guestId);
            }
          });
    } catch (RuntimeException e) {
      settled(guestId);
      throw e;
    }
  }

  private void settled(UUID guestId) {
    unreconciled.computeIfPresent(guestId, (g, n) -> n > 1 ? n - 1 : null);
  }

  /** Applies the guest's current connection state to their room (idempotent). */
  void reconcile(UUID guestId) {
    try {
      rooms
          .activeRoom(guestId)
          .ifPresent(
              room ->
                  rooms.inRoom(
                      room.code(),
                      () -> {
                        applyPresence(guestId, room);
                        return null;
                      }));
    } catch (RuntimeException e) {
      log.warn("Presence update failed", e);
    }
  }

  private void applyPresence(UUID guestId, RoomRef room) {
    boolean stillMember =
        rooms.activeRoom(guestId).map(RoomRef::code).filter(room.code()::equals).isPresent();
    if (!stillMember) {
      return;
    }
    try (MDC.MDCCloseable g = MDC.putCloseable("guestId", guestId.toString());
        MDC.MDCCloseable r = MDC.putCloseable("roomCode", room.code())) {
      if (connections.isConnected(guestId)) {
        Map<UUID, Pending> pending = disconnected.get(room.code());
        if (pending != null && pending.remove(guestId) != null) {
          log.info("Player reconnected within grace period");
          publisher.publish(
              room.code(), room.id(), "PLAYER_RECONNECTED", Map.of("guestId", guestId));
          sessions.onConnection(room.code(), guestId, true);
        }
      } else {
        // A running game is more forgiving than a lobby: a blip must not cost a player their seat.
        Instant endsAt =
            clock
                .instant()
                .plus(
                    sessions.info(room.code()).sessionId() != null
                        ? properties.inGameDisconnectGrace()
                        : properties.disconnectGrace());
        Pending previous =
            disconnected
                .computeIfAbsent(room.code(), k -> new ConcurrentHashMap<>())
                .putIfAbsent(guestId, new Pending(room.id(), endsAt));
        if (previous == null) {
          log.info("Player disconnected; grace period ends at {}", endsAt);
          publisher.publish(
              room.code(),
              room.id(),
              "PLAYER_DISCONNECTED",
              Map.of("guestId", guestId, "graceEndsAt", Timestamps.format(endsAt)));
          sessions.onConnection(room.code(), guestId, false);
        }
      }
    }
  }

  /** Removes members whose grace period ended; runs periodically and is safe to call any time. */
  void sweep() {
    Instant now = clock.instant();
    disconnected.forEach(
        (code, pending) ->
            pending.forEach(
                (guestId, p) -> {
                  if (!p.graceEndsAt().isAfter(now)) {
                    expire(code, guestId);
                  }
                }));
    disconnected
        .keySet()
        .forEach(code -> disconnected.computeIfPresent(code, (k, v) -> v.isEmpty() ? null : v));
  }

  private void expire(String code, UUID guestId) {
    try {
      rooms.inRoom(
          code,
          () -> {
            Map<UUID, Pending> pending = disconnected.get(code);
            Pending p = pending == null ? null : pending.get(guestId);
            // Re-check under the mailbox: the guest may have reconnected or left meanwhile.
            if (p == null
                || p.graceEndsAt().isAfter(clock.instant())
                || connections.isConnected(guestId)) {
              return null;
            }
            try (MDC.MDCCloseable g = MDC.putCloseable("guestId", guestId.toString());
                MDC.MDCCloseable r = MDC.putCloseable("roomCode", code)) {
              log.info("Grace period expired; removing player");
              rooms.leave(guestId, code, LeaveReason.TIMEOUT);
            }
            pending.remove(guestId);
            return null;
          });
    } catch (RuntimeException e) {
      log.warn("Could not expire disconnected player in room {}", code, e);
    }
  }

  void forgetMember(String roomCode, UUID guestId) {
    Map<UUID, Pending> pending = disconnected.get(roomCode);
    if (pending != null) {
      pending.remove(guestId);
    }
  }

  void forgetRoom(String roomCode) {
    disconnected.remove(roomCode);
  }

  @Override
  public boolean isDisconnected(String roomCode, UUID guestId) {
    Map<UUID, Pending> pending = disconnected.get(roomCode);
    return pending != null && pending.containsKey(guestId);
  }

  @Override
  public boolean isLive(String roomCode, UUID guestId) {
    return connections.isConnected(guestId)
        || unreconciled.containsKey(guestId)
        || isDisconnected(roomCode, guestId);
  }

  boolean hasLiveConnection(UUID guestId) {
    return connections.isConnected(guestId);
  }

  Collection<ConnectionDirectory.Connection> liveConnections() {
    return connections.all();
  }

  int liveCount() {
    return connections.size();
  }

  int disconnectedCount() {
    return disconnected.values().stream().mapToInt(Map::size).sum();
  }
}
