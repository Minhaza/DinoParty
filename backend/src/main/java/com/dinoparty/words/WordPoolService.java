package com.dinoparty.words;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.words.CustomWordProvider.CustomWord;
import com.dinoparty.words.WordCatalogTypes.CatalogWord;
import com.dinoparty.words.WordCatalogTypes.CategorizedWord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the eligible word pool of a game from its {@link WordSelection} (game-rules [DG-15],
 * api-contracts §2.2): built-in words filtered by categories and difficulty, custom pack words
 * selected by pack only, merged in that order and de-duplicated by {@link WordKey}. Called once at
 * game start; a selection that cannot be used rejects the start before anything is initialized.
 */
@Service
@Transactional(readOnly = true)
public class WordPoolService {

  public static final String CATEGORIES_FIELD = "settings.wordCategories";
  public static final String PACKS_FIELD = "settings.wordPackIds";

  private final BuiltInWordProvider builtIn;
  private final CustomWordProvider custom;

  public WordPoolService(BuiltInWordProvider builtIn, CustomWordProvider custom) {
    this.builtIn = builtIn;
    this.custom = custom;
  }

  /**
   * The frozen pool for {@code selection}, with at least {@code minimum} distinct usable words.
   *
   * @param requester the authenticated host; custom packs are resolved with their access rights
   * @throws DomainException {@code VALIDATION_FAILED} for an unknown or inactive category, or for a
   *     missing, unknown or unavailable pack; {@code WORD_POOL_TOO_SMALL} with {@code details:
   *     {required, available}} when fewer than {@code minimum} distinct usable words remain
   */
  public WordPool build(WordSelection selection, UUID requester, int minimum) {
    List<String> candidates = new ArrayList<>();
    if (selection.source().usesBuiltIn()) {
      for (CatalogWord w : builtIn.words(categories(selection), selection.difficulty())) {
        candidates.add(w.text());
      }
    }
    if (selection.source().usesCustom()) {
      // Custom words are selected by pack only: their category/difficulty metadata is optional and
      // never filters them out (api-contracts §2.2).
      for (CustomWord w : packWords(selection.packIds(), requester)) {
        candidates.add(w.text());
      }
    }
    Map<String, String> byKey = new LinkedHashMap<>();
    for (String candidate : candidates) {
      String text = WordKey.usable(candidate);
      if (text != null) {
        byKey.putIfAbsent(WordKey.of(text), text); // first occurrence wins: built-in, then packs
      }
    }
    if (byKey.size() < minimum) {
      throw new DomainException(
          ErrorCode.WORD_POOL_TOO_SMALL,
          "These word settings give "
              + byKey.size()
              + " usable words, but at least "
              + minimum
              + " are needed.",
          List.of(),
          Map.of("required", minimum, "available", byKey.size()));
    }
    return new WordPool(List.copyOf(byKey.values()));
  }

  /**
   * Every usable built-in word of every active category whose difficulty matches one of {@code
   * difficulties}, with its category key and label, de-duplicated by {@link WordKey} (first
   * occurrence wins, in filter order). For games whose clue is the category (D60); drawing from it
   * is the game's.
   */
  public List<CategorizedWord> categorizedBuiltIn(List<DifficultyFilter> difficulties) {
    List<String> active = builtIn.activeCategoryKeys();
    Map<String, String> labels = builtIn.categoryLabels();
    Map<String, CategorizedWord> byKey = new LinkedHashMap<>();
    for (DifficultyFilter difficulty : difficulties) {
      for (CatalogWord w : builtIn.words(active, difficulty)) {
        String text = WordKey.usable(w.text());
        if (text != null) {
          String label = labels.getOrDefault(w.categoryKey(), w.categoryKey());
          byKey.putIfAbsent(WordKey.of(text), new CategorizedWord(text, w.categoryKey(), label));
        }
      }
    }
    return List.copyOf(byKey.values());
  }

  private List<String> categories(WordSelection selection) {
    List<String> active = builtIn.activeCategoryKeys();
    if (selection.randomMix()) {
      return active;
    }
    for (String key : selection.categories()) {
      if (!active.contains(key)) {
        throw DomainException.invalid(
            CATEGORIES_FIELD, "must contain active category keys or random-mix");
      }
    }
    return selection.categories();
  }

  private List<CustomWord> packWords(List<UUID> packIds, UUID requester) {
    if (packIds.isEmpty()) {
      throw DomainException.invalid(PACKS_FIELD, "must select at least one word pack");
    }
    Map<UUID, List<CustomWord>> packs = custom.packs(requester, packIds);
    List<CustomWord> words = new ArrayList<>();
    for (UUID id : packIds) {
      List<CustomWord> pack = packs.get(id);
      if (pack == null) {
        throw DomainException.invalid(PACKS_FIELD, "contains a word pack that is not available");
      }
      words.addAll(pack);
    }
    return words;
  }
}
