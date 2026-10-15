package com.dinoparty.words;

import java.util.List;
import java.util.UUID;

/**
 * The effective word settings of one game (api-contracts §2.2, game-rules [DG-15]).
 *
 * @param categories built-in category keys; empty or {@link WordCatalogTypes#RANDOM_MIX} means
 *     every active category. Built-in words only.
 * @param difficulty built-in words only; {@code MIXED} means every stored difficulty
 * @param packIds custom word packs, in the host's order. Custom words only.
 */
public record WordSelection(
    WordSource source, List<String> categories, DifficultyFilter difficulty, List<UUID> packIds) {

  public WordSelection {
    categories = List.copyOf(categories);
    packIds = List.copyOf(packIds);
  }

  /** True when the built-in part uses every active category. */
  public boolean randomMix() {
    return categories.isEmpty() || categories.contains(WordCatalogTypes.RANDOM_MIX);
  }
}
