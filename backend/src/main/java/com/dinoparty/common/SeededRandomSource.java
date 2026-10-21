package com.dinoparty.common;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Reproducible {@link RandomSource} for one game session: the same seed yields the same shuffle and
 * the same module decisions. The seed is never sent to a client. Not thread safe on purpose; a
 * session is only ever used inside its room mailbox.
 */
public final class SeededRandomSource implements RandomSource {

  /** Fixed so a stored seed (D16) replays the same way; lives in the {@code jdk.random} module. */
  static final String ALGORITHM = "L64X128MixRandom";

  private final RandomGenerator generator;

  public SeededRandomSource(long seed) {
    this.generator = RandomGeneratorFactory.of(ALGORITHM).create(seed);
  }

  /**
   * Fails when the Java runtime lacks the algorithm (a trimmed image without {@code jdk.random}),
   * so startup fails instead of every game start.
   */
  public static void requireAvailable() {
    try {
      RandomGeneratorFactory.of(ALGORITHM);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "Java runtime lacks " + ALGORITHM + "; include the jdk.random module", e);
    }
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
