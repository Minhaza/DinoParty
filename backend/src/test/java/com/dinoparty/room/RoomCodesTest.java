package com.dinoparty.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.RandomSource;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class RoomCodesTest {

  private final SecureRandom secure = new SecureRandom();
  private final RoomCodes codes =
      new RoomCodes(
          new RandomSource() {
            @Override
            public int nextInt(int bound) {
              return secure.nextInt(bound);
            }

            @Override
            public byte[] nextBytes(int length) {
              return new byte[length];
            }
          });

  @Test
  void generatedCodesUseTheUnambiguousAlphabet() {
    for (int i = 0; i < 500; i++) {
      assertThat(codes.generate()).hasSize(6).matches("[ABCDEFGHJKMNPQRSTUVWXYZ2-9]{6}");
    }
  }

  @Test
  void normalizeUppercasesValidCodesAndRejectsOthers() {
    assertThat(RoomCodes.normalize("abc234")).contains("ABC234");
    assertThat(RoomCodes.normalize("ABC23")).isEmpty();
    assertThat(RoomCodes.normalize("ABC2345")).isEmpty();
    assertThat(RoomCodes.normalize("ABCDE0")).isEmpty(); // 0 is not in the alphabet
    assertThat(RoomCodes.normalize("ABCDEL")).isEmpty(); // neither is L
    assertThat(RoomCodes.normalize(null)).isEmpty();
    assertThat(RoomCodes.normalize("ab c23")).isEmpty();
  }
}
