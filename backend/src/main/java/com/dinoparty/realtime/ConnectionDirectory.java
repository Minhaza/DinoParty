package com.dinoparty.realtime;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The single live STOMP connection of each guest (latest wins). Every connection is its own
 * principal ({@code guestId_wsSessionId}), so a private message resolves to exactly one socket:
 * user-destination fan-out to several sockets of one user is never needed, and a replaced
 * connection stops receiving private events the moment its successor is registered.
 */
@Component
class ConnectionDirectory {

  record Connection(UUID guestId, UUID guestSessionId, String wsSessionId) {
    String principalName() {
      return ConnectionDirectory.principalName(guestId, wsSessionId);
    }
  }

  static String principalName(UUID guestId, String wsSessionId) {
    return guestId + "_" + wsSessionId;
  }

  private final ConcurrentHashMap<UUID, Connection> live = new ConcurrentHashMap<>();

  /** Registers the newest connection; returns the one it replaced, if a different socket. */
  Optional<Connection> register(Connection connection) {
    Connection previous = live.put(connection.guestId(), connection);
    return Optional.ofNullable(previous)
        .filter(p -> !p.wsSessionId().equals(connection.wsSessionId()));
  }

  /** Removes the connection only if it is still the guest's current one. */
  boolean unregister(UUID guestId, String wsSessionId) {
    Connection current = live.get(guestId);
    return current != null
        && current.wsSessionId().equals(wsSessionId)
        && live.remove(guestId, current);
  }

  Optional<Connection> current(UUID guestId) {
    return Optional.ofNullable(live.get(guestId));
  }

  boolean isConnected(UUID guestId) {
    return live.containsKey(guestId);
  }

  Collection<Connection> all() {
    return live.values();
  }

  int size() {
    return live.size();
  }
}
