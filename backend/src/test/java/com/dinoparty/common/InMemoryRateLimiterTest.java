package com.dinoparty.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTest {

  /** Manually advanced clock so the test never sleeps. */
  private static final class ManualClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final ManualClock clock = new ManualClock();
  private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);

  @Test
  void allowsUpToTheLimitThenRejectsWithRetryAfter() {
    for (int i = 0; i < 3; i++) {
      limiter.check("k", 3, 60);
    }

    clock.advance(Duration.ofSeconds(20));
    assertThatThrownBy(() -> limiter.check("k", 3, 60))
        .isInstanceOfSatisfying(
            RateLimitedException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(40));
  }

  @Test
  void windowResetsAndKeysAreIndependent() {
    for (int i = 0; i < 3; i++) {
      limiter.check("a", 3, 60);
    }

    assertThatCode(() -> limiter.check("b", 3, 60)).doesNotThrowAnyException();
    clock.advance(Duration.ofSeconds(61));
    assertThatCode(() -> limiter.check("a", 3, 60)).doesNotThrowAnyException();
  }
}
