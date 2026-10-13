package com.dinoparty.realtime;

import com.dinoparty.room.RoomMember;
import java.util.UUID;

/** Wire shape of a room member, shared by REST ({@code RoomDto}) and STOMP events. */
public record PlayerDto(
    UUID guestId,
    String nickname,
    String avatarKey,
    String avatarColor,
    boolean ready,
    String connection,
    boolean isHost,
    Integer score) {

  public static PlayerDto from(RoomMember member, UUID hostGuestId, boolean disconnected) {
    return from(member, hostGuestId, disconnected, null);
  }

  public static PlayerDto from(
      RoomMember member, UUID hostGuestId, boolean disconnected, Integer score) {
    return new PlayerDto(
        member.guestId(),
        member.nickname(),
        member.avatarKey(),
        member.avatarColor(),
        member.ready(),
        disconnected ? "DISCONNECTED" : "CONNECTED",
        member.guestId().equals(hostGuestId),
        score);
  }
}
