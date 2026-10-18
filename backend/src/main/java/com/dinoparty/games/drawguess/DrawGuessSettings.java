package com.dinoparty.games.drawguess;

import com.dinoparty.engine.SettingField.BoolField;
import com.dinoparty.engine.SettingField.EnumField;
import com.dinoparty.engine.SettingField.IntField;
import com.dinoparty.engine.SettingField.ItemFormat;
import com.dinoparty.engine.SettingField.ListField;
import com.dinoparty.engine.SettingsSchema;
import com.dinoparty.words.DifficultyFilter;
import com.dinoparty.words.WordCatalogTypes;
import com.dinoparty.words.WordSelection;
import com.dinoparty.words.WordSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The host's settings of one session ([DG] settings table) and their {@link #SCHEMA}, the one
 * declaration of their keys, bounds and defaults (the room validates against it, the game API
 * publishes it). A missing or unusable value falls back to the default instead of failing the game.
 * The catalog row ({@code V2}, word keys {@code V7}) keeps a non-authoritative copy of the defaults
 * (guarded by {@code DrawGuessIT}).
 *
 * @param words the word selection; whether it gives enough words is checked at start ([DG-15])
 */
public record DrawGuessSettings(
    int cycles, int drawSeconds, int wordChoiceSeconds, boolean hintReveal, WordSelection words) {

  public record IntBound(int min, int max, int defaultValue) {}

  public static final Map<String, IntBound> INT_BOUNDS =
      Map.of(
          "cycles", new IntBound(1, 5, 2),
          "drawSeconds", new IntBound(30, 180, 80),
          "wordChoiceSeconds", new IntBound(5, 30, 15));

  public static final boolean DEFAULT_HINT_REVEAL = true;
  public static final String DEFAULT_DIFFICULTY = "mixed";
  public static final String DEFAULT_WORD_SOURCE = WordSource.BUILT_IN.key();

  /**
   * Setting value per difficulty filter: {@code easy}, {@code medium}, {@code hard}, {@code mixed}.
   */
  public static final Map<String, DifficultyFilter> DIFFICULTIES =
      Map.of(
          "easy", DifficultyFilter.EASY,
          "medium", DifficultyFilter.MEDIUM,
          "hard", DifficultyFilter.HARD,
          "mixed", DifficultyFilter.MIXED);

  /** Most entries of {@code wordCategories} (the catalog has nine categories). */
  public static final int MAX_CATEGORIES = 20;

  /** Most entries of {@code wordPackIds}. */
  public static final int MAX_PACKS = 10;

  /** Host settings in display order; the labels are the lobby's. */
  public static final SettingsSchema SCHEMA =
      new SettingsSchema(
          List.of(
              intField("cycles", "Turns per player"),
              intField("drawSeconds", "Drawing time"),
              intField("wordChoiceSeconds", "Word choice time"),
              BoolField.of("hintReveal", "Letter hints", DEFAULT_HINT_REVEAL),
              new EnumField(
                  "difficulty",
                  "Difficulty",
                  List.of("easy", "medium", "hard", "mixed"),
                  DEFAULT_DIFFICULTY),
              new EnumField(
                  "wordSource",
                  "Word source",
                  Arrays.stream(WordSource.values()).map(WordSource::key).toList(),
                  DEFAULT_WORD_SOURCE),
              new ListField(
                  "wordCategories",
                  "Word categories",
                  ItemFormat.CATEGORY_KEY,
                  MAX_CATEGORIES,
                  WordCatalogTypes.RANDOM_MIX,
                  "category keys",
                  List.of(WordCatalogTypes.RANDOM_MIX)),
              new ListField(
                  "wordPackIds",
                  "Custom word packs",
                  ItemFormat.UUID,
                  MAX_PACKS,
                  null,
                  "word pack ids",
                  List.of())));

  /** Every setting with its default ({@link #SCHEMA}). */
  public static Map<String, Object> defaults() {
    return SCHEMA.defaults();
  }

  private static IntField intField(String key, String label) {
    IntBound bound = INT_BOUNDS.get(key);
    return IntField.of(key, label, bound.min(), bound.max(), bound.defaultValue());
  }

  static DrawGuessSettings from(Map<String, Object> settings) {
    Map<String, Object> s = settings == null ? Map.of() : settings;
    return new DrawGuessSettings(
        integer(s, "cycles"),
        integer(s, "drawSeconds"),
        integer(s, "wordChoiceSeconds"),
        s.get("hintReveal") instanceof Boolean b ? b : DEFAULT_HINT_REVEAL,
        new WordSelection(
            source(s.get("wordSource")),
            categories(s.get("wordCategories")),
            difficulty(s.get("difficulty")),
            packIds(s.get("wordPackIds"))));
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

  private static DifficultyFilter difficulty(Object value) {
    return DIFFICULTIES.getOrDefault(
        value instanceof String s ? s : DEFAULT_DIFFICULTY, DifficultyFilter.MIXED);
  }

  private static WordSource source(Object value) {
    return value instanceof String s
        ? WordSource.fromKey(s).orElse(WordSource.BUILT_IN)
        : WordSource.BUILT_IN;
  }

  /** Category keys as given; an unusable value means Random Mix (an empty list). */
  private static List<String> categories(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> keys = new ArrayList<>();
    for (Object o : list) {
      if (!(o instanceof String key)) {
        return List.of();
      }
      keys.add(key);
    }
    return keys;
  }

  /** Pack ids as given; an unusable value means no pack (the start check then reports it). */
  private static List<UUID> packIds(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<UUID> ids = new ArrayList<>();
    for (Object o : list) {
      if (!(o instanceof String text)) {
        return List.of();
      }
      try {
        ids.add(UUID.fromString(text));
      } catch (IllegalArgumentException e) {
        return List.of();
      }
    }
    return ids;
  }
}
