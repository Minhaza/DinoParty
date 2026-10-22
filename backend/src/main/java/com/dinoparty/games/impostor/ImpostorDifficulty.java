package com.dinoparty.games.impostor;

import com.dinoparty.words.DifficultyFilter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The {@code difficulty} setting of both impostor games (D60). Unlike Draw &amp; Guess, {@code
 * mixed} is EASY and MEDIUM only: hard words make the category clue useless.
 */
enum ImpostorDifficulty {
  EASY(List.of(DifficultyFilter.EASY)),
  MEDIUM(List.of(DifficultyFilter.MEDIUM)),
  MIXED(List.of(DifficultyFilter.EASY, DifficultyFilter.MEDIUM));

  private final List<DifficultyFilter> filters;

  ImpostorDifficulty(List<DifficultyFilter> filters) {
    this.filters = filters;
  }

  /** The catalog difficulties this setting draws from. */
  List<DifficultyFilter> filters() {
    return filters;
  }

  /** The setting value: {@code easy}, {@code medium} or {@code mixed}. */
  String key() {
    return name().toLowerCase(Locale.ROOT);
  }

  static Optional<ImpostorDifficulty> fromKey(Object value) {
    for (ImpostorDifficulty d : values()) {
      if (d.key().equals(value)) {
        return Optional.of(d);
      }
    }
    return Optional.empty();
  }
}
