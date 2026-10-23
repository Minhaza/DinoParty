package com.dinoparty.words;

import com.dinoparty.words.WordCatalogTypes.CatalogWord;
import java.util.List;
import java.util.Map;

/** Built-in words for the word pool; implemented by the catalog ({@link WordCatalogService}). */
public interface BuiltInWordProvider {

  /** Keys of the active categories, in display order. */
  List<String> activeCategoryKeys();

  /** Active words of the given active categories whose stored difficulty matches the filter. */
  List<CatalogWord> words(List<String> categoryKeys, DifficultyFilter difficulty);

  /**
   * Label of every active category in the game words' locale (display order); a category without a
   * label is labelled with its key.
   */
  Map<String, String> categoryLabels();
}
