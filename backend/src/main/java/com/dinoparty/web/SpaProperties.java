package com.dinoparty.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Built frontend served by this process (single-origin deploy, decision D49). {@code location} is a
 * Spring resource URL ending in {@code /}.
 */
@ConfigurationProperties("app.spa")
record SpaProperties(boolean enabled, String location) {

  SpaProperties {
    location = location == null || location.isBlank() ? "classpath:/static/" : location;
  }

  /** Paths the SPA never answers, so they keep their API/actuator/STOMP behavior. */
  static boolean isBackendPath(String path) {
    return path.equals("/ws")
        || path.startsWith("/ws/")
        || path.startsWith("/api/")
        || path.equals("/api")
        || path.startsWith("/actuator");
  }
}
