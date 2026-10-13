package com.dinoparty.web;

import com.dinoparty.guest.GuestRegistration;

record CreateGuestResponse(GuestDto guest, String token, String expiresAt) {

  static CreateGuestResponse from(GuestRegistration registration) {
    return new CreateGuestResponse(
        GuestDto.from(registration.guest()),
        registration.token(),
        Timestamps.format(registration.expiresAt()));
  }
}
