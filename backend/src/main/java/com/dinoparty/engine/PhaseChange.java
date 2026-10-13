package com.dinoparty.engine;

import java.time.Instant;
import java.util.Map;

/**
 * Starts a new phase. The deadline (may be null) is advertised to clients and also becomes the
 * phase-end deadline; data is public and merged into PHASE_STARTED.
 */
public record PhaseChange(String name, Instant deadlineAt, Map<String, Object> data) {

  public PhaseChange {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Phase name required");
    }
    data = data == null ? Map.of() : Map.copyOf(data);
  }
}
