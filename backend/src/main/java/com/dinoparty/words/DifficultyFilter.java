package com.dinoparty.words;

import com.dinoparty.common.DomainException;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** A difficulty selection: one stored difficulty, or the {@code MIXED} preset (all of them). */
public enum DifficultyFilter {
  EASY("Easy", EnumSet.of(WordDifficulty.EASY)),
  MEDIUM("Medium", EnumSet.of(WordDifficulty.MEDIUM)),
  HARD("Hard", EnumSet.of(WordDifficulty.HARD)),
  MIXED("Mixed", EnumSet.allOf(WordDifficulty.class));

  private final String label;
  private final Set<WordDifficulty> difficulties;

  DifficultyFilter(String label, Set<WordDifficulty> difficulties) {
    this.label = label;
    this.difficulties = Set.copyOf(difficulties);
  }

  public String label() {
    return label;
  }

  /** The stored difficulties this selection matches. */
  public Set<WordDifficulty> difficulties() {
    return difficulties;
  }

  /** Case-insensitive; {@code null} or blank means {@code MIXED}. */
  public static DifficultyFilter parse(String value) {
    if (value == null || value.isBlank()) {
      return MIXED;
    }
    String key = value.strip().toUpperCase(Locale.ROOT);
    for (DifficultyFilter f : values()) {
      if (f.name().equals(key)) {
        return f;
      }
    }
    throw DomainException.invalid("difficulty", "must be one of EASY, MEDIUM, HARD, MIXED");
  }
}
