package com.dinoparty.games.drawguess;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Guess sanitizing, normalization and matching ([DG-4], [DG-5]). Pure functions. */
final class GuessMatcher {

  static final int MAX_LENGTH = 80;
  static final int CLOSE_MIN_LETTERS = 6;

  /** Control characters and invisible format characters (bidi overrides, zero-width marks). */
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}]");

  private static final Pattern MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern SEPARATORS = Pattern.compile("[-_\\p{Z}\\s]");
  private static final Pattern NOT_LETTER_DIGIT_SPACE = Pattern.compile("[^\\p{L}\\p{N} ]");
  private static final Pattern SPACES = Pattern.compile(" +");

  private GuessMatcher() {}

  /** Raw text as it may be shown to others: control characters stripped, then trimmed. */
  static String sanitize(String raw) {
    return CONTROL.matcher(raw).replaceAll("").strip();
  }

  /** Length in characters (code points), the unit of the 1-80 rule. */
  static int length(String text) {
    return text.codePointCount(0, text.length());
  }

  /**
   * NFKC, accents folded (NFD minus combining marks), lowercase, hyphens/underscores to spaces,
   * everything but letters, digits and spaces removed, whitespace collapsed and trimmed.
   */
  static String normalize(String text) {
    String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
    s = Normalizer.normalize(s, Normalizer.Form.NFD);
    s = MARKS.matcher(s).replaceAll("");
    s = s.toLowerCase(Locale.ROOT);
    // Lowercasing can produce new combining marks (e.g. dotted capital I); fold them too.
    s = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
    s = SEPARATORS.matcher(s).replaceAll(" ");
    s = NOT_LETTER_DIGIT_SPACE.matcher(s).replaceAll("");
    return SPACES.matcher(s).replaceAll(" ").strip();
  }

  static String spaceless(String normalized) {
    return normalized.replace(" ", "");
  }

  /** Equal normalized texts, or equal once all spaces are removed ("ice cream" = "icecream"). */
  static boolean isCorrect(String normalizedGuess, String normalizedWord) {
    return normalizedGuess.equals(normalizedWord)
        || spaceless(normalizedGuess).equals(spaceless(normalizedWord));
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
