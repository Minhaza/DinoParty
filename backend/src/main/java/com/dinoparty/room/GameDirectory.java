package com.dinoparty.room;

import java.util.Map;
import java.util.Optional;

/**
 * Port to the game registry, implemented outside the room module (which knows nothing of the
 * engine). The registered game modules are the only source of a game's name, player bounds and
 * settings schema; the room only adds the availability flag of its {@code game_definition} row.
 */
public interface GameDirectory {

  /** The registered game with this key, or empty when no module declares it. */
  Optional<GameInfo> find(String gameKey);

  /**
   * Validates host-supplied settings against the game's schema and returns them layered over the
   * game's defaults ({@code null} = all defaults). Throws {@code VALIDATION_FAILED} with field
   * errors named {@code settings.<key>}.
   */
  Map<String, Object> resolveSettings(String gameKey, Map<String, Object> supplied);
}
