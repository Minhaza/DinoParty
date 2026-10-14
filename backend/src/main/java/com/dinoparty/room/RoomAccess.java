package com.dinoparty.room;

import java.util.UUID;

/**
 * What a guest may do in a room, for modules that scope their own data to a room (word packs). A
 * host is always an active member.
 */
public record RoomAccess(
    UUID roomId, String code, RoomStatus status, boolean member, boolean host) {}
