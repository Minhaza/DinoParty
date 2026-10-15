package com.dinoparty.games.telephone;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TelephoneSettingsTest {

  @Test
  void TP2_autoEntriesIsNWhenEvenElseNMinusOne() {
    int[][] expected = {{4, 4}, {5, 4}, {6, 6}, {7, 6}, {8, 8}};
    for (int[] e : expected) {
      assertThat(TelephoneSettings.from(Map.of()).entries(e[0])).as("n=%d", e[0]).isEqualTo(e[1]);
    }
  }

  @Test
  void TP2_explicitEntriesAreClampedToN() {
    assertThat(TelephoneSettings.from(Map.of("entriesPerChain", 8)).entries(5)).isEqualTo(5);
    assertThat(TelephoneSettings.from(Map.of("entriesPerChain", 5)).entries(8)).isEqualTo(5);
    assertThat(TelephoneSettings.from(Map.of("entriesPerChain", 4)).entries(4)).isEqualTo(4);
  }

  @Test
  void defaultsAndFallbackForUnusableValues() {
    TelephoneSettings s = TelephoneSettings.from(Map.of("promptSeconds", "x", "drawSeconds", 5));
    assertThat(s.promptSeconds()).isEqualTo(60);
    assertThat(s.drawSeconds()).isEqualTo(90);
    assertThat(s.describeSeconds()).isEqualTo(45);
    assertThat(s.entriesPerChain()).isZero();
    assertThat(TelephoneSettings.defaults())
        .containsEntry("promptSeconds", 60)
        .containsEntry("drawSeconds", 90)
        .containsEntry("describeSeconds", 45)
        .containsEntry("entriesPerChain", 0)
        .containsEntry("allowSkipTimeout", true);
  }

  @Test
  void secondsPerKind() {
    TelephoneSettings s = TelephoneSettings.from(Map.of());
    assertThat(s.seconds(TelephoneState.Kind.PROMPT)).isEqualTo(60);
    assertThat(s.seconds(TelephoneState.Kind.DRAW)).isEqualTo(90);
    assertThat(s.seconds(TelephoneState.Kind.DESCRIBE)).isEqualTo(45);
  }
}
