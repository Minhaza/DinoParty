package com.dinoparty.common;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Fixed-window counter per key. Single-instance only (see docs/architecture.md §1). */
@Component
class InMemoryRateLimiter implements RateLimiter {

  private static final int PURGE_THRESHOLD = 10_000;

  private record Window(Instant end, int count) {}

  private final Clock clock;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  InMemoryRateLimiter(Clock clock) {
    this.clock = clock;
  }

  @Override
  public void check(String key, int limit, int windowSeconds) {
    Instant now = clock.instant();
    if (windows.size() > PURGE_THRESHOLD) {
      windows.values().removeIf(w -> !w.end().isAfter(now));
    }
    Window updated =
        windows.compute(
            key,
            (k, current) ->
                current == null || !current.end().isAfter(now)
                    ? new Window(now.plusSeconds(windowSeconds), 1)
                    : new Window(current.end(), current.count() + 1));
    if (updated.count() > limit) {
      long retryAfter = Math.max(1, updated.end().getEpochSecond() - now.getEpochSecond());
      throw new RateLimitedException(retryAfter);
    }
  }
}
