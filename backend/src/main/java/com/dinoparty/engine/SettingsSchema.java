package com.dinoparty.engine;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.DomainException.FieldViolation;
import com.dinoparty.common.ErrorCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The host settings a game accepts, declared by its module. It is the only source of their keys,
 * bounds and defaults: the room validates against it and the game API publishes it.
 */
public record SettingsSchema(List<SettingField> fields) {

  public static final SettingsSchema EMPTY = new SettingsSchema(List.of());

  public SettingsSchema {
    fields = List.copyOf(fields);
    Map<String, SettingField> byKey = new LinkedHashMap<>();
    for (SettingField field : fields) {
      if (byKey.putIfAbsent(field.key(), field) != null) {
        throw new IllegalArgumentException("Duplicate setting " + field.key());
      }
      String problem = field.check(field.defaultValue());
      if (problem != null) {
        throw new IllegalArgumentException("Default of " + field.key() + " " + problem);
      }
    }
  }

  public Optional<SettingField> field(String key) {
    return fields.stream().filter(f -> f.key().equals(key)).findFirst();
  }

  /** Every setting with its default, in declaration order. */
  public Map<String, Object> defaults() {
    Map<String, Object> defaults = new LinkedHashMap<>();
    fields.forEach(f -> defaults.put(f.key(), f.defaultValue()));
    return Collections.unmodifiableMap(defaults);
  }

  /**
   * Validates {@code supplied} and returns it layered over the defaults. All problems are reported
   * together as field errors named {@code settings.<key>}.
   */
  public Map<String, Object> resolve(Map<String, Object> supplied) {
    Map<String, Object> merged = new LinkedHashMap<>(defaults());
    if (supplied == null) {
      return merged;
    }
    List<FieldViolation> violations = new ArrayList<>();
    supplied.forEach(
        (key, value) -> {
          Optional<SettingField> field = field(key);
          if (field.isEmpty()) {
            violations.add(new FieldViolation("settings." + key, "is not a setting of this game"));
            return;
          }
          String problem = field.get().check(value);
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
