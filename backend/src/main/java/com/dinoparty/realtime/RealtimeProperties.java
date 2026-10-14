package com.dinoparty.realtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the STOMP layer ({@code app.realtime.*}). Defaults match docs/api-contracts.md §3;
 * tests shorten the timers.
 */
@ConfigurationProperties("app.realtime")
public record RealtimeProperties(
    Duration disconnectGrace,
    Duration inGameDisconnectGrace,
    Duration sweepInterval,
    Duration sessionRecheckInterval,
    Integer maxMessageBytes,
    Integer maxSubscriptionsPerSession,
    Integer commandLimitPer10s,
    Integer connectLimitPerMinute,
    Integer authFailureLimitPerMinute,
    Boolean maintenanceEnabled) {

  public RealtimeProperties {
    disconnectGrace = disconnectGrace == null ? Duration.ofSeconds(30) : disconnectGrace;
    inGameDisconnectGrace =
        inGameDisconnectGrace == null ? Duration.ofSeconds(60) : inGameDisconnectGrace;
    sweepInterval = sweepInterval == null ? Duration.ofSeconds(1) : sweepInterval;
    sessionRecheckInterval =
        sessionRecheckInterval == null ? Duration.ofSeconds(30) : sessionRecheckInterval;
    maxMessageBytes = maxMessageBytes == null ? 65_536 : maxMessageBytes;
    maxSubscriptionsPerSession =
        maxSubscriptionsPerSession == null ? 8 : maxSubscriptionsPerSession;
    commandLimitPer10s = commandLimitPer10s == null ? 60 : commandLimitPer10s;
    connectLimitPerMinute = connectLimitPerMinute == null ? 60 : connectLimitPerMinute;
    authFailureLimitPerMinute = authFailureLimitPerMinute == null ? 20 : authFailureLimitPerMinute;
    maintenanceEnabled = maintenanceEnabled == null || maintenanceEnabled;
  }
}
