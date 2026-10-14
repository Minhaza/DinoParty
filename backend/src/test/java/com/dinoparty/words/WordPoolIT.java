package com.dinoparty.words;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dinoparty.IntegrationTest;
import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The word pool over the real seeded catalog (V6) and the production custom-pack provider (no packs
 * until WP-03). Rolled back, so deactivated rows never leak into other tests.
 */
@IntegrationTest
@Transactional
class WordPoolIT {

  private static final UUID HOST = UUID.randomUUID();

  @Autowired WordPoolService pools;
  @Autowired WordCatalogService catalog;
  @Autowired JdbcTemplate jdbc;

  private WordPool builtIn(List<String> categories, DifficultyFilter difficulty) {
    return pools.build(
        new WordSelection(WordSource.BUILT_IN, categories, difficulty, List.of()), HOST, 3);
  }

  @Test
  void poolSizesEqualTheCatalogCountsForEverySelection() {
    for (DifficultyFilter difficulty : DifficultyFilter.values()) {
      assertThat(builtIn(List.of(), difficulty).size())
          .as("random-mix %s", difficulty)
          .isEqualTo(catalog.counts("en", null, difficulty.name()).total());
      for (String category : catalog.activeCategoryKeys()) {
        assertThat(builtIn(List.of(category), difficulty).size())
            .as("%s %s", category, difficulty)
            .isEqualTo(catalog.counts("en", category, difficulty.name()).total());
      }
    }
    assertThat(builtIn(List.of(WordCatalogTypes.RANDOM_MIX), DifficultyFilter.MIXED).size())
        .isEqualTo(653);
  }

  @Test
  void inactiveWordsAndCategoriesNeverEnterAPool() {
    jdbc.update(
        "update word_catalog_entry set active = false where locale = 'en' and display_text = 'cat'");
    jdbc.update("update word_category set active = false where key = 'sports'");

    WordPool pool = builtIn(List.of(), DifficultyFilter.MIXED);
    assertThat(pool.words()).doesNotContain("cat").hasSize(653 - 1 - 51);

    DomainException e =
        assertThrows(
            DomainException.class, () -> builtIn(List.of("sports"), DifficultyFilter.MIXED));
    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
  }

  @Test
  void customPacksAreUnavailableUntilWp03ProvidesThem() {
    DomainException e =
        assertThrows(
            DomainException.class,
            () ->
                pools.build(
                    new WordSelection(
                        WordSource.COMBINED,
                        List.of(),
                        DifficultyFilter.MIXED,
                        List.of(UUID.randomUUID())),
                    HOST,
                    3));
    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(e.fieldErrors()).extracting("field").containsExactly("settings.wordPackIds");
  }
}
