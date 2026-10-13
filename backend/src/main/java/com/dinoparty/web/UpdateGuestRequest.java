package com.dinoparty.web;

import jakarta.validation.constraints.Size;

/** Absent or null fields are left unchanged. */
record UpdateGuestRequest(
    @Size(max = 64) String nickname,
    @Size(max = 40) String avatarKey,
    @Size(max = 16) String avatarColor) {}
