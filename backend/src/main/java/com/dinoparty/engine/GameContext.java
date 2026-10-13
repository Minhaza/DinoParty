package com.dinoparty.engine;

import com.dinoparty.common.RandomSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a module may use during a call. It offers no thread, scheduler, repository or
 * publisher: a module computes a Transition and the engine does the rest.
 */
public record GameContext(
    UUID sessionId,
    Clock clock,
    RandomSource random,
    List<PlayerInfo> roster,
    List<UUID> order,
    Map<String, Object> settings) {}
