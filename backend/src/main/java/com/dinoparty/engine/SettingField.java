package com.dinoparty.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One host setting of a game: its key, label, default and the values it accepts. The wire form is
 * {@code SettingField} (api-contracts §2.6); {@link #check} is what the room validates against.
 */
public sealed interface SettingField
    permits SettingField.IntField,
        SettingField.BoolField,
        SettingField.EnumField,
        SettingField.ListField {

  String key();

  String label();

  /** Wire type: {@code int}, {@code bool}, {@code enum} or {@code list}. */
  String type();

  Object defaultValue();

  /** Returns an error message, or null when {@code value} is acceptable. */
  String check(Object value);

  /**
   * Whole number from {@code min} to {@code max}; {@code autoValue}, when set, is also accepted and
   * means "automatic" (Telephone {@code entriesPerChain} 0).
   */
  record IntField(
      String key, String label, int min, int max, Integer autoValue, Integer defaultValue)
      implements SettingField {

    public IntField {
      requireKeyAndLabel(key, label);
      Objects.requireNonNull(defaultValue, "defaultValue");
      if (max < min) {
        throw new IllegalArgumentException("Invalid bounds for " + key);
      }
    }

    public static IntField of(String key, String label, int min, int max, int defaultValue) {
      return new IntField(key, label, min, max, null, defaultValue);
    }

    public static IntField withAuto(
        String key, String label, int min, int max, int autoValue, int defaultValue) {
      return new IntField(key, label, min, max, autoValue, defaultValue);
    }

    @Override
    public String type() {
      return "int";
    }

    @Override
    public String check(Object value) {
      boolean whole = value instanceof Integer || value instanceof Long;
      long n = whole ? ((Number) value).longValue() : 0;
      boolean ok = whole && ((n >= min && n <= max) || (autoValue != null && n == autoValue));
      if (ok) {
        return null;
      }
      return autoValue == null
          ? "must be a whole number from " + min + " to " + max
          : "must be " + autoValue + " (automatic) or a whole number from " + min + " to " + max;
    }
  }

  /** {@code true}/{@code false}, or only the values in {@code options} when it is narrower. */
  record BoolField(String key, String label, Set<Boolean> options, Boolean defaultValue)
      implements SettingField {

    private static final Set<Boolean> BOTH = Set.of(true, false);

    public BoolField {
      requireKeyAndLabel(key, label);
      Objects.requireNonNull(defaultValue, "defaultValue");
      options = Set.copyOf(options);
      if (options.isEmpty()) {
        throw new IllegalArgumentException("No accepted value for " + key);
      }
    }

    public static BoolField of(String key, String label, boolean defaultValue) {
      return new BoolField(key, label, BOTH, defaultValue);
    }

    /** Accepts exactly one value; for keys whose other value has no defined behavior yet. */
    public static BoolField only(String key, String label, boolean accepted) {
      return new BoolField(key, label, Set.of(accepted), accepted);
    }

    /** True when not every boolean is accepted (the wire form then lists {@code options}). */
    public boolean restricted() {
      return !options.equals(BOTH);
    }

    @Override
    public String type() {
      return "bool";
    }

    @Override
    public String check(Object value) {
      if (!restricted()) {
        return value instanceof Boolean ? null : "must be true or false";
      }
      return value instanceof Boolean b && options.contains(b)
          ? null
          : "must be " + options.iterator().next();
    }
  }

  /** One of {@code options}, listed in display order. */
  record EnumField(String key, String label, List<String> options, String defaultValue)
      implements SettingField {

    public EnumField {
      requireKeyAndLabel(key, label);
      options = List.copyOf(options);
      if (options.isEmpty() || new HashSet<>(options).size() != options.size()) {
        throw new IllegalArgumentException("Options of " + key + " must be distinct");
      }
    }

    @Override
    public String type() {
      return "enum";
    }

    @Override
    public String check(Object value) {
      return value instanceof String s && options.contains(s)
          ? null
          : "must be one of " + String.join(", ", options.stream().sorted().toList());
    }
  }

  /** How the items of a {@link ListField} must look. */
  enum ItemFormat {
    /** A word-category key, {@code [a-z][a-z0-9-]{0,39}}. */
    CATEGORY_KEY("category-key"),
    /** A lowercase canonical UUID string. */
    UUID("uuid");

    private static final Pattern CATEGORY = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    private final String wire;

    ItemFormat(String wire) {
      this.wire = wire;
    }

    public String wire() {
      return wire;
    }

    boolean matches(String item) {
      return switch (this) {
        case CATEGORY_KEY -> CATEGORY.matcher(item).matches();
        case UUID -> {
          try {
            yield java.util.UUID.fromString(item).toString().equals(item);
          } catch (IllegalArgumentException e) {
            yield false;
          }
        }
      };
    }
  }

  /**
   * Up to {@code maxItems} distinct strings of {@code format}; {@code exclusiveOption}, when set,
   * may only appear on its own. Whether an item exists (active category, pack of the room) is
   * checked at game start, because that can change after the settings were saved.
   *
   * @param itemName plural noun of an item, used in the error message only
   */
  record ListField(
      String key,
      String label,
      ItemFormat format,
      int maxItems,
      String exclusiveOption,
      String itemName,
      List<String> defaultValue)
      implements SettingField {

    public ListField {
      requireKeyAndLabel(key, label);
      Objects.requireNonNull(format, "format");
      Objects.requireNonNull(itemName, "itemName");
      if (maxItems < 1) {
        throw new IllegalArgumentException("maxItems of " + key + " must be positive");
      }
      defaultValue = List.copyOf(defaultValue);
    }

    @Override
    public String type() {
      return "list";
    }

    @Override
    public String check(Object value) {
      String problem =
          "must be a list of at most "
              + maxItems
              + " distinct "
              + itemName
              + (exclusiveOption == null ? "" : ", or [\"" + exclusiveOption + "\"]");
      if (!(value instanceof List<?> list) || list.size() > maxItems) {
        return problem;
      }
      Set<Object> seen = new HashSet<>();
      for (Object o : list) {
        if (!(o instanceof String item) || !format.matches(item) || !seen.add(item)) {
          return problem;
        }
      }
      return exclusiveOption != null && list.size() > 1 && list.contains(exclusiveOption)
          ? problem
          : null;
    }
  }

  private static void requireKeyAndLabel(String key, String label) {
    if (key == null || key.isBlank() || label == null || label.isBlank()) {
      throw new IllegalArgumentException("A setting needs a key and a label");
    }
  }
}
