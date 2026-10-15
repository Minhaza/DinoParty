package com.dinoparty.room;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.DomainException.FieldViolation;
import com.dinoparty.common.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Per-game settings bounds from docs/game-rules.md. Defaults live in {@code
 * game_definition.default_settings}; this class only validates what a host supplies.
 */
final class SettingsSchema {

  private sealed interface Rule {
    /** Returns an error message, or null when {@code value} is acceptable. */
    String check(Object value);
  }

  private record IntRule(int min, int max) implements Rule {
    @Override
    public String check(Object value) {
      boolean ok = (value instanceof Integer || value instanceof Long);
      if (!ok || ((Number) value).longValue() < min || ((Number) value).longValue() > max) {
        return "must be a whole number from " + min + " to " + max;
      }
      return null;
    }
  }

  private record BoolRule() implements Rule {
    @Override
    public String check(Object value) {
      return value instanceof Boolean ? null : "must be true or false";
    }
  }

  private record EnumRule(Set<String> options) implements Rule {
    @Override
    public String check(Object value) {
      return value instanceof String s && options.contains(s)
          ? null
          : "must be one of " + String.join(", ", options.stream().sorted().toList());
    }
  }

  /**
   * Distinct category keys ({@code [a-z][a-z0-9-]{0,39}}), at most {@code max}; {@code random-mix}
   * only on its own. Whether a key is an active category is checked at game start, because the
   * catalog can change after the settings were saved.
   */
  private record CategoryListRule(int max) implements Rule {
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    @Override
    public String check(Object value) {
      String problem =
          "must be a list of at most " + max + " distinct category keys, or [\"random-mix\"]";
      if (!(value instanceof List<?> list) || list.size() > max) {
        return problem;
      }
      Set<Object> seen = new HashSet<>();
      for (Object o : list) {
        if (!(o instanceof String key) || !KEY.matcher(key).matches() || !seen.add(key)) {
          return problem;
        }
      }
      return list.size() > 1 && list.contains("random-mix") ? problem : null;
    }
  }

  /** Distinct canonical UUID strings, at most {@code max}; existence is checked at game start. */
  private record UuidListRule(int max) implements Rule {
    @Override
    public String check(Object value) {
      String problem = "must be a list of at most " + max + " distinct word pack ids";
      if (!(value instanceof List<?> list) || list.size() > max) {
        return problem;
      }
      Set<UUID> seen = new HashSet<>();
      for (Object o : list) {
        if (!(o instanceof String text)) {
          return problem;
        }
        try {
          UUID id = UUID.fromString(text);
          if (!id.toString().equals(text) || !seen.add(id)) {
            return problem;
          }
        } catch (IllegalArgumentException e) {
          return problem;
        }
      }
      return null;
    }
  }

  private static final Map<String, Map<String, Rule>> SCHEMAS =
      Map.of(
          "draw-guess",
          Map.of(
              "cycles", new IntRule(1, 5),
              "drawSeconds", new IntRule(30, 180),
              "wordChoiceSeconds", new IntRule(5, 30),
              "hintReveal", new BoolRule(),
              "difficulty", new EnumRule(Set.of("easy", "medium", "hard", "mixed")),
              "wordSource", new EnumRule(Set.of("built-in", "custom", "combined")),
              "wordCategories", new CategoryListRule(20),
              "wordPackIds", new UuidListRule(10)),
          "telephone",
          Map.of(
              "promptSeconds", new IntRule(20, 120),
              "drawSeconds", new IntRule(30, 180),
              "describeSeconds", new IntRule(20, 90),
              "allowSkipTimeout", new BoolRule()));

  private SettingsSchema() {}

  /**
   * Validates {@code supplied} for {@code gameKey} and returns it layered over {@code defaults}.
   * All problems are reported together as field errors named {@code settings.<key>}.
   */
  static Map<String, Object> resolve(
      String gameKey, Map<String, Object> defaults, Map<String, Object> supplied) {
    Map<String, Rule> rules = SCHEMAS.getOrDefault(gameKey, Map.of());
    Map<String, Object> merged = new LinkedHashMap<>(defaults);
    if (supplied == null) {
      return merged;
    }
    List<FieldViolation> violations = new ArrayList<>();
    supplied.forEach(
        (key, value) -> {
          Rule rule = rules.get(key);
          if (rule == null) {
            violations.add(new FieldViolation("settings." + key, "is not a setting of this game"));
            return;
          }
          String problem = rule.check(value);
          if (problem != null) {
            violations.add(new FieldViolation("settings." + key, problem));
          } else {
            merged.put(key, value);
          }
        });
    if (!violations.isEmpty()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "The request is invalid.", violations);
    }
    return merged;
  }
}
