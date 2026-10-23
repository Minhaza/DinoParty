package com.dinoparty.games.impostor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The settings schema the room validates Dino Impostor with (api-contracts §2.2, game-rules Game 3
 * settings) and the values {@link DinoImpostorSettings#from} reads must not drift.
 */
class DinoImpostorSettingsTest {

  private static Map<String, Object> resolve(Map<String, Object> supplied) {
    return DinoImpostorSettings.SCHEMA.resolve(supplied);
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
  void theDefaultsAreTheContractDefaultsInDisplayOrder() {
    Map<String, Object> defaults = DinoImpostorSettings.defaults();
    assertThat(defaults)
        .containsExactly(
            Map.entry("rounds", 0),
            Map.entry("laps", 2),
            Map.entry("turnSeconds", 25),
            Map.entry("voteSeconds", 60),
            Map.entry("guessSeconds", 30),
            Map.entry("difficulty", "mixed"));
    assertThat(resolve(defaults)).isEqualTo(defaults);
    DinoImpostorSettings s = DinoImpostorSettings.from(defaults);
    assertThat(s).isEqualTo(DinoImpostorSettings.from(Map.of()));
    assertThat(s.laps()).isEqualTo(2);
    assertThat(s.turnSeconds()).isEqualTo(25);
    assertThat(s.difficulty()).isEqualTo(ImpostorDifficulty.MIXED);
  }

  @Test
  void boundsAreInclusiveAndEverythingOutsideIsRejected() {
    Map<String, int[]> bounds =
        Map.of(
            "laps", new int[] {1, 3},
            "turnSeconds", new int[] {10, 60},
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
    rejected("cycles", 2);
  }

  @Test
  void DI2_roundsZeroIsThePlayerCountClampedToThreeToTen() {
    DinoImpostorSettings auto = DinoImpostorSettings.from(Map.of("rounds", 0));
    assertThat(auto.rounds(3)).isEqualTo(3);
    for (int n = 4; n <= 10; n++) {
      assertThat(auto.rounds(n)).isEqualTo(n);
    }
    assertThat(auto.rounds(12)).isEqualTo(10);
    assertThat(auto.wordsToDraw()).isEqualTo(10);

    DinoImpostorSettings fixed = DinoImpostorSettings.from(Map.of("rounds", 5));
    assertThat(fixed.rounds(9)).isEqualTo(5);
    assertThat(fixed.wordsToDraw()).isEqualTo(5);
  }

  @Test
  void unusableStoredValuesFallBackToTheDefaults() {
    DinoImpostorSettings s =
        DinoImpostorSettings.from(Map.of("laps", 9, "turnSeconds", "x", "difficulty", "hard"));
    assertThat(s).isEqualTo(DinoImpostorSettings.from(Map.of()));
  }
}
