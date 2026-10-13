package com.dinoparty.room;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Members are ordered by join time; the host is identified by {@code hostGuestId}. */
public record RoomDetails(
    UUID id,
    String code,
    RoomStatus status,
    String gameKey,
    UUID hostGuestId,
    int minPlayers,
    int maxPlayers,
    Map<String, Object> settings,
    List<RoomMember> members,
    Instant createdAt)
    implements RoomView {}
