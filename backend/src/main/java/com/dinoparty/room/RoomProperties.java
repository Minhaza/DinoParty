package com.dinoparty.room;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code APP_PUBLIC_URL} is the frontend origin used to build invite links. */
@ConfigurationProperties("app")
public record RoomProperties(String publicUrl, Integer joinLimitPerMinute) {

  public RoomProperties {
    publicUrl = publicUrl == null || publicUrl.isBlank() ? "http://localhost:5173" : publicUrl;
    joinLimitPerMinute = joinLimitPerMinute == null ? 30 : joinLimitPerMinute;
  }
}
