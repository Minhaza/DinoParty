package com.dinoparty.common;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Reproducible {@link RandomSource} for one game session: the same seed yields the same shuffle and
 * the same module decisions. The seed is never sent to a client. Not thread safe on purpose; a
 * session is only ever used inside its room mailbox.
 */
public final class SeededRandomSource implements RandomSource {

  private final RandomGenerator generator;

  public SeededRandomSource(long seed) {
    this.generator = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
  }

  @Override
  public int nextInt(int bound) {
    return generator.nextInt(bound);
  }

  @Override
  public byte[] nextBytes(int length) {
    byte[] bytes = new byte[length];
    generator.nextBytes(bytes);
    return bytes;
  }
}
