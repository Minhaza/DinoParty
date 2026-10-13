package com.dinoparty.realtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.Principal;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Connection lifecycle: records authenticated connections in {@link PresenceTracker}, replaces a
 * guest's older connection, and logs/measures it. Logs carry the guest id and the (non-secret)
 * WebSocket session id only; credentials are never part of any event seen here.
 */
@Component
class ConnectionLifecycle {

  private static final Logger log = LoggerFactory.getLogger(ConnectionLifecycle.class);
  private static final Duration CLOSE_DELAY = Duration.ofMillis(500);

  private final PresenceTracker presence;
  private final SubscriptionRegistry subscriptions;
  private final WebSocketSessionRegistry sessions;
  private final ThreadPoolTaskScheduler scheduler;
  private final MeterRegistry meters;

  ConnectionLifecycle(
      PresenceTracker presence,
      SubscriptionRegistry subscriptions,
      WebSocketSessionRegistry sessions,
      @org.springframework.beans.factory.annotation.Qualifier("realtimeScheduler")
          ThreadPoolTaskScheduler scheduler,
      MeterRegistry meters) {
    this.presence = presence;
    this.subscriptions = subscriptions;
    this.sessions = sessions;
    this.scheduler = scheduler;
    this.meters = meters;
    Gauge.builder("dinoparty.ws.connections.active", presence, PresenceTracker::liveCount)
        .description("Authenticated STOMP connections currently open")
        .register(meters);
    Gauge.builder("dinoparty.ws.players.disconnected", presence, PresenceTracker::disconnectedCount)
        .description("Players inside their disconnect grace period")
        .register(meters);
  }

  @EventListener
  void onConnected(SessionConnectedEvent event) {
    if (!(event.getUser() instanceof GuestPrincipal user)) {
      return;
    }
    String wsSessionId = sessionId(event.getMessage());
    if (wsSessionId == null) {
      return;
    }
    try (MDC.MDCCloseable g = MDC.putCloseable("guestId", user.guestId().toString())) {
      log.info("STOMP connected (ws session {})", wsSessionId);
      presence
          .connected(
              new ConnectionDirectory.Connection(
                  user.guestId(), user.guest().sessionId(), wsSessionId))
          .ifPresent(this::replace);
    }
  }

  @EventListener
  void onDisconnected(SessionDisconnectEvent event) {
    String wsSessionId = event.getSessionId();
    subscriptions.removeSession(wsSessionId);
    Principal principal = event.getUser();
    if (principal instanceof GuestPrincipal user) {
      try (MDC.MDCCloseable g = MDC.putCloseable("guestId", user.guestId().toString())) {
        log.info(
            "STOMP disconnected (ws session {}, close {})", wsSessionId, event.getCloseStatus());
        presence.disconnected(user.guestId(), wsSessionId);
      }
      meters.counter("dinoparty.ws.disconnects").increment();
    }
  }

  /** The newest connection wins; the old one was told (SessionReplacement), now it is closed. */
  private void replace(ConnectionDirectory.Connection old) {
    log.info("Closing replaced STOMP connection (ws session {})", old.wsSessionId());
    scheduler.schedule(
        () ->
            sessions.close(
                old.wsSessionId(), CloseStatus.POLICY_VIOLATION.withReason("SESSION_REPLACED")),
        scheduler.getClock().instant().plus(CLOSE_DELAY));
  }

  private static String sessionId(org.springframework.messaging.Message<?> message) {
    return org.springframework.messaging.simp.SimpMessageHeaderAccessor.getSessionId(
        message.getHeaders());
  }
}
