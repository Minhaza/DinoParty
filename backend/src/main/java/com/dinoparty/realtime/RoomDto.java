package com.dinoparty.web;

import com.dinoparty.room.RoomDetails;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record RoomDto(
    UUID id,
    String code,
    String inviteUrl,
    String status,
    String gameKey,
    UUID hostGuestId,
    int minPlayers,
    int maxPlayers,
    Map<String, Object> settings,
    List<PlayerDto> players,
    UUID sessionId,
    String createdAt) {

  static RoomDto from(RoomDetails room, String publicUrl) {
    String base =
        publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    return new RoomDto(
        room.id(),
        room.code(),
        base + "/join/" + room.code(),
        room.status().name(),
        room.gameKey(),
        room.hostGuestId(),
        room.minPlayers(),
        room.maxPlayers(),
        room.settings(),
        room.members().stream().map(m -> PlayerDto.from(m, room.hostGuestId())).toList(),
        null,
        Timestamps.format(room.createdAt()));
  }
}
