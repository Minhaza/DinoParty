package com.dinoparty.wordpacks;

import com.dinoparty.wordpacks.WordPackTypes.RowProblem;
import com.dinoparty.words.WordDifficulty;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Normalization and validation of one pack word and its metadata (api-contracts §2.5). Pure, so the
 * REST add path and both import formats share one definition of "valid".
 */
final class WordRules {

  /** A validated word: display text, duplicate key, and resolved metadata. */
  record Word(String text, String key, String category, WordDifficulty difficulty) {}

  /** Either a {@link Word} or the reason there is none. */
  record Checked(Word word, RowProblem problem) {}

  /** Either a cleaned string or the reason there is none. */
  record Cleaned(String value, RowProblem problem) {}

  private WordRules() {}

  static Checked check(String text, String category, String difficulty) {
    Cleaned t = clean(text, WordPackLimits.MAX_WORD_CODE_POINTS, 1, RowProblem.BLANK_WORD);
    if (t.problem() != null) {
      return new Checked(null, t.problem());
    }
    WordDifficulty level = WordDifficulty.MEDIUM;
    if (difficulty != null && !difficulty.isBlank()) {
      try {
        level = WordDifficulty.valueOf(difficulty.strip().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        return new Checked(null, RowProblem.INVALID_DIFFICULTY);
      }
    }
    String cat = WordPackLimits.DEFAULT_CATEGORY;
    if (category != null && !category.isBlank()) {
      Cleaned c =
          clean(category, WordPackLimits.MAX_CATEGORY_CODE_POINTS, 1, RowProblem.INVALID_CATEGORY);
      if (c.problem() != null) {
        return new Checked(null, RowProblem.INVALID_CATEGORY);
      }
      cat = c.value();
    }
    return new Checked(new Word(t.value(), key(t.value()), cat, level), null);
  }

  /**
   * Case-insensitive duplicate key: NFKC, then case-folded through upper case so {@code ß} = {@code
   * SS}. Accents are kept: {@code café} and {@code cafe} are different words here (the game pool
   * applies the stricter [DG-4] equivalence when it is built).
   */
  static String key(String text) {
    String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
    return s.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
  }

  /**
   * Trims, normalizes to NFC and collapses whitespace runs to one space. Rejects control characters
   * (a tab counts as whitespace), invisible format characters other than ZWJ/ZWNJ, unassigned,
   * surrogate and private-use code points, and {@code <} {@code >}.
   */
  static Cleaned clean(String raw, int maxCodePoints, int minCodePoints, RowProblem emptyProblem) {
    if (raw == null) {
      return new Cleaned(null, emptyProblem);
    }
    // A cheap bound before normalization: no valid value is more than 4 chars per code point.
    if (raw.length() > maxCodePoints * 8) {
      return new Cleaned(null, RowProblem.TOO_LONG);
    }
    String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
    StringBuilder out = new StringBuilder(nfc.length());
    boolean pendingSpace = false;
    int count = 0;
    for (int i = 0; i < nfc.length(); ) {
      int cp = nfc.codePointAt(i);
      i += Character.charCount(cp);
      if (isSpace(cp)) {
        pendingSpace = out.length() > 0;
        continue;
      }
      if (isForbidden(cp)) {
        return new Cleaned(null, RowProblem.INVALID_CHARACTER);
      }
      if (pendingSpace) {
        out.append(' ');
        count++;
        pendingSpace = false;
      }
      out.appendCodePoint(cp);
      count++;
    }
    if (count < minCodePoints) {
      return new Cleaned(null, emptyProblem);
    }
    if (count > maxCodePoints) {
      return new Cleaned(null, RowProblem.TOO_LONG);
    }
    return new Cleaned(out.toString(), null);
  }

  static boolean isSpace(int cp) {
    return Character.isWhitespace(cp) || Character.getType(cp) == Character.SPACE_SEPARATOR;
  }

  private static boolean isForbidden(int cp) {
    if (cp == '<' || cp == '>') {
      return true;
    }
    return switch (Character.getType(cp)) {
      case Character.CONTROL, Character.UNASSIGNED, Character.SURROGATE, Character.PRIVATE_USE ->
          true;
      case Character.FORMAT -> cp != 0x200C && cp != 0x200D;
      default -> false;
    };
  }
}
