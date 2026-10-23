package com.dinoparty.words;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Guess sanitizing, normalization and exact matching shared by every guessing game ([DG-4],
 * [DI-11]). Pure functions; game-specific rules such as Draw &amp; Guess's "close" guesses stay in
 * the game.
 */
public final class GuessNormalizer {

  /** Control characters and invisible format characters (bidi overrides, zero-width marks). */
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}]");

  private static final Pattern MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern SEPARATORS = Pattern.compile("[-_\\p{Z}\\s]");
  private static final Pattern NOT_LETTER_DIGIT_SPACE = Pattern.compile("[^\\p{L}\\p{N} ]");
  private static final Pattern SPACES = Pattern.compile(" +");

  private GuessNormalizer() {}

  /** Raw text as it may be shown to others: control characters stripped, then trimmed. */
  public static String sanitize(String raw) {
    return CONTROL.matcher(raw).replaceAll("").strip();
  }

  /** Length in characters (code points), the unit of the guess length rules. */
  public static int length(String text) {
    return text.codePointCount(0, text.length());
  }

  /**
   * NFKC, accents folded (NFD minus combining marks), lowercase, hyphens/underscores to spaces,
   * everything but letters, digits and spaces removed, whitespace collapsed and trimmed.
   */
  public static String normalize(String text) {
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

  public static String spaceless(String normalized) {
    return normalized.replace(" ", "");
  }

  /** Equal normalized texts, or equal once all spaces are removed ("ice cream" = "icecream"). */
  public static boolean isCorrect(String normalizedGuess, String normalizedWord) {
    return normalizedGuess.equals(normalizedWord)
        || spaceless(normalizedGuess).equals(spaceless(normalizedWord));
  }
}
