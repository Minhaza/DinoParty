package com.dinoparty.room;

import java.util.Map;

/** Null fields are left unchanged. */
public record UpdateRoomSettingsCommand(
    String gameKey, Map<String, Object> settings, Integer maxPlayers) {}
