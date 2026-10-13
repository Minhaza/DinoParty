package com.dinoparty.engine;

import java.util.UUID;

public record ScoreDelta(UUID guestId, int points, String reason) {}
