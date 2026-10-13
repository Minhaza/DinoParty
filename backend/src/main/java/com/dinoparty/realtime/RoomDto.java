package com.dinoparty.realtime;

import com.dinoparty.common.Timestamps;
import com.dinoparty.room.MemberPresence;
import com.dinoparty.room.RoomDetails;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire shape of a room for members, shared by the REST API and STOMP snapshots. */
public record RoomDto(
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

  public static RoomDto from(RoomDetails room, String publicUrl, MemberPresence presence) {
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
        room.members().stream()
            .map(
                m ->
                    PlayerDto.from(
                        m, room.hostGuestId(), presence.isDisconnected(room.code(), m.guestId())))
            .toList(),
        null,
        Timestamps.format(room.createdAt()));
  }
}
