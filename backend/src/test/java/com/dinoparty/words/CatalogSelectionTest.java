package com.dinoparty.words;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Locale normalization and difficulty filters of the word catalog (no Spring, no database). */
class CatalogSelectionTest {

  @ParameterizedTest
  @CsvSource({"en,en", "EN,en", " en ,en", "en-us,en-US", "en_US,en-US", "PT-br,pt-BR", "fil,fil"})
  void normalizesLocaleTags(String input, String expected) {
    assertThat(CatalogLocales.normalize(input)).isEqualTo(expected);
  }

  @Test
  void missingLocaleMeansTheDefault() {
    assertThat(CatalogLocales.normalize(null)).isEqualTo("en");
    assertThat(CatalogLocales.normalize("  ")).isEqualTo("en");
  }

  @ParameterizedTest
  @ValueSource(strings = {"e", "english", "en-", "en-USA", "en--US", "e1", "en US", "../en"})
  void rejectsMalformedLocaleTags(String input) {
    assertThatThrownBy(() -> CatalogLocales.normalize(input))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
              assertThat(e.fieldErrors()).singleElement().extracting("field").isEqualTo("locale");
            });
  }

  @Test
  void fallbackChainGoesFromRegionToLanguageToDefault() {
    assertThat(CatalogLocales.fallbackChain("pt-BR")).containsExactly("pt-BR", "pt", "en");
    assertThat(CatalogLocales.fallbackChain("fr")).containsExactly("fr", "en");
    assertThat(CatalogLocales.fallbackChain("en-GB")).containsExactly("en-GB", "en");
    assertThat(CatalogLocales.fallbackChain("en")).containsExactly("en");
  }

  @Test
  void mixedIsAFilterOverEveryStoredDifficulty() {
    assertThat(DifficultyFilter.MIXED.difficulties())
        .isEqualTo(EnumSet.allOf(WordDifficulty.class));
    assertThat(DifficultyFilter.EASY.difficulties()).containsExactly(WordDifficulty.EASY);
    assertThat(DifficultyFilter.MEDIUM.difficulties()).containsExactly(WordDifficulty.MEDIUM);
    assertThat(DifficultyFilter.HARD.difficulties()).containsExactly(WordDifficulty.HARD);
    // MIXED is never a stored difficulty.
    assertThat(WordDifficulty.values()).extracting(Enum::name).doesNotContain("MIXED");
  }

  @Test
  void parsesDifficultyCaseInsensitivelyWithMixedAsDefault() {
    assertThat(DifficultyFilter.parse("easy")).isEqualTo(DifficultyFilter.EASY);
    assertThat(DifficultyFilter.parse(" Medium ")).isEqualTo(DifficultyFilter.MEDIUM);
    assertThat(DifficultyFilter.parse("HARD")).isEqualTo(DifficultyFilter.HARD);
    assertThat(DifficultyFilter.parse("mixed")).isEqualTo(DifficultyFilter.MIXED);
    assertThat(DifficultyFilter.parse(null)).isEqualTo(DifficultyFilter.MIXED);
    assertThat(DifficultyFilter.parse("")).isEqualTo(DifficultyFilter.MIXED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"extreme", "EASY,HARD", "1", "random-mix"})
  void rejectsUnknownDifficulties(String input) {
    assertThatThrownBy(() -> DifficultyFilter.parse(input))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.fieldErrors()).extracting("field").containsExactly("difficulty"));
  }
}
