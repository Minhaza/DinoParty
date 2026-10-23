package com.dinoparty.games.impostor;

import com.dinoparty.common.ErrorCode;
import com.dinoparty.engine.ActionRejected;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * At most {@code limit} accepted commands per player in any sliding {@code window}. The transport
 * limiter is a one-second fixed window and cannot express "5 per 10 s", so the module counts on its
 * own clock. {@link #check} only reads; {@link #record} is called once the command is accepted, so
 * a rejection changes nothing.
 */
final class RateWindow {

  private final int limit;
  private final Duration window;
  private final Map<UUID, Deque<Instant>> accepted = new HashMap<>();

  RateWindow(int limit, Duration window) {
    this.limit = limit;
    this.window = window;
  }

  void check(UUID guest, Instant now) {
    Deque<Instant> times = accepted.get(guest);
    if (times == null) {
      return;
    }
    Instant from = now.minus(window);
    long recent = times.stream().filter(t -> t.isAfter(from)).count();
    if (recent >= limit) {
      throw new ActionRejected(ErrorCode.RATE_LIMITED, "Slow down a little.");
    }
  }

  void record(UUID guest, Instant now) {
    Deque<Instant> times = accepted.computeIfAbsent(guest, g -> new ArrayDeque<>());
    Instant from = now.minus(window);
    while (!times.isEmpty() && !times.peekFirst().isAfter(from)) {
      times.removeFirst();
    }
    times.addLast(now);
  }
}
