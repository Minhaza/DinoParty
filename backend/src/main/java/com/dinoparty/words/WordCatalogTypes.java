package com.dinoparty.words;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read models of the word catalog. None of them is ever tied to a game in progress. */
public final class WordCatalogTypes {

  private WordCatalogTypes() {}

  /** Key of the "all categories" selection preset; never a stored category. */
  public static final String RANDOM_MIX = "random-mix";

  public static final String RANDOM_MIX_LABEL = "Random Mix";

  /** An active category with its label in the resolved locale. */
  public record WordCategory(String key, String label) {}

  /** Active categories of a catalog locale, in display order. */
  public record Categories(String locale, List<WordCategory> categories) {}

  /** Word counts of one category, per stored difficulty of the selection. */
  public record CategoryCount(String key, Map<WordDifficulty, Long> byDifficulty, long total) {}

  /**
   * Active word counts for a selection. {@code byDifficulty} has exactly the difficulties of the
   * filter (in enum order, zeros included); {@code byCategory} has the selected categories.
   */
  public record WordCounts(
      String locale,
      String category,
      DifficultyFilter difficulty,
      long total,
      Map<WordDifficulty, Long> byDifficulty,
      List<CategoryCount> byCategory) {}

  /** A catalog word; server-side only, never served by the read-only metadata API. */
  public record CatalogWord(
      UUID id, String locale, String text, String categoryKey, WordDifficulty difficulty) {}
}
