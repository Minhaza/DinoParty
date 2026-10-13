package com.dinoparty.realtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Remembers recent {@code actionId}s per (guest, room) so a retried command is not applied twice.
 * The window is bounded by count and age.
 */
@Component
class ActionDeduper {

  static final int MAX_PER_GUEST = 256;
  static final Duration MAX_AGE = Duration.ofMinutes(5);

  private final Clock clock;
  private final ConcurrentHashMap<String, Map<String, Instant>> seen = new ConcurrentHashMap<>();

  ActionDeduper(Clock clock) {
    this.clock = clock;
  }

  /** True the first time an id is seen inside the window; false for a repeat. */
  boolean firstSeen(String roomCode, UUID guestId, String actionId) {
    Map<String, Instant> ids = seen.computeIfAbsent(key(roomCode, guestId), k -> newWindow());
    Instant now = clock.instant();
    synchronized (ids) {
      Instant previous = ids.get(actionId);
      if (previous != null && previous.plus(MAX_AGE).isAfter(now)) {
        return false;
      }
      ids.put(actionId, now);
      return true;
    }
  }

  void forgetMember(String roomCode, UUID guestId) {
    seen.remove(key(roomCode, guestId));
  }

  void forgetRoom(String roomCode) {
    seen.keySet().removeIf(k -> k.startsWith(roomCode + ":"));
  }

  /** Drops windows whose newest entry has aged out. */
  void purgeExpired() {
    Instant cutoff = clock.instant().minus(MAX_AGE);
    seen.values()
        .removeIf(
            ids -> {
              synchronized (ids) {
                return ids.values().stream().allMatch(t -> t.isBefore(cutoff));
              }
            });
  }

  int trackedWindows() {
    return seen.size();
  }

  private static String key(String roomCode, UUID guestId) {
    return roomCode + ":" + guestId;
  }

  private static Map<String, Instant> newWindow() {
    return new LinkedHashMap<>() {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
        return size() > MAX_PER_GUEST;
      }
    };
  }
}
