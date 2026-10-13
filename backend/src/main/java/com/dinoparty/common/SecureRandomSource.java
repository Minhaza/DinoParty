package com.dinoparty.common;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** Cryptographically strong {@link RandomSource}; used for tokens and room codes. */
@Component
class SecureRandomSource implements RandomSource {

  private final SecureRandom random = new SecureRandom();

  @Override
  public int nextInt(int bound) {
    return random.nextInt(bound);
  }

  @Override
  public byte[] nextBytes(int length) {
    byte[] bytes = new byte[length];
    random.nextBytes(bytes);
    return bytes;
  }
}
