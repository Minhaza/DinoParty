package com.dinoparty.realtime;

import com.dinoparty.guest.AuthenticatedGuest;
import java.security.Principal;
import java.util.UUID;

/**
 * STOMP principal of one connection. The name is unique per connection (see {@link
 * ConnectionDirectory}); the guest id is what authorization uses.
 */
record GuestPrincipal(AuthenticatedGuest guest, String connectionId) implements Principal {

  @Override
  public String getName() {
    return ConnectionDirectory.principalName(guest.guestId(), connectionId);
  }

  UUID guestId() {
    return guest.guestId();
  }
}
