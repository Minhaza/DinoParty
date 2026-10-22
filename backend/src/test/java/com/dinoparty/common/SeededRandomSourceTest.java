package com.dinoparty.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class SeededRandomSourceTest {

  @Test
  void algorithmIsAvailableOnThisRuntime() {
    assertThatCode(SeededRandomSource::requireAvailable).doesNotThrowAnyException();
  }

  @Test
  void sameSeedGivesSameSequence() {
    SeededRandomSource a = new SeededRandomSource(42L);
    SeededRandomSource b = new SeededRandomSource(42L);
    for (int i = 0; i < 20; i++) {
      assertThat(a.nextInt(1000)).isEqualTo(b.nextInt(1000));
    }
    assertThat(a.nextBytes(16)).isEqualTo(b.nextBytes(16));
  }
}
