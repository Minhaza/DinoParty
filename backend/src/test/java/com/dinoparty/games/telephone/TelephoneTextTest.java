package com.dinoparty.games.telephone;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TelephoneTextTest {

  @Test
  void TP14_stripsControlAndFormatCharactersThenTrims() {
    assertThat(TelephoneText.sanitize("  a\u0000b​c‮ d\n ")).isEqualTo("abc d");
    assertThat(TelephoneText.sanitize("​ \t")).isEmpty();
  }

  @Test
  void TP14_lengthCountsCodePoints() {
    String twoEmoji = "🦖🦕"; // two dinosaurs, four UTF-16 units
    assertThat(twoEmoji.length()).isEqualTo(4);
    assertThat(TelephoneText.length(twoEmoji)).isEqualTo(2);
  }
}
