package com.dinoparty.guest;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Guest session tuning ({@code TOKEN_TTL}, creation throttle). Defaults suit production; tests
 * override them.
 */
@ConfigurationProperties("app.guest")
public record GuestProperties(Duration tokenTtl, Integer createLimitPerMinute) {

  public GuestProperties {
    tokenTtl = tokenTtl == null ? Duration.ofDays(30) : tokenTtl;
    createLimitPerMinute = createLimitPerMinute == null ? 10 : createLimitPerMinute;
  }
}
