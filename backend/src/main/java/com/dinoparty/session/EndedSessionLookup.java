package com.dinoparty.session;

import java.util.Optional;
import java.util.UUID;

/**
 * Port to the stored history: the newest ended session of a room. Read once per room after a
 * restart, when the in-memory record of its last game is gone (game-engine.md §9).
 */
public interface EndedSessionLookup {

  Optional<LastSession> latestEnded(UUID roomId);
}
