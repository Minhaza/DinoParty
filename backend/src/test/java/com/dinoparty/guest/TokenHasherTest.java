package com.dinoparty.guest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenHasherTest {

  @Test
  void hashesToKnownSha256Hex() {
    assertThat(TokenHasher.hash("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void differentTokensHashDifferentlyAndStably() {
    assertThat(TokenHasher.hash("token-a")).isEqualTo(TokenHasher.hash("token-a"));
    assertThat(TokenHasher.hash("token-a")).isNotEqualTo(TokenHasher.hash("token-b"));
  }
}
