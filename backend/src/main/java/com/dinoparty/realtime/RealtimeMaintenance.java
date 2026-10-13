package com.dinoparty.realtime;

import com.dinoparty.guest.GuestService;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

/**
 * Periodic housekeeping: expire disconnected players whose grace period ended, close connections
 * whose guest session expired or was revoked, and drop stale duplicate-detection windows.
 */
@Component
class RealtimeMaintenance implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(RealtimeMaintenance.class);

  private final PresenceTracker presence;
  private final ActionDeduper deduper;
  private final GuestService guests;
  private final WebSocketSessionRegistry sessions;
  private final ThreadPoolTaskScheduler scheduler;
  private final RealtimeProperties properties;
  private final Clock clock;
  private ScheduledFuture<?> task;
  private Instant lastSessionCheck;

  RealtimeMaintenance(
      PresenceTracker presence,
      ActionDeduper deduper,
      GuestService guests,
      WebSocketSessionRegistry sessions,
      @Qualifier("realtimeScheduler") ThreadPoolTaskScheduler scheduler,
      RealtimeProperties properties,
      Clock clock) {
    this.presence = presence;
    this.deduper = deduper;
    this.guests = guests;
    this.sessions = sessions;
    this.scheduler = scheduler;
    this.properties = properties;
    this.clock = clock;
  }

  /** One maintenance pass; public to the package so tests can drive it with a manual clock. */
  void runOnce() {
    try {
      presence.sweep();
      deduper.purgeExpired();
      dropStaleSessions();
    } catch (RuntimeException e) {
      log.warn("Realtime maintenance failed", e);
    }
  }

  private void dropStaleSessions() {
    Instant now = clock.instant();
    if (lastSessionCheck != null
        && lastSessionCheck.plus(properties.sessionRecheckInterval()).isAfter(now)) {
      return;
    }
    lastSessionCheck = now;
    for (ConnectionDirectory.Connection connection : presence.liveConnections()) {
      if (!guests.isSessionActive(connection.guestSessionId())) {
        log.info(
            "Closing WebSocket session {}: guest session expired or revoked",
            connection.wsSessionId());
        sessions.close(
            connection.wsSessionId(), CloseStatus.POLICY_VIOLATION.withReason("SESSION_EXPIRED"));
      }
    }
  }

  @Override
  public synchronized void start() {
    if (task == null && properties.maintenanceEnabled()) {
      task = scheduler.scheduleWithFixedDelay(this::runOnce, properties.sweepInterval());
    }
  }

  @Override
  public synchronized void stop() {
    if (task != null) {
      task.cancel(false);
      task = null;
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return task != null;
  }
}
