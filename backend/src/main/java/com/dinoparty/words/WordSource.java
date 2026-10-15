package com.dinoparty.words;

import java.util.Locale;
import java.util.Optional;

/** Where a game's words come from (room setting {@code wordSource}). */
public enum WordSource {
  /** The built-in catalog, filtered by categories and difficulty. */
  BUILT_IN,
  /** Only the selected custom word packs; category and difficulty filters do not apply. */
  CUSTOM,
  /** Both, merged and de-duplicated (built-in words first). */
  COMBINED;

  /** The setting value: {@code built-in}, {@code custom} or {@code combined}. */
  public String key() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  public static Optional<WordSource> fromKey(String key) {
    for (WordSource s : values()) {
      if (s.key().equals(key)) {
        return Optional.of(s);
      }
    }
    return Optional.empty();
  }

  public boolean usesBuiltIn() {
    return this != CUSTOM;
  }

  public boolean usesCustom() {
    return this != BUILT_IN;
  }
}
