package com.dinoparty.words;

import com.dinoparty.common.RandomSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The frozen, de-duplicated word pool of one game session ([DG-15]): built once at game start by
 * {@link WordPoolService} and immutable afterwards, so later settings or pack edits never reach a
 * running game. The per-session "used" set lives in the game state. Holds secrets: never
 * serialized, and {@link #toString()} prints no word.
 */
public final class WordPool {

  private final List<String> words;

  WordPool(List<String> words) {
    this.words = List.copyOf(words);
  }

  /** Every word of the pool, in pool order (built-in words first, then packs in order). */
  public List<String> words() {
    return words;
  }

  public int size() {
    return words.size();
  }

  /**
   * Draws {@code count} distinct words uniformly without replacement from the pool minus the {@code
   * used} words. When fewer than {@code count} unused words remain, the whole pool is used again
   * (exhaustion falls back to repeats, [DG-15]).
   *
   * @throws IllegalStateException if the pool itself has fewer than {@code count} words (the start
   *     check of {@link WordPoolService} prevents that)
   */
  public List<String> draw(Set<String> used, RandomSource random, int count) {
    if (words.size() < count) {
      throw new IllegalStateException("The word pool is smaller than one draw");
    }
    List<String> candidates = new ArrayList<>(words.size());
    for (String w : words) {
      if (!used.contains(w)) {
        candidates.add(w);
      }
    }
    if (candidates.size() < count) {
      candidates = new ArrayList<>(words);
    }
    // Partial Fisher-Yates: the first `count` slots become a uniform sample.
    for (int i = 0; i < count; i++) {
      int j = i + random.nextInt(candidates.size() - i);
      String tmp = candidates.get(i);
      candidates.set(i, candidates.get(j));
      candidates.set(j, tmp);
    }
    return List.copyOf(candidates.subList(0, count));
  }

  @Override
  public String toString() {
    return "WordPool[size=" + words.size() + "]";
  }
}
