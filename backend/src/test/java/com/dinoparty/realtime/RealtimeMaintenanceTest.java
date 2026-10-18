package com.dinoparty.realtime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dinoparty.guest.GuestService;
import com.dinoparty.room.OrphanRoomCleanup;
import com.dinoparty.session.SessionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Pacing of the idle-room sweep inside the maintenance pass, with a manual clock. */
class RealtimeMaintenanceTest {

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

  private static final Duration INTERVAL = Duration.ofMinutes(5);

  private final ManualClock clock = new ManualClock();
  private final OrphanRoomCleanup orphans = mock(OrphanRoomCleanup.class);
  private RealtimeMaintenance maintenance;

  @BeforeEach
  void setUp() {
    PresenceTracker presence = mock(PresenceTracker.class);
    when(presence.liveConnections()).thenReturn(List.of());
    RealtimeProperties properties =
        new RealtimeProperties(
            null, null, null, null, null, null, null, null, null, null, INTERVAL);
    maintenance =
        new RealtimeMaintenance(
            presence,
            mock(ActionDeduper.class),
            mock(GuestService.class),
            mock(SessionService.class),
            orphans,
            mock(WebSocketSessionRegistry.class),
            mock(ThreadPoolTaskScheduler.class),
            properties,
            clock);
  }

  /**
   * Right after a (re)start nobody has reconnected yet and restart recovery has just put every room
   * back to WAITING; sweeping then would close long-running rooms whose players are on their way
   * back.
   */
  @Test
  void theFirstSweepWaitsOneIntervalAfterStartupSoClientsCanReconnect() {
    maintenance.runOnce();
    clock.advance(INTERVAL.minusSeconds(1));
    maintenance.runOnce();

    verify(orphans, never()).closeIdleRooms();

    clock.advance(Duration.ofSeconds(1));
    maintenance.runOnce();

    verify(orphans, times(1)).closeIdleRooms();
  }

  @Test
  void laterSweepsRunOncePerInterval() {
    clock.advance(INTERVAL);
    maintenance.runOnce();
    clock.advance(Duration.ofMinutes(1));
    maintenance.runOnce();
    clock.advance(INTERVAL);
    maintenance.runOnce();

    verify(orphans, times(2)).closeIdleRooms();
  }
}
