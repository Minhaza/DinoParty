package com.dinoparty.health;

import java.time.Instant;

/** Response of {@code GET /api/v1/health}; shape defined in docs/api-contracts.md §2. */
public record HealthResponse(String status, String version, String db, Instant time) {

  public static final String UP = "UP";
  public static final String DOWN = "DOWN";
}
