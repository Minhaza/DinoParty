package com.dinoparty.health;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class HealthService {

  private final DatabaseProbe databaseProbe;
  private final Clock clock;
  private final String version;

  HealthService(DatabaseProbe databaseProbe, Clock clock, @Value("${app.version}") String version) {
    this.databaseProbe = databaseProbe;
    this.clock = clock;
    this.version = version;
  }

  HealthResponse check() {
    String db = databaseProbe.isUp() ? HealthResponse.UP : HealthResponse.DOWN;
    return new HealthResponse(db, version, db, clock.instant());
  }
}
