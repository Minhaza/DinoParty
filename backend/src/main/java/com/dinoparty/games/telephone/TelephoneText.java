package com.dinoparty.games.telephone;

import java.util.regex.Pattern;

/** Prompt and description text of [TP-14]: stripped of invisible characters, 1-140 code points. */
final class TelephoneText {

  static final int MAX_LENGTH = 140;

  /** Placeholder entry for a description nobody wrote ([TP-5]). */
  static final String NO_DESCRIPTION = "(no description)";

  /** Control characters and invisible format characters (bidi overrides, zero-width marks). */
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}]");

  private TelephoneText() {}

  /** Raw text as it may be shown to others: control characters stripped, then trimmed. */
  static String sanitize(String raw) {
    return CONTROL.matcher(raw).replaceAll("").strip();
  }

  /** Length in code points, the unit of the 1-140 rule. */
  static int length(String text) {
    return text.codePointCount(0, text.length());
  }
}
