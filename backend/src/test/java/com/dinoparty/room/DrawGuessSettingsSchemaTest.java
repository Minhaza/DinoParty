package com.dinoparty.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.common.DomainException;
import com.dinoparty.games.drawguess.DrawGuessSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The room module validates Draw & Guess settings with its own schema; the module reads them with
 * its own bounds. They must not drift (draw-guess-design §10): every module bound is accepted by
 * the room schema and one step outside is refused.
 */
class DrawGuessSettingsSchemaTest {

  private static final Map<String, Object> DEFAULTS = DrawGuessSettings.defaults();

  @Test
  void integerBoundsMatchTheRoomSchema() {
    DrawGuessSettings.INT_BOUNDS.forEach(
        (key, bound) -> {
          assertThat(SettingsSchema.resolve("draw-guess", DEFAULTS, Map.of(key, bound.min())))
              .containsEntry(key, bound.min());
          assertThat(SettingsSchema.resolve("draw-guess", DEFAULTS, Map.of(key, bound.max())))
              .containsEntry(key, bound.max());
          assertThatThrownBy(
                  () ->
                      SettingsSchema.resolve("draw-guess", DEFAULTS, Map.of(key, bound.min() - 1)))
              .isInstanceOf(DomainException.class);
          assertThatThrownBy(
                  () ->
                      SettingsSchema.resolve("draw-guess", DEFAULTS, Map.of(key, bound.max() + 1)))
              .isInstanceOf(DomainException.class);
        });
  }

  @Test
  void theDefaultsAreAValidCompleteSettingsObject() {
    assertThat(SettingsSchema.resolve("draw-guess", Map.of(), DEFAULTS)).isEqualTo(DEFAULTS);
    for (String difficulty : new String[] {"easy", "mixed", "hard"}) {
      assertThat(SettingsSchema.resolve("draw-guess", DEFAULTS, Map.of("difficulty", difficulty)))
          .containsEntry("difficulty", difficulty);
    }
  }
}
