package com.dinoparty.engine;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A completed round, persisted at round end. The meta map is game specific and may hold values that
 * only became public at round end; it is written once the round is over.
 */
public record RoundRecord(
    int roundNo,
    Instant startedAt,
    Instant endedAt,
    String endReason,
    Map<UUID, Integer> scoreDeltas,
    Map<String, Object> meta) {}
