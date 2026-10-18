package com.dinoparty.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.engine.SettingField.BoolField;
import com.dinoparty.engine.SettingField.EnumField;
import com.dinoparty.engine.SettingField.IntField;
import com.dinoparty.engine.SettingField.ItemFormat;
import com.dinoparty.engine.SettingField.ListField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Validation of host-supplied settings against a game's declared schema (api-contracts §2.6). */
class SettingsSchemaTest {

  private static final SettingsSchema SCHEMA =
      new SettingsSchema(
          List.of(
              IntField.of("cycles", "Turns", 1, 5, 2),
              IntField.of("drawSeconds", "Drawing time", 30, 180, 80),
              IntField.withAuto("entries", "Entries", 4, 8, 0, 0),
              BoolField.of("hintReveal", "Hints", true),
              BoolField.only("skip", "Skip", true),
              new EnumField("difficulty", "Difficulty", List.of("easy", "hard", "mixed"), "mixed"),
              new ListField(
                  "categories",
                  "Categories",
                  ItemFormat.CATEGORY_KEY,
                  3,
                  "random-mix",
                  "category keys",
                  List.of("random-mix")),
              new ListField(
                  "packs", "Packs", ItemFormat.UUID, 2, null, "word pack ids", List.of())));

  @Test
  void defaultsAreTheDeclaredValuesInDeclarationOrder() {
    assertThat(SCHEMA.defaults().keySet())
        .containsExactly(
            "cycles",
            "drawSeconds",
            "entries",
            "hintReveal",
            "skip",
            "difficulty",
            "categories",
            "packs");
    assertThat(SCHEMA.defaults()).containsEntry("cycles", 2).containsEntry("packs", List.of());
  }

  @Test
  void suppliedValuesOverrideDefaultsAndOthersStay() {
    assertThat(SCHEMA.resolve(Map.of("cycles", 4, "difficulty", "easy")))
        .containsEntry("cycles", 4)
        .containsEntry("difficulty", "easy")
        .containsEntry("drawSeconds", 80)
        .containsEntry("hintReveal", true);
  }

  @Test
  void nullSuppliedYieldsDefaults() {
    assertThat(SCHEMA.resolve(null)).isEqualTo(SCHEMA.defaults());
  }

  @Test
  void rejectsUnknownKeysWrongTypesAndOutOfRangeValuesTogether() {
    Map<String, Object> bad =
        Map.of("cycles", 99, "hintReveal", "yes", "difficulty", "nightmare", "bogus", 1);

    assertThatThrownBy(() -> SCHEMA.resolve(bad))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
              assertThat(e.fieldErrors())
                  .extracting(f -> f.field() + ": " + f.message())
                  .containsExactlyInAnyOrder(
                      "settings.cycles: must be a whole number from 1 to 5",
                      "settings.hintReveal: must be true or false",
                      "settings.difficulty: must be one of easy, hard, mixed",
                      "settings.bogus: is not a setting of this game");
            });
  }

  @Test
  void integerBoundsAreInclusiveAndFractionsAreRejected() {
    assertThat(SCHEMA.resolve(Map.of("drawSeconds", 30))).containsEntry("drawSeconds", 30);
    assertThat(SCHEMA.resolve(Map.of("drawSeconds", 180L))).containsEntry("drawSeconds", 180L);
    assertThatThrownBy(() -> SCHEMA.resolve(Map.of("drawSeconds", 29)))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> SCHEMA.resolve(Map.of("drawSeconds", 60.5)))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void anAutoValueIsAcceptedNextToTheRange() {
    for (int ok : new int[] {0, 4, 5, 8}) {
      assertThat(SCHEMA.resolve(Map.of("entries", ok))).containsEntry("entries", ok);
    }
    for (int bad : new int[] {-1, 1, 3, 9}) {
      assertThatThrownBy(() -> SCHEMA.resolve(Map.of("entries", bad)))
          .isInstanceOfSatisfying(
              DomainException.class,
              e ->
                  assertThat(e.fieldErrors().getFirst().message())
                      .isEqualTo("must be 0 (automatic) or a whole number from 4 to 8"));
    }
  }

  @Test
  void aRestrictedBooleanAcceptsOnlyItsOptions() {
    assertThat(SCHEMA.resolve(Map.of("skip", true))).containsEntry("skip", true);
    assertThatThrownBy(() -> SCHEMA.resolve(Map.of("skip", false)))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.fieldErrors().getFirst().message()).isEqualTo("must be true"));
  }

  @Test
  void listsAcceptDistinctWellFormedItemsAndTheExclusiveOptionAlone() {
    for (List<String> ok :
        List.of(List.<String>of(), List.of("random-mix"), List.of("animals", "food-drinks"))) {
      assertThat(SCHEMA.resolve(Map.of("categories", ok))).containsEntry("categories", ok);
    }
    String id = "123e4567-e89b-12d3-a456-426614174000";
    assertThat(SCHEMA.resolve(Map.of("packs", List.of(id)))).containsEntry("packs", List.of(id));
  }

  @Test
  void malformedListsAreRejectedWithTheirMessage() {
    String id = "123e4567-e89b-12d3-a456-426614174000";
    List<Map<String, Object>> bad =
        List.of(
            Map.of("categories", "animals"),
            Map.of("categories", List.of("Animals")),
            Map.of("categories", List.of("animals", "animals")),
            Map.of("categories", List.of("random-mix", "animals")),
            Map.of("categories", List.of(7)),
            Map.of("categories", List.of("a", "b", "c", "d")),
            Map.of("packs", id),
            Map.of("packs", List.of("not-a-uuid")),
            Map.of("packs", List.of(id.toUpperCase())),
            Map.of("packs", List.of(id, id)),
            Map.of(
                "packs",
                List.of(
                    id,
                    "223e4567-e89b-12d3-a456-426614174000",
                    "323e4567-e89b-12d3-a456-426614174000")));
    for (Map<String, Object> settings : bad) {
      String key = settings.keySet().iterator().next();
      String message =
          key.equals("categories")
              ? "must be a list of at most 3 distinct category keys, or [\"random-mix\"]"
              : "must be a list of at most 2 distinct word pack ids";
      assertThatThrownBy(() -> SCHEMA.resolve(settings))
          .as(settings.toString())
          .isInstanceOfSatisfying(
              DomainException.class,
              e ->
                  assertThat(e.fieldErrors())
                      .extracting(f -> f.field() + ": " + f.message())
                      .containsExactly("settings." + key + ": " + message));
    }
  }

  @Test
  void anEmptySchemaRejectsEverySuppliedKey() {
    assertThat(SettingsSchema.EMPTY.resolve(Map.of())).isEmpty();
    assertThatThrownBy(() -> SettingsSchema.EMPTY.resolve(Map.of("cycles", 2)))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void aSchemaWithDuplicateKeysOrAnInvalidDefaultCannotBeBuilt() {
    assertThatThrownBy(
            () ->
                new SettingsSchema(
                    List.of(IntField.of("a", "A", 1, 2, 1), BoolField.of("a", "A", true))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SettingsSchema(List.of(IntField.of("a", "A", 1, 2, 3))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
