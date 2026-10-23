package com.dinoparty.games.drawguess;

import com.dinoparty.words.GuessNormalizer;

/**
 * Draw &amp; Guess guess rules ([DG-4], [DG-5]). Sanitizing, normalization and exact matching are
 * the shared {@link GuessNormalizer}; the "close" rule and the length limit are Draw &amp; Guess's
 * own. Pure functions.
 */
final class GuessMatcher {

  static final int MAX_LENGTH = 80;
  static final int CLOSE_MIN_LETTERS = 6;

  private GuessMatcher() {}

  static String sanitize(String raw) {
    return GuessNormalizer.sanitize(raw);
  }

  static int length(String text) {
    return GuessNormalizer.length(text);
  }

  static String normalize(String text) {
    return GuessNormalizer.normalize(text);
  }

  static String spaceless(String normalized) {
    return GuessNormalizer.spaceless(normalized);
  }

  static boolean isCorrect(String normalizedGuess, String normalizedWord) {
    return GuessNormalizer.isCorrect(normalizedGuess, normalizedWord);
  }

  /** Words of at least six letters: a guess at Levenshtein distance exactly 1 (space-free). */
  static boolean isClose(String normalizedGuess, String normalizedWord) {
    String word = spaceless(normalizedWord);
    if (word.length() < CLOSE_MIN_LETTERS) {
      return false;
    }
    return oneEditApart(spaceless(normalizedGuess), word);
  }

  /** True when exactly one insertion, deletion or substitution turns {@code a} into {@code b}. */
  static boolean oneEditApart(String a, String b) {
    if (a.equals(b) || Math.abs(a.length() - b.length()) > 1) {
      return false;
    }
    String shorter = a.length() <= b.length() ? a : b;
    String longer = shorter == a ? b : a;
    int i = 0;
    while (i < shorter.length() && shorter.charAt(i) == longer.charAt(i)) {
      i++;
    }
    if (shorter.length() == longer.length()) {
      return shorter.substring(i + 1).equals(longer.substring(i + 1));
    }
    return shorter.substring(i).equals(longer.substring(i + 1));
  }
}
