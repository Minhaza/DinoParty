package com.dinoparty.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Single injectable time source; domain code must never call {@code Instant.now()} directly. */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
