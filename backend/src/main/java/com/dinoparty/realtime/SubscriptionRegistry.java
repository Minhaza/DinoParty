package com.dinoparty.realtime;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Subscriptions that passed the membership check. The outbound guard only delivers a room's public
 * stream to subscriptions listed here, so a member who left (or was timed out) stops receiving
 * events even though their socket stays subscribed.
 */
@Component
class SubscriptionRegistry {

  /** {@code roomCode} is null for the errors queue. */
  record Subscription(UUID guestId, String roomCode, String destination) {}

  private final ConcurrentHashMap<String, Map<String, Subscription>> bySession =
      new ConcurrentHashMap<>();

  void authorize(String wsSessionId, String subscriptionId, Subscription subscription) {
    bySession
        .computeIfAbsent(wsSessionId, k -> new ConcurrentHashMap<>())
        .put(subscriptionId, subscription);
  }

  int count(String wsSessionId) {
    Map<String, Subscription> subs = bySession.get(wsSessionId);
    return subs == null ? 0 : subs.size();
  }

  boolean isAuthorized(String wsSessionId, String subscriptionId) {
    Map<String, Subscription> subs = bySession.get(wsSessionId);
    return subs != null && subs.containsKey(subscriptionId);
  }

  void remove(String wsSessionId, String subscriptionId) {
    Map<String, Subscription> subs = bySession.get(wsSessionId);
    if (subs != null) {
      subs.remove(subscriptionId);
    }
  }

  void removeSession(String wsSessionId) {
    bySession.remove(wsSessionId);
  }

  void revokeMember(UUID guestId, String roomCode) {
    bySession.values().forEach(subs -> subs.values().removeIf(s -> matches(s, guestId, roomCode)));
  }

  void revokeRoom(String roomCode) {
    bySession.values().forEach(subs -> subs.values().removeIf(s -> roomCode.equals(s.roomCode())));
  }

  int sessions() {
    return bySession.size();
  }

  private static boolean matches(Subscription s, UUID guestId, String roomCode) {
    return s.guestId().equals(guestId) && roomCode.equals(s.roomCode());
  }
}
