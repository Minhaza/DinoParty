package com.dinoparty.games.telephone;

import com.dinoparty.common.RandomSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Auto prompts of [TP-3]: drawn without replacement from the frozen pool; when every word was used
 * the whole pool is used again (exhaustion falls back to repeats).
 */
final class PromptPool {

  private PromptPool() {}

  static String draw(List<String> pool, Set<String> used, RandomSource random) {
    List<String> candidates = new ArrayList<>(pool.size());
    for (String word : pool) {
      if (!used.contains(word)) {
        candidates.add(word);
      }
    }
    if (candidates.isEmpty()) {
      used.clear();
      candidates = pool;
    }
    String word = candidates.get(random.nextInt(candidates.size()));
    used.add(word);
    return word;
  }
}
