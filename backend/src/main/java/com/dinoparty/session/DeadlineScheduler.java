package com.dinoparty.session;

import java.time.Instant;
import java.util.UUID;

/**
 * Wakes a room up once at a given instant. The wake-up carries no state: the session reads its own
 * deadline book, so late, duplicate or spurious wake-ups are harmless.
 */
public interface DeadlineScheduler {

  interface Handle {
    void cancel();
  }

  Handle arm(String roomCode, UUID sessionInstance, Instant at);
}
