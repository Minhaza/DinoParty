package com.dinoparty.room;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Converts the room/game settings map to and from its JSONB column text. */
@Component
class SettingsCodec {

  private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
      new TypeReference<>() {};

  private final JsonMapper jsonMapper;

  SettingsCodec(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  String write(Map<String, Object> settings) {
    return jsonMapper.writeValueAsString(settings);
  }

  Map<String, Object> read(String json) {
    return jsonMapper.readValue(json, MAP_TYPE);
  }
}
