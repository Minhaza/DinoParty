package com.dinoparty.words;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Custom word packs for the word pool. The {@code wordpacks} module implements it on top of its
 * room-scoped pack storage (WP-03). Selection, filtering, de-duplication and drawing stay in {@link
 * WordPoolService}, so a provider only returns entries.
 */
public interface CustomWordProvider {

  /** One entry of a pack. Category and difficulty are optional metadata (may be null). */
  record CustomWord(String text, String categoryKey, WordDifficulty difficulty) {}

  /**
   * The current entries of every requested pack that {@code requester} may use. A pack that does
   * not exist or that the requester may not use is absent from the map (never an exception). The
   * returned lists are read once at game start; later edits must not be visible through them.
   */
  Map<UUID, List<CustomWord>> packs(UUID requester, List<UUID> packIds);
}
