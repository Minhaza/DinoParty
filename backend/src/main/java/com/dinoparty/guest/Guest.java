package com.dinoparty.guest;

import java.time.Instant;
import java.util.UUID;

/** Public view of a guest identity; JPA entities never leave the persistence package. */
public record Guest(
    UUID id, String nickname, String avatarKey, String avatarColor, Instant createdAt) {}
