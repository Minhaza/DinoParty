package com.dinoparty.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Origin allowlist from {@code CORS_ALLOWED_ORIGINS} (comma-separated); never a wildcard. */
@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {

  public CorsProperties {
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
  }
}
