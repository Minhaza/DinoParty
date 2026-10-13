package com.dinoparty.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Size caps here only bound input; the nickname/avatar rules live in {@code GuestService}. */
record CreateGuestRequest(
    @NotNull @Size(max = 64) String nickname,
    @NotNull @Size(max = 40) String avatarKey,
    @Size(max = 16) String avatarColor) {}
