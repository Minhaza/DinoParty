package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActionDeduperTest {

  /** Settable clock for deterministic windows. */
  private static final class ManualClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final ManualClock clock = new ManualClock();
  private final ActionDeduper deduper = new ActionDeduper(clock);
  private final UUID guest = UUID.randomUUID();

  @Test
  void repeatedActionIdIsRejected() {
    assertThat(deduper.firstSeen("ABC234", guest, "a1")).isTrue();
    assertThat(deduper.firstSeen("ABC234", guest, "a1")).isFalse();
    assertThat(deduper.firstSeen("ABC234", guest, "a2")).isTrue();
  }

  @Test
  void windowsAreSeparatePerGuestAndRoom() {
    UUID other = UUID.randomUUID();
    assertThat(deduper.firstSeen("ABC234", guest, "a1")).isTrue();
    assertThat(deduper.firstSeen("ABC234", other, "a1")).isTrue();
    assertThat(deduper.firstSeen("XYZ789", guest, "a1")).isTrue();
  }

  @Test
  void idIsForgottenAfterTheWindowAges() {
    deduper.firstSeen("ABC234", guest, "a1");
    clock.advance(ActionDeduper.MAX_AGE.plusSeconds(1));
    assertThat(deduper.firstSeen("ABC234", guest, "a1")).isTrue();
  }

  @Test
  void windowIsBoundedByCount() {
    for (int i = 0; i < ActionDeduper.MAX_PER_GUEST + 10; i++) {
      deduper.firstSeen("ABC234", guest, "id-" + i);
    }
    // The oldest ids fell out of the bounded window; recent ones are still remembered.
    assertThat(deduper.firstSeen("ABC234", guest, "id-0")).isTrue();
    assertThat(deduper.firstSeen("ABC234", guest, "id-" + (ActionDeduper.MAX_PER_GUEST + 9)))
        .isFalse();
  }

  @Test
  void forgettingAMemberOrRoomFreesTheirWindow() {
    deduper.firstSeen("ABC234", guest, "a1");
    deduper.forgetMember("ABC234", guest);
    assertThat(deduper.trackedWindows()).isZero();

    deduper.firstSeen("ABC234", guest, "a1");
    deduper.firstSeen("ABC234", UUID.randomUUID(), "a1");
    deduper.forgetRoom("ABC234");
    assertThat(deduper.trackedWindows()).isZero();
  }

  @Test
  void purgeDropsOnlyExpiredWindows() {
    deduper.firstSeen("ABC234", guest, "a1");
    clock.advance(ActionDeduper.MAX_AGE.plusSeconds(1));
    deduper.firstSeen("XYZ789", guest, "fresh");
    deduper.purgeExpired();
    assertThat(deduper.trackedWindows()).isEqualTo(1);
  }
}
