package com.dinoparty.session;

import java.util.UUID;

/** Published by a scheduler when an armed instant is reached. */
public record DeadlineWake(String roomCode, UUID sessionInstance) {}
