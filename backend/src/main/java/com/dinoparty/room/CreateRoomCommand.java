package com.dinoparty.room;

import java.util.Map;

/** {@code settings} and {@code maxPlayers} are optional; defaults come from the game definition. */
public record CreateRoomCommand(String gameKey, Map<String, Object> settings, Integer maxPlayers) {}
