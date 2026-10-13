package com.dinoparty.common;

/** Thrown when a {@link RateLimiter} rejects an attempt; answered as 429 with Retry-After. */
public class RateLimitedException extends DomainException {

  private final long retryAfterSeconds;

  public RateLimitedException(long retryAfterSeconds) {
    super(ErrorCode.RATE_LIMITED, "Too many attempts. Try again shortly.");
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
