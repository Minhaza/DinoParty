package com.dinoparty.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Engine tuning ({@code app.engine.*}); tests shorten the timers. */
@ConfigurationProperties("app.engine")
public record SessionProperties(
    Duration startCountdown,
    Duration resultsTimeout,
    Duration watchdogTolerance,
    Boolean strictRegistry,
    Boolean recoveryOnStartup) {

  public SessionProperties {
    startCountdown = startCountdown == null ? Duration.ofSeconds(3) : startCountdown;
    resultsTimeout = resultsTimeout == null ? Duration.ofMinutes(15) : resultsTimeout;
    watchdogTolerance = watchdogTolerance == null ? Duration.ofSeconds(1) : watchdogTolerance;
    strictRegistry = strictRegistry != null && strictRegistry;
    recoveryOnStartup = recoveryOnStartup == null || recoveryOnStartup;
  }
}
