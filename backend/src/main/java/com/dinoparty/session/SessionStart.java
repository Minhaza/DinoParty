package com.dinoparty.session;

import com.dinoparty.engine.PlayerInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The seed is stored for reproducing a game in tests and support; no DTO ever exposes it. */
public record SessionStart(
    UUID sessionId,
    UUID roomId,
    String gameKey,
    Map<String, Object> settings,
    List<PlayerInfo> roster,
    long seed,
    Instant startedAt) {}
