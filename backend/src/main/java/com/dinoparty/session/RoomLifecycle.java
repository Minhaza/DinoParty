package com.dinoparty.session;

import com.dinoparty.room.RoomStatus;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Port to the room module. The session module changes room status only through it, and always
 * inside {@link #inRoom}; tests replace it with an in-memory fake.
 */
public interface RoomLifecycle {

  /** Runs the action in the room mailbox (reentrant for the thread that already holds it). */
  <T> T inRoom(String roomCode, Supplier<T> action);

  RoomState state(String roomCode);

  void changeStatus(String roomCode, RoomStatus to, String reason, Instant startsAt);

  /** Startup recovery; returns the number of rooms that were reset to waiting. */
  int resetAfterRestart();
}
