package com.dinoparty.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Connects the Logback OpenTelemetry appender (see logback-spring.xml) to the SDK that Spring Boot
 * auto-configures. Whether anything is actually exported is controlled by {@code OTEL_ENABLED} in
 * application.yml; with export off the SDK has no log processor and records are discarded.
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

  @Bean
  InitializingBean installOpenTelemetryLogAppender(OpenTelemetry openTelemetry) {
    return () -> OpenTelemetryAppender.install(openTelemetry);
  }
}
