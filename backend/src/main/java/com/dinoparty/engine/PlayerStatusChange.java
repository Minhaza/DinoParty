package com.dinoparty.engine;

import java.util.UUID;

public record PlayerStatusChange(UUID guestId, PlayerStatus status) {}
