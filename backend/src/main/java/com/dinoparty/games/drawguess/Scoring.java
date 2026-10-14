package com.dinoparty.games.drawguess;

/** Integer scoring formulas of [DG-12] and [DG-13]; no floating point anywhere. */
final class Scoring {

  static final int GUESSER_FLOOR = 50;
  static final int ORDER_PENALTY = 25;
  static final int ALL_GUESSED_BONUS = 50;

  private Scoring() {}

  /**
   * {@code max(50, roundHalfUp(100 + 400 × remMs / (T × 1000)) − 25 × order)}; {@code remMs} is
   * clamped to {@code [0, T × 1000]}.
   */
  static int guesserPoints(long remMs, int drawSeconds, int order) {
    long den = drawSeconds * 1000L;
    long rem = Math.max(0, Math.min(remMs, den));
    long base = roundHalfUp(100 * den + 400 * rem, den);
    return (int) Math.max(GUESSER_FLOOR, base - (long) ORDER_PENALTY * order);
  }

  /**
   * {@code roundHalfUp(100 × correct / eligible)}, +50 when everyone eligible solved; 0 if none.
   */
  static int drawerPoints(int correct, int eligible) {
    if (correct <= 0 || eligible <= 0) {
      return 0;
    }
    int points = (int) roundHalfUp(100L * correct, eligible);
    return correct >= eligible ? points + ALL_GUESSED_BONUS : points;
  }

  /** {@code floor(num / den + 0.5)} for non-negative values, as {@code (2·num + den) / (2·den)}. */
  static long roundHalfUp(long num, long den) {
    return (2 * num + den) / (2 * den);
  }
}
