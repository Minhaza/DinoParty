package com.dinoparty.history;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Write-behind settings of the history recorder ({@code app.engine.recorder.*}). */
@ConfigurationProperties("app.engine.recorder")
public record HistoryProperties(
    Integer queueCapacity, Duration retryInitial, Duration retryMax, Integer maxAttempts) {

  public HistoryProperties {
    queueCapacity = queueCapacity == null ? 10_000 : queueCapacity;
    retryInitial = retryInitial == null ? Duration.ofSeconds(1) : retryInitial;
    retryMax = retryMax == null ? Duration.ofSeconds(30) : retryMax;
    maxAttempts = maxAttempts == null ? 10 : maxAttempts;
  }
}
