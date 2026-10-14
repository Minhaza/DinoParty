package com.dinoparty.engine;

import java.util.Map;
import java.util.UUID;

/** Per-player standing detail that the engine merges with the ledger totals and ranking. */
public record GameOutcome(Map<UUID, Map<String, Object>> detail) {

  public GameOutcome {
    detail = detail == null ? Map.of() : Map.copyOf(detail);
  }

  public static GameOutcome empty() {
    return new GameOutcome(Map.of());
  }
}
