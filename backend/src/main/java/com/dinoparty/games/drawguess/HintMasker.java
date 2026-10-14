package com.dinoparty.games.drawguess;

import com.dinoparty.common.RandomSource;

/**
 * The public hint of [DG-3]: one character per word character, {@code _} for a hidden letter,
 * spaces and hyphens as they are. Reveals never exceed ⌈letters/2⌉ and never pick a separator.
 */
final class HintMasker {

  static final char HIDDEN = '_';

  private HintMasker() {}

  static String initial(String word) {
    StringBuilder hint = new StringBuilder(word.length());
    for (int i = 0; i < word.length(); i++) {
      char c = word.charAt(i);
      hint.append(isSeparator(c) ? c : HIDDEN);
    }
    return hint.toString();
  }

  static int letters(String word) {
    int n = 0;
    for (int i = 0; i < word.length(); i++) {
      if (!isSeparator(word.charAt(i))) {
        n++;
      }
    }
    return n;
  }

  static int maxRevealed(String word) {
    return (letters(word) + 1) / 2;
  }

  static int revealed(String hint) {
    int n = 0;
    for (int i = 0; i < hint.length(); i++) {
      char c = hint.charAt(i);
      if (c != HIDDEN && !isSeparator(c)) {
        n++;
      }
    }
    return n;
  }

  /** Reveals one random hidden letter; returns the hint unchanged if the cap is reached. */
  static String reveal(String word, String hint, RandomSource random) {
    int hidden = 0;
    for (int i = 0; i < hint.length(); i++) {
      if (hint.charAt(i) == HIDDEN) {
        hidden++;
      }
    }
    if (hidden == 0 || revealed(hint) >= maxRevealed(word)) {
      return hint;
    }
    int pick = random.nextInt(hidden);
    char[] chars = hint.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      if (chars[i] == HIDDEN && pick-- == 0) {
        chars[i] = Character.toLowerCase(word.charAt(i));
        break;
      }
    }
    return new String(chars);
  }

  private static boolean isSeparator(char c) {
    return c == ' ' || c == '-';
  }
}
