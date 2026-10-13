package com.dinoparty.web;

import com.dinoparty.guest.Guest;
import java.util.UUID;

record GuestDto(UUID id, String nickname, String avatarKey, String avatarColor, String createdAt) {

  static GuestDto from(Guest guest) {
    return new GuestDto(
        guest.id(),
        guest.nickname(),
        guest.avatarKey(),
        guest.avatarColor(),
        Timestamps.format(guest.createdAt()));
  }
}
