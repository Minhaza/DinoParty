package com.dinoparty.room;

import java.util.Map;

public record GameInfo(
    String key,
    String name,
    int minPlayers,
    int maxPlayers,
    boolean enabled,
    Map<String, Object> defaultSettings) {}
