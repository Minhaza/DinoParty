package com.dinoparty.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class HealthServiceTest {

  private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
  private final DatabaseProbe probe = mock(DatabaseProbe.class);
  private final HealthService service =
      new HealthService(probe, Clock.fixed(NOW, ZoneOffset.UTC), "1.2.3");

  @Test
  void reportsUpWhenDatabaseIsUp() {
    when(probe.isUp()).thenReturn(true);

    assertThat(service.check()).isEqualTo(new HealthResponse("UP", "1.2.3", "UP", NOW));
  }

  @Test
  void reportsDownWhenDatabaseIsDown() {
    when(probe.isUp()).thenReturn(false);

    assertThat(service.check()).isEqualTo(new HealthResponse("DOWN", "1.2.3", "DOWN", NOW));
  }
}
