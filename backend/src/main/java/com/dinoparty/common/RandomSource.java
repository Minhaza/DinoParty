package com.dinoparty.common;

/** Injectable randomness so domain code never calls {@code new Random()} and tests can seed it. */
public interface RandomSource {

  /** Uniform value in {@code [0, bound)}. */
  int nextInt(int bound);

  byte[] nextBytes(int length);
}
