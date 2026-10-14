package com.dinoparty.wordpacks;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.wordpacks.WordPackTypes.RowProblem;
import com.dinoparty.wordpacks.WordRules.Checked;
import com.dinoparty.words.WordDifficulty;
import org.junit.jupiter.api.Test;

class WordRulesTest {

  private static Checked check(String text) {
    return WordRules.check(text, null, null);
  }

  @Test
  void trimsAndCollapsesWhitespaceAndAppliesTheDefaults() {
    Checked c = check("  Ice \t  cream  ");
    assertThat(c.problem()).isNull();
    assertThat(c.word().text()).isEqualTo("Ice cream");
    assertThat(c.word().category()).isEqualTo("Custom");
    assertThat(c.word().difficulty()).isEqualTo(WordDifficulty.MEDIUM);
  }

  @Test
  void blankMetadataFallsBackToTheDefaults() {
    Checked c = WordRules.check("cat", "  ", "");
    assertThat(c.word().category()).isEqualTo("Custom");
    assertThat(c.word().difficulty()).isEqualTo(WordDifficulty.MEDIUM);
  }

  @Test
  void difficultyIsCaseInsensitiveAndMixedIsRejected() {
    assertThat(WordRules.check("cat", null, " hard ").word().difficulty())
        .isEqualTo(WordDifficulty.HARD);
    assertThat(WordRules.check("cat", null, "Easy").word().difficulty())
        .isEqualTo(WordDifficulty.EASY);
    assertThat(WordRules.check("cat", null, "mixed").problem())
        .isEqualTo(RowProblem.INVALID_DIFFICULTY);
    assertThat(WordRules.check("cat", null, "5").problem())
        .isEqualTo(RowProblem.INVALID_DIFFICULTY);
  }

  @Test
  void accentsArePreservedAndTextIsNormalizedToNfc() {
    String decomposed = "Café"; // e + combining acute
    Checked c = check(decomposed);
    assertThat(c.word().text()).isEqualTo("Café");
    assertThat(c.word().key()).isEqualTo(check("CAFÉ").word().key());
  }

  @Test
  void duplicateKeysIgnoreCaseButKeepAccents() {
    assertThat(check("Dinosaur").word().key()).isEqualTo(check("DINOSAUR").word().key());
    assertThat(check("Café").word().key()).isNotEqualTo(check("cafe").word().key());
    assertThat(check("Straße").word().key()).isEqualTo(check("STRASSE").word().key());
    // NFKC: the fullwidth form is the same word.
    assertThat(check("ＡＢＣ").word().key()).isEqualTo(check("abc").word().key());
  }

  @Test
  void wordLengthCountsCodePointsNotUtf16Units() {
    String eighty = "🦖".repeat(80); // 80 emoji = 160 chars
    assertThat(check(eighty).problem()).isNull();
    assertThat(check(eighty + "🦖").problem()).isEqualTo(RowProblem.TOO_LONG);
    assertThat(check("a".repeat(80)).problem()).isNull();
    assertThat(check("a".repeat(81)).problem()).isEqualTo(RowProblem.TOO_LONG);
  }

  @Test
  void hugeInputIsRejectedBeforeNormalization() {
    assertThat(check("a".repeat(2_000_000)).problem()).isEqualTo(RowProblem.TOO_LONG);
  }

  @Test
  void blankIsRejected() {
    assertThat(check(null).problem()).isEqualTo(RowProblem.BLANK_WORD);
    assertThat(check("   \t ").problem()).isEqualTo(RowProblem.BLANK_WORD);
  }

  @Test
  void unsafeOrInvisibleCharactersAreRejected() {
    assertThat(check("a\u0000b").problem()).isEqualTo(RowProblem.INVALID_CHARACTER);
    assertThat(check("a‮b").problem()).isEqualTo(RowProblem.INVALID_CHARACTER); // bidi override
    assertThat(check("a​b").problem()).isEqualTo(RowProblem.INVALID_CHARACTER); // zero width
    assertThat(check("a﻿b").problem()).isEqualTo(RowProblem.INVALID_CHARACTER); // BOM inside
    assertThat(check("ab").problem()).isEqualTo(RowProblem.INVALID_CHARACTER); // private use
    assertThat(check("a\uD800b").problem())
        .isEqualTo(RowProblem.INVALID_CHARACTER); // lone surrogate
    assertThat(check("<script>alert(1)</script>").problem())
        .isEqualTo(RowProblem.INVALID_CHARACTER);
    assertThat(check("a > b").problem()).isEqualTo(RowProblem.INVALID_CHARACTER);
  }

  @Test
  void joinersOfEmojiAndIndicScriptsAreAllowed() {
    assertThat(check("👨‍👩‍👧").problem()).isNull();
  }

  @Test
  void badCategoryIsRejectedWithItsOwnCode() {
    assertThat(WordRules.check("cat", "x".repeat(41), null).problem())
        .isEqualTo(RowProblem.INVALID_CATEGORY);
    assertThat(WordRules.check("cat", "a<b", null).problem())
        .isEqualTo(RowProblem.INVALID_CATEGORY);
    assertThat(WordRules.check("cat", "x".repeat(40), null).word().category()).hasSize(40);
  }
}
