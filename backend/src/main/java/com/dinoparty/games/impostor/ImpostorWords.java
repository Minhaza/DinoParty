package com.dinoparty.games.impostor;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.RandomSource;
import com.dinoparty.words.GuessNormalizer;
import com.dinoparty.words.WordCatalogTypes.CategorizedWord;
import com.dinoparty.words.WordPoolService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The candidate words of one impostor session ([DI-2], D60): the usable built-in words of the set
 * difficulty with their categories, loaded once when the host starts. The pool is kept whole, so
 * the decoy draw of Sketchy Saurus ([SS-3], D63) can pick from the same category among the words
 * this session does not use. Holds secrets: {@link #toString()} prints no word.
 */
final class ImpostorWords {

  /** The words of a Sketchy Saurus session and, in the same order, the decoy of each. */
  record WithDecoys(List<CategorizedWord> words, List<CategorizedWord> decoys) {
    @Override
    public String toString() {
      return "WithDecoys[words=" + words.size() + "]";
    }
  }

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
      throw tooSmall(count);
    }
    List<CategorizedWord> candidates = new ArrayList<>(pool);
    // Partial Fisher-Yates: the first `count` slots become a uniform sample.
    for (int i = 0; i < count; i++) {
      swap(candidates, i, i + random.nextInt(candidates.size() - i));
    }
    return List.copyOf(candidates.subList(0, count));
  }

  /**
   * [SS-3] (D63): {@code count} words as {@link #draw}, each with a decoy: another word of the same
   * category (the pool is already of the set difficulty) that differs from it after {@link
   * GuessNormalizer} normalization and is used nowhere else in the session, neither as a word nor
   * as a decoy. A word whose category has no such partner left is skipped.
   *
   * @throws DomainException {@code WORD_POOL_TOO_SMALL} with {@code details: {required,
   *     available}}, {@code required} being the words plus the decoys, when the pool cannot supply
   *     them
   */
  WithDecoys drawWithDecoys(int count, RandomSource random) {
    List<CategorizedWord> candidates = new ArrayList<>(pool);
    for (int i = 0; i < candidates.size(); i++) {
      swap(candidates, i, i + random.nextInt(candidates.size() - i));
    }
    Set<String> used = new HashSet<>();
    List<CategorizedWord> words = new ArrayList<>();
    List<CategorizedWord> decoys = new ArrayList<>();
    for (CategorizedWord word : candidates) {
      if (words.size() == count) {
        break;
      }
      String key = GuessNormalizer.normalize(word.text());
      if (used.contains(key)) {
        continue;
      }
      List<CategorizedWord> partners = new ArrayList<>();
      for (CategorizedWord other : candidates) {
        String otherKey = GuessNormalizer.normalize(other.text());
        if (other.categoryKey().equals(word.categoryKey())
            && !otherKey.equals(key)
            && !used.contains(otherKey)) {
          partners.add(other);
        }
      }
      if (partners.isEmpty()) {
        continue;
      }
      CategorizedWord decoy = partners.get(random.nextInt(partners.size()));
      used.add(key);
      used.add(GuessNormalizer.normalize(decoy.text()));
      words.add(word);
      decoys.add(decoy);
    }
    if (words.size() < count) {
      throw tooSmall(2 * count);
    }
    return new WithDecoys(List.copyOf(words), List.copyOf(decoys));
  }

  private DomainException tooSmall(int required) {
    return new DomainException(
        ErrorCode.WORD_POOL_TOO_SMALL,
        "This difficulty gives "
            + pool.size()
            + " usable words, but this game needs "
            + required
            + ".",
        List.of(),
        Map.of("required", required, "available", pool.size()));
  }

  private static void swap(List<CategorizedWord> list, int i, int j) {
    CategorizedWord tmp = list.get(i);
    list.set(i, list.get(j));
    list.set(j, tmp);
  }

  @Override
  public String toString() {
    return "ImpostorWords[size=" + pool.size() + "]";
  }
}
