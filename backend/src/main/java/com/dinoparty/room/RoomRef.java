package com.dinoparty.room;

import java.util.UUID;

/** Identity of a room: what realtime code needs to address events without loading details. */
public record RoomRef(UUID id, String code) {}
