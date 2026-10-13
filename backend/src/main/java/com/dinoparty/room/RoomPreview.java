package com.dinoparty.room;

public record RoomPreview(
    String code,
    String gameKey,
    RoomStatus status,
    int playerCount,
    int maxPlayers,
    String hostNickname)
    implements RoomView {}
