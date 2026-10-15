package com.dinoparty.words;

/** Persisted difficulty of a catalog word. {@code MIXED} is a filter ({@link DifficultyFilter}). */
public enum WordDifficulty {
  EASY("Easy"),
  MEDIUM("Medium"),
  HARD("Hard");

  private final String label;

  WordDifficulty(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
