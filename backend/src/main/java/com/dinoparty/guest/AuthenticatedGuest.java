package com.dinoparty.guest;

import java.util.UUID;

/** Principal established by the token filter; the only trusted source of "who is calling". */
public record AuthenticatedGuest(UUID guestId, UUID sessionId) {}
