package com.dinoparty.room;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Serializes every mutation of one room: commands for the same room code run one at a time, while
 * different rooms proceed in parallel (docs/architecture.md §3.2). Slots are reference counted and
 * removed when idle, so the map never grows with the number of rooms ever touched. The lock is
 * fair: commands (and deadline wake-ups) are served in arrival order, so none can starve behind a
 * flood.
 */
@Component
class RoomMailbox {

  private static final class Slot {
    final ReentrantLock lock = new ReentrantLock(true);
    int users;
  }

  private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

  <T> T run(String roomCode, Supplier<T> command) {
    Slot slot =
        slots.compute(
            roomCode,
            (code, existing) -> {
              Slot s = existing == null ? new Slot() : existing;
              s.users++;
              return s;
            });
    slot.lock.lock();
    try {
      return command.get();
    } finally {
      slot.lock.unlock();
      slots.compute(roomCode, (code, s) -> s != null && --s.users == 0 ? null : s);
    }
  }

  int activeSlots() {
    return slots.size();
  }
}
