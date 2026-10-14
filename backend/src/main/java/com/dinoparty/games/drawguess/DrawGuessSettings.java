package com.dinoparty.games.drawguess;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The host's settings of one session ([DG] settings table). The room module already validated them
 * against its schema; a missing or unusable value falls back to the default instead of failing the
 * game. The bounds and defaults here must equal {@code room.SettingsSchema} and the {@code V2}
 * catalog row (guarded by tests).
 */
public record DrawGuessSettings(
    int cycles, int drawSeconds, int wordChoiceSeconds, boolean hintReveal, Difficulty difficulty) {

  public enum Difficulty {
    EASY,
    MIXED,
    HARD
  }

  public record IntBound(int min, int max, int defaultValue) {}

  public static final Map<String, IntBound> INT_BOUNDS =
      Map.of(
          "cycles", new IntBound(1, 5, 2),
          "drawSeconds", new IntBound(30, 180, 80),
          "wordChoiceSeconds", new IntBound(5, 30, 15));

  public static final boolean DEFAULT_HINT_REVEAL = true;
  public static final String DEFAULT_DIFFICULTY = "mixed";

  /** The catalog defaults ({@code game_definition.default_settings} of {@code draw-guess}). */
  public static Map<String, Object> defaults() {
    Map<String, Object> defaults = new LinkedHashMap<>();
    defaults.put("cycles", INT_BOUNDS.get("cycles").defaultValue());
    defaults.put("drawSeconds", INT_BOUNDS.get("drawSeconds").defaultValue());
    defaults.put("wordChoiceSeconds", INT_BOUNDS.get("wordChoiceSeconds").defaultValue());
    defaults.put("hintReveal", DEFAULT_HINT_REVEAL);
    defaults.put("difficulty", DEFAULT_DIFFICULTY);
    return defaults;
  }

  static DrawGuessSettings from(Map<String, Object> settings) {
    Map<String, Object> s = settings == null ? Map.of() : settings;
    return new DrawGuessSettings(
        integer(s, "cycles"),
        integer(s, "drawSeconds"),
        integer(s, "wordChoiceSeconds"),
        s.get("hintReveal") instanceof Boolean b ? b : DEFAULT_HINT_REVEAL,
        difficulty(s.get("difficulty")));
  }

  private static int integer(Map<String, Object> settings, String key) {
    IntBound bound = INT_BOUNDS.get(key);
    if (settings.get(key) instanceof Number n
        && n.doubleValue() == n.longValue()
        && n.longValue() >= bound.min()
        && n.longValue() <= bound.max()) {
      return n.intValue();
    }
    return bound.defaultValue();
  }

  private static Difficulty difficulty(Object value) {
    return switch (value instanceof String s ? s : DEFAULT_DIFFICULTY) {
      case "easy" -> Difficulty.EASY;
      case "hard" -> Difficulty.HARD;
      default -> Difficulty.MIXED;
    };
  }
}
