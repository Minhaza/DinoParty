package com.dinoparty.games.impostor;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.RandomSource;
import com.dinoparty.words.WordCatalogTypes.CategorizedWord;
import com.dinoparty.words.WordPoolService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The candidate words of one impostor session ([DI-2], D60): the usable built-in words of the set
 * difficulty with their categories, loaded once when the host starts. The pool is kept whole, so a
 * later decoy draw (Sketchy Saurus, [SS-3]) can pick from the same category among the words this
 * session does not use. Holds secrets: {@link #toString()} prints no word.
 */
final class ImpostorWords {

  private final List<CategorizedWord> pool;

  private ImpostorWords(List<CategorizedWord> pool) {
    this.pool = List.copyOf(pool);
  }

  static ImpostorWords load(WordPoolService words, ImpostorDifficulty difficulty) {
    return new ImpostorWords(words.categorizedBuiltIn(difficulty.filters()));
  }

  int size() {
    return pool.size();
  }

  /**
   * {@code count} distinct words, uniformly without replacement. {@code random} is the injected
   * source, not the session seed, so the stored seed cannot reveal the words.
   *
   * @throws DomainException {@code WORD_POOL_TOO_SMALL} with {@code details: {required, available}}
   *     when the pool has fewer than {@code count} words
   */
  List<CategorizedWord> draw(int count, RandomSource random) {
    if (pool.size() < count) {
      throw new DomainException(
          ErrorCode.WORD_POOL_TOO_SMALL,
          "This difficulty gives "
              + pool.size()
              + " usable words, but this game needs "
              + count
              + ".",
          List.of(),
          Map.of("required", count, "available", pool.size()));
    }
    List<CategorizedWord> candidates = new ArrayList<>(pool);
    // Partial Fisher-Yates: the first `count` slots become a uniform sample.
    for (int i = 0; i < count; i++) {
      int j = i + random.nextInt(candidates.size() - i);
      CategorizedWord tmp = candidates.get(i);
      candidates.set(i, candidates.get(j));
      candidates.set(j, tmp);
    }
    return List.copyOf(candidates.subList(0, count));
  }

  @Override
  public String toString() {
    return "ImpostorWords[size=" + pool.size() + "]";
  }
}
