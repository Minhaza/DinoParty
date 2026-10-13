package com.dinoparty.room;

import java.time.Instant;
import java.util.UUID;

public record RoomMember(
    UUID guestId, String nickname, String avatarKey, String avatarColor, Instant joinedAt) {}
