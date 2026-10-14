package com.dinoparty.engine;

import com.dinoparty.common.RandomSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a module may use during a call. It offers no thread, scheduler, repository or
 * publisher: a module computes a Transition and the engine does the rest.
 *
 * @param nextPhaseId the id the engine gives the new phase if the transition returned by this call
 *     starts one; lets a module put the {@code phaseId} into the private events of that phase (e.g.
 *     {@code WORD_CHOICES}). Unused when the call does not change the phase.
 */
public record GameContext(
    UUID sessionId,
    Clock clock,
    RandomSource random,
    List<PlayerInfo> roster,
    List<UUID> order,
    Map<String, Object> settings,
    UUID nextPhaseId) {}
