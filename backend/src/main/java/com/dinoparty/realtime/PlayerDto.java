package com.dinoparty.web;

import com.dinoparty.room.RoomMember;
import java.util.UUID;

/**
 * {@code ready}, {@code connection} and {@code score} are fixed placeholders until presence and
 * games exist (docs/api-contracts.md §2.2).
 */
record PlayerDto(
    UUID guestId,
    String nickname,
    String avatarKey,
    String avatarColor,
    boolean ready,
    String connection,
    boolean isHost,
    Integer score) {

  static PlayerDto from(RoomMember member, UUID hostGuestId) {
    return new PlayerDto(
        member.guestId(),
        member.nickname(),
        member.avatarKey(),
        member.avatarColor(),
        false,
        "CONNECTED",
        member.guestId().equals(hostGuestId),
        null);
  }
}
