package com.dinoparty.wordpacks;

/** Server-side limits of word packs (api-contracts §2.5). Starting values, enforced everywhere. */
public final class WordPackLimits {

  public static final int MAX_FILE_BYTES = 1024 * 1024;

  /** Bounds parser work and memory independently of the byte limit (many tiny rows). */
  public static final int MAX_FILE_ROWS = 10_000;

  public static final int MAX_WORDS_PER_PACK = 1_000;
  public static final int MAX_PACKS_PER_ROOM = 20;
  public static final int MAX_ADD_BATCH = 100;

  public static final int MAX_WORD_CODE_POINTS = 80;
  public static final int MAX_NAME_CODE_POINTS = 80;
  public static final int MAX_DESCRIPTION_CODE_POINTS = 280;
  public static final int MAX_CATEGORY_CODE_POINTS = 40;

  public static final int PREVIEW_ROWS = 50;
  public static final int MAX_REPORTED_ISSUES = 100;

  public static final String DEFAULT_CATEGORY = "Custom";

  private WordPackLimits() {}
}
