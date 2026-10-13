package com.dinoparty.common;

/** Port for attempt throttling; the in-memory adapter can be swapped without touching callers. */
public interface RateLimiter {

  /**
   * Records an attempt for {@code key} and throws {@link RateLimitedException} when more than
   * {@code limit} attempts happened in the current {@code windowSeconds} window.
   */
  void check(String key, int limit, int windowSeconds);
}
