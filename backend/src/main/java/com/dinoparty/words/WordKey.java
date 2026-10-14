package com.dinoparty.words;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Word identity and shape for the word pool. Two words are the same word when their keys are equal;
 * the key is the [DG-4] normalized form without spaces, so two words are duplicates exactly when a
 * guess that is correct for one is correct for the other ("ice-cream" = "ice cream" = "icecream",
 * "Café" = "cafe").
 */
public final class WordKey {

  static final int MIN_LENGTH = 3;
  static final int MAX_LENGTH = 20;

  /** [DG-15] shape: letters (any script) with single inner spaces or hyphens. */
  private static final Pattern SHAPE = Pattern.compile("\\p{L}+(?:[ -]\\p{L}+)*");

  private static final Pattern MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern NOT_LETTER_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]");

  private WordKey() {}

  /** NFKC, accents folded, lowercase, everything but letters and digits removed. */
  public static String of(String text) {
    String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
    s = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
    s = s.toLowerCase(Locale.ROOT);
    // Lowercasing can produce new combining marks (e.g. dotted capital I); fold them too.
    s = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
    return NOT_LETTER_DIGIT.matcher(s).replaceAll("");
  }

  /**
   * The text a game shows (NFC, trimmed), or null when it is not a usable [DG-15] word: 3-20
   * characters of letters with single inner spaces or hyphens.
   */
  static String usable(String text) {
    if (text == null) {
      return null;
    }
    String s = Normalizer.normalize(text, Normalizer.Form.NFC).strip();
    int length = s.codePointCount(0, s.length());
    if (length < MIN_LENGTH || length > MAX_LENGTH || !SHAPE.matcher(s).matches()) {
      return null;
    }
    return s;
  }
}
