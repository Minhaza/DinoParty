package com.dinoparty.engine;

import java.util.UUID;

/** Snapshot of a roster member taken when the session starts. */
public record PlayerInfo(UUID guestId, String nickname, String avatarKey, String avatarColor) {}
