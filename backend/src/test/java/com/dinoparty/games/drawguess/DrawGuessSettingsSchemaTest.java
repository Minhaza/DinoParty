package com.dinoparty.games.drawguess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.common.DomainException;
import com.dinoparty.engine.SettingField.EnumField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The schema the room validates with and the values {@link DrawGuessSettings#from} reads must not
 * drift (draw-guess-design §10): every module bound is accepted by the schema and one step outside
 * is refused; every option the module understands is an option of the schema.
 */
class DrawGuessSettingsSchemaTest {

  private static final Map<String, Object> DEFAULTS = DrawGuessSettings.defaults();

  private static Map<String, Object> resolve(Map<String, Object> supplied) {
    return DrawGuessSettings.SCHEMA.resolve(supplied);
  }

  @Test
  void enumOptionsAreExactlyTheValuesTheModuleReads() {
    assertThat(DrawGuessSettings.SCHEMA.field("difficulty").orElseThrow())
        .isInstanceOfSatisfying(
            EnumField.class,
            f ->
                assertThat(f.options())
                    .containsExactlyInAnyOrderElementsOf(DrawGuessSettings.DIFFICULTIES.keySet()));
    assertThat(DrawGuessSettings.SCHEMA.field("wordSource").orElseThrow())
        .isInstanceOfSatisfying(
            EnumField.class,
            f -> assertThat(f.options()).containsExactly("built-in", "custom", "combined"));
  }

  @Test
  void integerBoundsMatchTheSchema() {
    DrawGuessSettings.INT_BOUNDS.forEach(
        (key, bound) -> {
          assertThat(resolve(Map.of(key, bound.min()))).containsEntry(key, bound.min());
          assertThat(resolve(Map.of(key, bound.max()))).containsEntry(key, bound.max());
          assertThatThrownBy(() -> resolve(Map.of(key, bound.min() - 1)))
              .isInstanceOf(DomainException.class);
          assertThatThrownBy(() -> resolve(Map.of(key, bound.max() + 1)))
              .isInstanceOf(DomainException.class);
        });
  }

  @Test
  void theDefaultsAreAValidCompleteSettingsObject() {
    assertThat(resolve(DEFAULTS)).isEqualTo(DEFAULTS);
    for (String difficulty : DrawGuessSettings.DIFFICULTIES.keySet()) {
      assertThat(resolve(Map.of("difficulty", difficulty))).containsEntry("difficulty", difficulty);
    }
  }

  @Test
  void wordSelectionValuesTheModuleReadsAreAccepted() {
    for (String source : List.of("built-in", "custom", "combined")) {
      assertThat(resolve(Map.of("wordSource", source))).containsEntry("wordSource", source);
    }
    List<String> nine =
        List.of(
            "animals",
            "food-drinks",
            "nature",
            "objects",
            "places",
            "movies-games",
            "sports",
            "technology",
            "actions");
    for (List<String> categories :
        List.of(List.<String>of(), List.of("random-mix"), List.of("animals"), nine)) {
      assertThat(resolve(Map.of("wordCategories", categories)))
          .containsEntry("wordCategories", categories);
    }
    List<String> packs = new ArrayList<>();
    for (int i = 0; i < DrawGuessSettings.MAX_PACKS; i++) {
      packs.add(UUID.randomUUID().toString());
    }
    assertThat(resolve(Map.of("wordPackIds", packs))).containsEntry("wordPackIds", packs);
  }

  @Test
  void malformedWordSelectionValuesAreRejected() {
    String id = UUID.randomUUID().toString();
    List<String> tooManyCategories = new ArrayList<>();
    for (int i = 0; i <= DrawGuessSettings.MAX_CATEGORIES; i++) {
      tooManyCategories.add("category-" + (char) ('a' + i));
    }
    List<String> tooManyPacks = new ArrayList<>();
    for (int i = 0; i <= DrawGuessSettings.MAX_PACKS; i++) {
      tooManyPacks.add(UUID.randomUUID().toString());
    }
    List<Map<String, Object>> bad =
        List.of(
            Map.of("wordSource", "BUILT_IN"),
            Map.of("wordSource", "packs"),
            Map.of("difficulty", "MEDIUM"),
            Map.of("wordCategories", "animals"),
            Map.of("wordCategories", List.of("Animals")),
            Map.of("wordCategories", List.of("animals", "animals")),
            Map.of("wordCategories", List.of("random-mix", "animals")),
            Map.of("wordCategories", List.of(7)),
            Map.of("wordCategories", tooManyCategories),
            Map.of("wordPackIds", id),
            Map.of("wordPackIds", List.of("not-a-uuid")),
            Map.of("wordPackIds", List.of(id.toUpperCase(Locale.ROOT))),
            Map.of("wordPackIds", List.of(id, id)),
            Map.of("wordPackIds", tooManyPacks));
    for (Map<String, Object> settings : bad) {
      assertThatThrownBy(() -> resolve(settings))
          .as(settings.toString())
          .isInstanceOfSatisfying(
              DomainException.class,
              e ->
                  assertThat(e.fieldErrors())
                      .extracting("field")
                      .containsExactly("settings." + settings.keySet().iterator().next()));
    }
  }
}
