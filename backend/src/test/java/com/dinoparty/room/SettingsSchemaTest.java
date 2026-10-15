package com.dinoparty.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SettingsSchemaTest {

  private static final Map<String, Object> DEFAULTS =
      Map.of("cycles", 2, "drawSeconds", 80, "hintReveal", true, "difficulty", "mixed");

  @Test
  void suppliedValuesOverrideDefaultsAndOthersStay() {
    Map<String, Object> resolved =
        SettingsSchema.resolve("draw-guess", DEFAULTS, Map.of("cycles", 4, "difficulty", "easy"));

    assertThat(resolved)
        .containsEntry("cycles", 4)
        .containsEntry("difficulty", "easy")
        .containsEntry("drawSeconds", 80)
        .containsEntry("hintReveal", true);
  }

  @Test
  void nullSuppliedYieldsDefaults() {
    assertThat(SettingsSchema.resolve("draw-guess", DEFAULTS, null)).isEqualTo(DEFAULTS);
  }

  @Test
  void rejectsUnknownKeysWrongTypesAndOutOfRangeValuesTogether() {
    Map<String, Object> bad =
        Map.of("cycles", 99, "hintReveal", "yes", "difficulty", "nightmare", "bogus", 1);

    assertThatThrownBy(() -> SettingsSchema.resolve("draw-guess", DEFAULTS, bad))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
              assertThat(e.fieldErrors())
                  .extracting(f -> f.field())
                  .containsExactlyInAnyOrder(
                      "settings.cycles",
                      "settings.hintReveal",
                      "settings.difficulty",
                      "settings.bogus");
            });
  }

  @Test
  void integerBoundsAreInclusiveAndFractionsAreRejected() {
    assertThat(SettingsSchema.resolve("draw-guess", DEFAULTS, Map.of("drawSeconds", 30)))
        .containsEntry("drawSeconds", 30);
    assertThat(SettingsSchema.resolve("draw-guess", DEFAULTS, Map.of("drawSeconds", 180)))
        .containsEntry("drawSeconds", 180);
    assertThatThrownBy(
            () -> SettingsSchema.resolve("draw-guess", DEFAULTS, Map.of("drawSeconds", 29)))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(
            () -> SettingsSchema.resolve("draw-guess", DEFAULTS, Map.of("drawSeconds", 60.5)))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void telephoneHasItsOwnSchema() {
    assertThatThrownBy(() -> SettingsSchema.resolve("telephone", Map.of(), Map.of("cycles", 2)))
        .isInstanceOf(DomainException.class);
    assertThat(SettingsSchema.resolve("telephone", Map.of(), Map.of("promptSeconds", 20)))
        .containsEntry("promptSeconds", 20);
  }

  @Test
  void TP2_entriesPerChainIsAutoOrFourToEight() {
    for (int ok : new int[] {0, 4, 5, 8}) {
      assertThat(SettingsSchema.resolve("telephone", Map.of(), Map.of("entriesPerChain", ok)))
          .containsEntry("entriesPerChain", ok);
    }
    for (int bad : new int[] {-1, 1, 3, 9}) {
      assertThatThrownBy(
              () ->
                  SettingsSchema.resolve("telephone", Map.of(), Map.of("entriesPerChain", bad)))
          .isInstanceOf(DomainException.class);
    }
  }

  @Test
  void TP5_allowSkipTimeoutAcceptsOnlyTrue() {
    assertThat(SettingsSchema.resolve("telephone", Map.of(), Map.of("allowSkipTimeout", true)))
        .containsEntry("allowSkipTimeout", true);
    assertThatThrownBy(
            () -> SettingsSchema.resolve("telephone", Map.of(), Map.of("allowSkipTimeout", false)))
        .isInstanceOf(DomainException.class);
  }
}
