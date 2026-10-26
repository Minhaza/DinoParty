package com.dinoparty.games.impostor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The settings schema the room validates Sketchy Saurus with (api-contracts §2.2, game-rules Game 4
 * settings) and the values {@link SketchySaurusSettings#from} reads must not drift.
 */
class SketchySaurusSettingsTest {

  private static Map<String, Object> resolve(Map<String, Object> supplied) {
    return SketchySaurusSettings.SCHEMA.resolve(supplied);
  }

  private static void rejected(String key, Object value) {
    assertThatThrownBy(() -> resolve(Map.of(key, value)))
        .as("%s=%s", key, value)
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
              assertThat(e.fieldErrors())
                  .singleElement()
                  .satisfies(v -> assertThat(v.field()).isEqualTo("settings." + key));
            });
  }

  @Test
  void theDefaultsAreTheContractDefaults() {
    Map<String, Object> defaults = SketchySaurusSettings.defaults();
    assertThat(defaults)
        .containsOnly(
            Map.entry("rounds", 0),
            Map.entry("sketchSeconds", 60),
            Map.entry("voteSeconds", 60),
            Map.entry("guessSeconds", 30),
            Map.entry("difficulty", "mixed"),
            Map.entry("impostorKnows", true),
            Map.entry("unawareGuess", false));
    assertThat(resolve(defaults)).isEqualTo(defaults);
    SketchySaurusSettings s = SketchySaurusSettings.from(defaults);
    assertThat(s).isEqualTo(SketchySaurusSettings.from(Map.of()));
    assertThat(s.sketchSeconds()).isEqualTo(60);
    assertThat(s.impostorKnows()).isTrue();
    assertThat(s.unawareGuess()).isFalse();
    assertThat(s.difficulty()).isEqualTo(ImpostorDifficulty.MIXED);
  }

  @Test
  void boundsAreInclusiveAndEverythingOutsideIsRejected() {
    Map<String, int[]> bounds =
        Map.of(
            "sketchSeconds", new int[] {30, 120},
            "voteSeconds", new int[] {30, 120},
            "guessSeconds", new int[] {15, 60});
    bounds.forEach(
        (key, b) -> {
          assertThat(resolve(Map.of(key, b[0]))).containsEntry(key, b[0]);
          assertThat(resolve(Map.of(key, b[1]))).containsEntry(key, b[1]);
          rejected(key, b[0] - 1);
          rejected(key, b[1] + 1);
          rejected(key, "5");
        });
    for (int ok : new int[] {0, 3, 7, 10}) {
      assertThat(resolve(Map.of("rounds", ok))).containsEntry("rounds", ok);
    }
    for (int bad : new int[] {-1, 1, 2, 11}) {
      rejected("rounds", bad);
    }
    for (String ok : List.of("easy", "medium", "mixed")) {
      assertThat(resolve(Map.of("difficulty", ok))).containsEntry("difficulty", ok);
    }
    rejected("difficulty", "hard");
    rejected("impostorKnows", "yes");
    rejected("unawareGuess", 1);
    rejected("laps", 2); // a Dino Impostor setting
    rejected("turnSeconds", 25);
  }

  @Test
  void theBooleansAcceptBothValues() {
    assertThat(resolve(Map.of("impostorKnows", false))).containsEntry("impostorKnows", false);
    assertThat(resolve(Map.of("unawareGuess", true))).containsEntry("unawareGuess", true);
  }

  @Test
  void DI2_roundsZeroIsAutomaticAndClampedToThreeToTen() {
    SketchySaurusSettings auto = SketchySaurusSettings.from(Map.of("rounds", 0));
    assertThat(auto.rounds(2)).isEqualTo(3);
    assertThat(auto.rounds(5)).isEqualTo(5);
    assertThat(auto.rounds(10)).isEqualTo(10);
    assertThat(auto.wordsToDraw()).isEqualTo(10);
    SketchySaurusSettings fixed = SketchySaurusSettings.from(Map.of("rounds", 4));
    assertThat(fixed.rounds(9)).isEqualTo(4);
    assertThat(fixed.wordsToDraw()).isEqualTo(4);
  }

  @Test
  void SS3_unawareGuessIsIgnoredWhenTheImpostorKnows() {
    for (boolean unaware : new boolean[] {false, true}) {
      Map<String, Object> knows = Map.of("impostorKnows", true, "unawareGuess", unaware);
      assertThat(SketchySaurusSettings.from(knows).impostorMayGuess()).isTrue();
    }
    assertThat(
            SketchySaurusSettings.from(Map.of("impostorKnows", false, "unawareGuess", false))
                .impostorMayGuess())
        .isFalse();
    assertThat(
            SketchySaurusSettings.from(Map.of("impostorKnows", false, "unawareGuess", true))
                .impostorMayGuess())
        .isTrue();
  }

  @Test
  void unusableStoredValuesFallBackToTheDefaults() {
    SketchySaurusSettings s =
        SketchySaurusSettings.from(
            Map.of("sketchSeconds", 5, "impostorKnows", "no", "rounds", 99, "difficulty", "x"));
    assertThat(s).isEqualTo(SketchySaurusSettings.from(Map.of()));
  }
}
