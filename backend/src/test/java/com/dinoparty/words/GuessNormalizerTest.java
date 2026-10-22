package com.dinoparty.words;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The shared guess normalization ([DG-4], used by [DI-11]). Same table as Draw & Guess's matcher
 * tests, against the public API every game uses.
 */
class GuessNormalizerTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Ice Cream|ice cream",
        "  ICE   cream!! |ice cream",
        "crème brûlée|creme brulee",
        "tug-of-war|tug of war",
        "snake_case|snake case",
        "ＦＵＬＬＷＩＤＴＨ|fullwidth",
        "Ⅻ o'clock|xii oclock",
        "ﬁsh|fish",
        "...|''",
        "dino 42|dino 42",
        "İstanbul|istanbul",
      })
  void DG4_normalization(String input, String expected) {
    assertThat(GuessNormalizer.normalize(input)).isEqualTo(expected);
  }

  @Test
  void DG4_DI11_correctIgnoresSpacesCaseAndAccentsButNotExtraLetters() {
    String word = GuessNormalizer.normalize("ice cream");
    assertThat(GuessNormalizer.isCorrect(GuessNormalizer.normalize("ICECREAM"), word)).isTrue();
    assertThat(GuessNormalizer.isCorrect(GuessNormalizer.normalize("ice-cream"), word)).isTrue();
    assertThat(GuessNormalizer.isCorrect(GuessNormalizer.normalize("ice creams"), word)).isFalse();
    assertThat(
            GuessNormalizer.isCorrect(
                GuessNormalizer.normalize("Crème Brûlée"),
                GuessNormalizer.normalize("creme brulee")))
        .isTrue();
  }

  @Test
  void DG4_sanitizeStripsControlAndFormatCharactersThenTrims() {
    assertThat(GuessNormalizer.sanitize("  he\u0000l​lo‮ \n")).isEqualTo("hello");
    assertThat(GuessNormalizer.length("🦖".repeat(80))).isEqualTo(80);
  }

  @Test
  void spacelessRemovesEverySpace() {
    assertThat(GuessNormalizer.spaceless("ice cream sandwich")).isEqualTo("icecreamsandwich");
  }
}
