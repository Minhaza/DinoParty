package com.dinoparty.guest;

import java.time.Instant;

/** Result of creating a guest. {@code token} is the raw secret and is only ever returned once. */
public record GuestRegistration(Guest guest, String token, Instant expiresAt) {

  @Override
  public String toString() {
    return "GuestRegistration[guest=" + guest + ", expiresAt=" + expiresAt + "]";
  }
}
