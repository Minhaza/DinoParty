package com.dinoparty.games.telephone;

import com.dinoparty.games.telephone.TelephoneState.Kind;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The host's settings of one session ([TP-2], [TP-12]). The room module already validated them
 * against its schema; a missing or unusable value falls back to the default instead of failing the
 * game. Bounds and defaults must equal {@code room.SettingsSchema} and the catalog row ({@code V2},
 * {@code V9}).
 *
 * @param entriesPerChain 0 = automatic, else 4..8 (D38)
 */
public record TelephoneSettings(
    int promptSeconds, int drawSeconds, int describeSeconds, int entriesPerChain) {

  record IntBound(int min, int max, int defaultValue) {}

  static final Map<String, IntBound> INT_BOUNDS =
      Map.of(
          "promptSeconds", new IntBound(20, 120, 60),
          "drawSeconds", new IntBound(30, 180, 90),
          "describeSeconds", new IntBound(20, 90, 45));

  static final int MIN_ENTRIES = 4;
  static final int MAX_ENTRIES = 8;

  /** The catalog defaults ({@code game_definition.default_settings} of {@code telephone}). */
  public static Map<String, Object> defaults() {
    Map<String, Object> defaults = new LinkedHashMap<>();
    INT_BOUNDS.forEach((key, bound) -> defaults.put(key, bound.defaultValue()));
    defaults.put("entriesPerChain", 0);
    defaults.put("allowSkipTimeout", true);
    return defaults;
  }

  static TelephoneSettings from(Map<String, Object> settings) {
    Map<String, Object> s = settings == null ? Map.of() : settings;
    Object entries = s.get("entriesPerChain");
    boolean validEntries =
        entries instanceof Number n
            && n.doubleValue() == n.longValue()
            && n.longValue() >= MIN_ENTRIES
            && n.longValue() <= MAX_ENTRIES;
    return new TelephoneSettings(
        integer(s, "promptSeconds"),
        integer(s, "drawSeconds"),
        integer(s, "describeSeconds"),
        validEntries ? ((Number) entries).intValue() : 0);
  }

  /** Entries per chain for a frozen roster of {@code n}: auto is n if even, else n - 1 ([TP-2]). */
  int entries(int n) {
    if (entriesPerChain == 0) {
      return n % 2 == 0 ? n : n - 1;
    }
    return Math.min(entriesPerChain, n);
  }

  int seconds(Kind kind) {
    return switch (kind) {
      case PROMPT -> promptSeconds;
      case DRAW -> drawSeconds;
      case DESCRIBE -> describeSeconds;
    };
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
}
