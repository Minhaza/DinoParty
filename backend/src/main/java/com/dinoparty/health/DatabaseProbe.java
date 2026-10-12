package com.dinoparty.health;

import java.sql.Connection;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class DatabaseProbe {

  private static final Logger log = LoggerFactory.getLogger(DatabaseProbe.class);
  private static final int VALIDATION_TIMEOUT_SECONDS = 2;

  private final DataSource dataSource;

  DatabaseProbe(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  boolean isUp() {
    try (Connection connection = dataSource.getConnection()) {
      return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
    } catch (Exception e) {
      // Message only: the stack trace adds nothing for an expected "database down" condition.
      log.warn("Database health probe failed: {}", e.getMessage());
      return false;
    }
  }
}
