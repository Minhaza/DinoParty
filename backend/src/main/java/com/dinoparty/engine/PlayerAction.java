package com.dinoparty.engine;

import java.util.Map;
import java.util.UUID;

/** The guest id is the authenticated principal, never a value from the payload. */
public record PlayerAction(UUID guestId, String type, Map<String, Object> payload) {

  public PlayerAction {
    payload = payload == null ? Map.of() : payload;
  }
}
