package com.dinoparty.engine;

import java.time.Instant;

/** Asking for an id that already exists replaces it (reschedule). */
public record DeadlineRequest(String id, Instant at) {}
