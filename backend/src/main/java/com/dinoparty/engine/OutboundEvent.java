package com.dinoparty.engine;

import java.util.Map;
import java.util.UUID;

public record OutboundEvent(String type, Audience audience, Map<String, Object> payload) {

  public OutboundEvent {
    payload = payload == null ? Map.of() : payload;
  }

  public static OutboundEvent publicEvent(String type, Map<String, Object> payload) {
    return new OutboundEvent(type, Audience.PUBLIC, payload);
  }

  public static OutboundEvent privateEvent(
      UUID recipient, String type, Map<String, Object> payload) {
    return new OutboundEvent(type, Audience.to(recipient), payload);
  }
}
