package com.dinoparty.words;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.words.CustomWordProvider.CustomWord;
import com.dinoparty.words.TestWords.Catalog;
import com.dinoparty.words.TestWords.Packs;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** [DG-15] word pool: sources, filters, de-duplication, start check and drawing (no Spring). */
class WordPoolServiceTest {

  private static final UUID HOST = UUID.randomUUID();
  private static final String MIX = WordCatalogTypes.RANDOM_MIX;

  private final Catalog catalog =
      new Catalog("animals", "food-drinks", "objects")
          .add("cat", "animals", WordDifficulty.EASY)
          .add("dog", "animals", WordDifficulty.EASY)
          .add("horse", "animals", WordDifficulty.MEDIUM)
          .add("giraffe", "animals", WordDifficulty.HARD)
          .add("apple", "food-drinks", WordDifficulty.EASY)
          .add("ice cream", "food-drinks", WordDifficulty.MEDIUM)
          .add("pizza", "food-drinks", WordDifficulty.MEDIUM)
          .add("croissant", "food-drinks", WordDifficulty.HARD)
          .add("chair", "objects", WordDifficulty.EASY)
          .add("lamp", "objects", WordDifficulty.EASY)
          .add("umbrella", "objects", WordDifficulty.MEDIUM)
          .add("telescope", "objects", WordDifficulty.HARD);
  private final Packs packs = new Packs();
  private final WordPoolService service = new WordPoolService(catalog, packs);

  private static WordSelection builtIn(List<String> categories, DifficultyFilter difficulty) {
    return new WordSelection(WordSource.BUILT_IN, categories, difficulty, List.of());
  }

  private Set<String> pool(WordSelection selection) {
    return Set.copyOf(service.build(selection, HOST, 1).words());
  }

  private static DomainException rejected(Runnable action) {
    try {
      action.run();
    } catch (DomainException e) {
      return e;
    }
    throw new AssertionError("expected a rejection");
  }

  // ------------------------------------------------------------------ built-in filters

  @Test
  void DG15_aSingleCategoryGivesOnlyItsWords() {
    assertThat(pool(builtIn(List.of("animals"), DifficultyFilter.MIXED)))
        .containsExactlyInAnyOrder("cat", "dog", "horse", "giraffe");
  }

  @Test
  void DG15_severalCategoriesAreUnitedAndCombinedWithTheDifficulty() {
    assertThat(pool(builtIn(List.of("animals", "objects"), DifficultyFilter.EASY)))
        .containsExactlyInAnyOrder("cat", "dog", "chair", "lamp");
  }

  @ParameterizedTest
  @EnumSource(
      value = DifficultyFilter.class,
      names = {"EASY", "MEDIUM", "HARD"})
  void DG15_eachDifficultyGivesExactlyItsStoredWords(DifficultyFilter filter) {
    Set<String> expected = new HashSet<>();
    catalog.entries.stream()
        .filter(w -> filter.difficulties().contains(w.difficulty()))
        .forEach(w -> expected.add(w.text()));

    assertThat(pool(builtIn(List.of(MIX), filter))).isEqualTo(expected).isNotEmpty();
  }

  @Test
  void DG15_mixedIsEveryDifficulty() {
    assertThat(pool(builtIn(List.of("food-drinks"), DifficultyFilter.MIXED)))
        .containsExactlyInAnyOrder("apple", "ice cream", "pizza", "croissant");
  }

  @Test
  void DG15_emptyFiltersAndTheRandomMixPresetMeanEveryActiveCategory() {
    Set<String> everything = new HashSet<>();
    catalog.entries.forEach(w -> everything.add(w.text()));

    assertThat(pool(builtIn(List.of(), DifficultyFilter.MIXED))).isEqualTo(everything);
    assertThat(pool(builtIn(List.of(MIX), DifficultyFilter.MIXED))).isEqualTo(everything);
  }

  @Test
  void DG15_anUnknownOrInactiveCategoryRejectsTheStart() {
    DomainException unknown =
        rejected(() -> pool(builtIn(List.of("animals", "space"), DifficultyFilter.MIXED)));
    assertThat(unknown.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(unknown.fieldErrors())
        .extracting("field")
        .containsExactly("settings.wordCategories");

    catalog.categories.remove("objects"); // deactivated after the host saved the settings
    assertThat(rejected(() -> pool(builtIn(List.of("objects"), DifficultyFilter.EASY))).code())
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(pool(builtIn(List.of(MIX), DifficultyFilter.EASY)))
        .containsExactlyInAnyOrder("cat", "dog", "apple"); // Random Mix follows the active set
  }

  // ------------------------------------------------------------------ custom and combined

  @Test
  void DG15_customWordsAreSelectedByPackOnlyWhateverTheirMetadata() {
    UUID pack = UUID.randomUUID();
    packs.packs.put(
        pack,
        List.of(
            new CustomWord("volcano", null, null),
            new CustomWord("comet", "space", WordDifficulty.HARD),
            new CustomWord("kite", "objects", WordDifficulty.EASY)));
    WordSelection custom =
        new WordSelection(
            WordSource.CUSTOM, List.of("animals"), DifficultyFilter.MEDIUM, List.of(pack));

    // Neither the category nor the difficulty filter removes a custom word.
    assertThat(pool(custom)).containsExactlyInAnyOrder("volcano", "comet", "kite");
  }

  @Test
  void DG15_combinedFiltersOnlyTheBuiltInWordsAndDeduplicatesNormalizedForms() {
    UUID first = packs.add("Ice-Cream", "icecream", "CAT", "rocket", "café");
    UUID second = packs.add("cafe", "Rocket", "moon");
    WordSelection combined =
        new WordSelection(
            WordSource.COMBINED,
            List.of("animals", "food-drinks"),
            DifficultyFilter.MEDIUM,
            List.of(first, second));

    List<String> words = service.build(combined, HOST, 1).words();

    // Built-in first (filtered), then the packs in the selected order; the first form wins.
    assertThat(words)
        .containsExactly("horse", "ice cream", "pizza", "CAT", "rocket", "café", "moon")
        .doesNotHaveDuplicates();
  }

  @Test
  void DG15_customSourcesNeedAvailablePacks() {
    UUID mine = packs.add("one", "two", "three");
    UUID theirs = packs.add("four", "five", "six");
    packs.owners.put(theirs, UUID.randomUUID());

    for (WordSource source : List.of(WordSource.CUSTOM, WordSource.COMBINED)) {
      for (List<UUID> ids :
          List.of(List.<UUID>of(), List.of(UUID.randomUUID()), List.of(mine, theirs))) {
        DomainException e =
            rejected(
                () -> pool(new WordSelection(source, List.of(MIX), DifficultyFilter.MIXED, ids)));
        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(e.fieldErrors()).extracting("field").containsExactly("settings.wordPackIds");
      }
    }
    // The built-in source ignores stored pack ids, so a host can switch back without clearing them.
    assertThat(
            pool(
                new WordSelection(
                    WordSource.BUILT_IN, List.of(MIX), DifficultyFilter.MIXED, List.of(theirs))))
        .contains("cat")
        .doesNotContain("four");
  }

  @Test
  void DG15_entriesThatAreNotUsableWordsDoNotCount() {
    UUID pack =
        packs.add(
            "  sea  turtle ", "ok", "a-", "rock3t", "hot dog", "x".repeat(21), "  Lighthouse ");
    WordSelection custom =
        new WordSelection(WordSource.CUSTOM, List.of(), DifficultyFilter.MIXED, List.of(pack));

    assertThat(service.build(custom, HOST, 1).words()).containsExactly("hot dog", "Lighthouse");
  }

  // ------------------------------------------------------------------ start check

  @Test
  void DG15_tooFewDistinctWordsRejectTheStartWithRequiredAndAvailableCounts() {
    UUID pack = packs.add("Cat", "cat", "CAT", "dog");
    WordSelection custom =
        new WordSelection(WordSource.CUSTOM, List.of(), DifficultyFilter.MIXED, List.of(pack));

    DomainException e = rejected(() -> service.build(custom, HOST, 3));

    assertThat(e.code()).isEqualTo(ErrorCode.WORD_POOL_TOO_SMALL);
    assertThat(e.details()).isEqualTo(Map.of("required", 3, "available", 2));
    assertThat(e.getMessage()).contains("2 usable words").contains("at least 3");

    // A filter that matches nothing is the same error with 0 available.
    catalog.entries.removeIf(w -> w.difficulty() == WordDifficulty.HARD);
    DomainException empty =
        rejected(() -> service.build(builtIn(List.of(MIX), DifficultyFilter.HARD), HOST, 3));
    assertThat(empty.details()).isEqualTo(Map.of("required", 3, "available", 0));
  }

  @Test
  void DG15_exactlyOneDrawOfWordsIsEnough() {
    UUID pack = packs.add("sun", "moon", "star");
    WordSelection custom =
        new WordSelection(WordSource.CUSTOM, List.of(), DifficultyFilter.MIXED, List.of(pack));

    assertThat(service.build(custom, HOST, 3).size()).isEqualTo(3);
  }

  @Test
  void DG15_aBuiltPoolIsFrozenAgainstLaterCatalogAndPackChanges() {
    UUID pack = packs.add("sun", "moon", "star");
    WordPool pool =
        service.build(
            new WordSelection(
                WordSource.COMBINED, List.of("animals"), DifficultyFilter.EASY, List.of(pack)),
            HOST,
            3);
    List<String> before = List.copyOf(pool.words());

    packs.packs.get(pack).clear();
    packs.packs.get(pack).add(new CustomWord("planet", null, null));
    catalog.entries.clear();

    assertThat(pool.words()).isEqualTo(before).containsExactly("cat", "dog", "sun", "moon", "star");
    assertThat(pool.toString()).doesNotContain("cat").contains("5");
  }

  // ------------------------------------------------------------------ drawing

  @Test
  void DG15_drawsDistinctUnusedWordsAndFallsBackToRepeatsOnExhaustion() {
    WordPool pool = new WordPool(List.of("aaa", "bbb", "ccc", "ddd"));
    SeededRandomSource random = new SeededRandomSource(1);

    assertThat(pool.draw(Set.of("aaa"), random, 3)).containsExactlyInAnyOrder("bbb", "ccc", "ddd");

    // Only one unused word left: the whole pool is used again (repeats allowed).
    List<String> fallback = pool.draw(Set.of("aaa", "bbb", "ccc"), random, 3);
    assertThat(fallback).hasSize(3).doesNotHaveDuplicates();
    assertThat(pool.words()).containsAll(fallback);

    assertThatThrownBy(() -> new WordPool(List.of("aaa", "bbb")).draw(Set.of(), random, 3))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void DG15_noChosenWordRepeatsWhileUnusedWordsRemainAndDrawsAreDeterministicPerSeed() {
    WordPool pool =
        TestWords.service().build(builtIn(List.of(MIX), DifficultyFilter.MIXED), HOST, 3);
    SeededRandomSource a = new SeededRandomSource(42);
    SeededRandomSource b = new SeededRandomSource(42);
    Set<String> used = new HashSet<>();
    List<String> chosen = new ArrayList<>();
    for (int round = 0; round < 50; round++) { // 10 players × 5 cycles, the longest game
      List<String> choices = pool.draw(used, a, 3);
      assertThat(choices).isEqualTo(pool.draw(used, b, 3));
      assertThat(choices).hasSize(3).doesNotHaveDuplicates().noneMatch(used::contains);
      used.add(choices.get(round % 3));
      chosen.add(choices.get(round % 3));
    }
    assertThat(chosen).doesNotHaveDuplicates();
  }

  @Test
  void wordSourceSettingKeys() {
    assertThat(WordSource.BUILT_IN.key()).isEqualTo("built-in");
    assertThat(WordSource.fromKey("combined")).contains(WordSource.COMBINED);
    assertThat(WordSource.fromKey("BUILT_IN")).isEmpty();
    assertThat(WordSource.CUSTOM.usesBuiltIn()).isFalse();
    assertThat(WordSource.COMBINED.usesBuiltIn()).isTrue();
    assertThat(WordSource.COMBINED.usesCustom()).isTrue();
  }
}
