package com.dinoparty.room;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code APP_PUBLIC_URL} is the frontend origin used to build invite links; {@code
 * app.room-orphan-ttl} is how long a waiting room may sit without any sign of life before the
 * maintenance sweep closes it.
 */
@ConfigurationProperties("app")
public record RoomProperties(String publicUrl, Integer joinLimitPerMinute, Duration roomOrphanTtl) {

  public RoomProperties {
    publicUrl = publicUrl == null || publicUrl.isBlank() ? "http://localhost:5173" : publicUrl;
    joinLimitPerMinute = joinLimitPerMinute == null ? 30 : joinLimitPerMinute;
    roomOrphanTtl = roomOrphanTtl == null ? Duration.ofHours(1) : roomOrphanTtl;
  }
}
