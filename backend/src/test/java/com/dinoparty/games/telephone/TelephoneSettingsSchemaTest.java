package com.dinoparty.games.telephone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.common.DomainException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The schema the room validates Telephone settings with ([TP-2], D38, D39) and the values {@link
 * TelephoneSettings#from} reads must not drift.
 */
class TelephoneSettingsSchemaTest {

  private static Map<String, Object> resolve(Map<String, Object> supplied) {
    return TelephoneSettings.SCHEMA.resolve(supplied);
  }

  @Test
  void telephoneHasItsOwnSchema() {
    assertThatThrownBy(() -> resolve(Map.of("cycles", 2))).isInstanceOf(DomainException.class);
    assertThat(resolve(Map.of("promptSeconds", 20))).containsEntry("promptSeconds", 20);
  }

  @Test
  void integerBoundsMatchTheSchema() {
    TelephoneSettings.INT_BOUNDS.forEach(
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
    Map<String, Object> defaults = TelephoneSettings.defaults();
    assertThat(resolve(defaults)).isEqualTo(defaults);
    assertThat(defaults.keySet())
        .containsExactly(
            "promptSeconds",
            "drawSeconds",
            "describeSeconds",
            "entriesPerChain",
            "allowSkipTimeout");
  }

  @Test
  void TP2_entriesPerChainIsAutoOrFourToEight() {
    for (int ok : new int[] {0, 4, 5, 8}) {
      assertThat(resolve(Map.of("entriesPerChain", ok))).containsEntry("entriesPerChain", ok);
      assertThat(TelephoneSettings.from(Map.of("entriesPerChain", ok)).entriesPerChain())
          .isEqualTo(ok);
    }
    for (int bad : new int[] {-1, 1, 3, 9}) {
      assertThatThrownBy(() -> resolve(Map.of("entriesPerChain", bad)))
          .isInstanceOf(DomainException.class);
    }
  }

  @Test
  void TP5_allowSkipTimeoutAcceptsOnlyTrue() {
    assertThat(resolve(Map.of("allowSkipTimeout", true))).containsEntry("allowSkipTimeout", true);
    assertThatThrownBy(() -> resolve(Map.of("allowSkipTimeout", false)))
        .isInstanceOf(DomainException.class);
  }
}
