package com.dinoparty.games.impostor;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImpostorChatTest {

  private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");

  private final ImpostorChat chat = new ImpostorChat();
  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  private static ErrorCode rejected(Runnable action) {
    try {
      action.run();
    } catch (DomainException e) {
      return e.code();
    }
    throw new AssertionError("expected a rejection");
  }

  @Test
  void DI8_aLineIsPublishedWithItsAuthorAndTimestamp() {
    Map<String, Object> line = chat.post(alice, "  hello  ", T0);

    assertThat(line)
        .containsEntry("guestId", alice)
        .containsEntry("text", "hello")
        .containsEntry("ts", "2026-01-01T12:00:00.000Z");
    assertThat(chat.recent()).containsExactly(line);
  }

  @Test
  void DI8_controlAndFormatCharactersAreStrippedBeforeTheLengthIsCounted() {
    Map<String, Object> line = chat.post(alice, "‮hi\u0000 there​", T0);
    assertThat(line).containsEntry("text", "hi there");
  }

  @Test
  void DI8_anEmojiIsOneCodePoint() {
    String max = "🦖".repeat(120);
    assertThat(chat.post(alice, max, T0)).containsEntry("text", max);
    assertThat(rejected(() -> chat.post(bob, max + "a", T0)))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
  }

  @Test
  void DI8_emptyOrOnlyInvisibleTextAndNonTextAreValidationFailed() {
    assertThat(rejected(() -> chat.post(alice, "", T0))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> chat.post(alice, " ​\u0007 ", T0)))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> chat.post(alice, null, T0))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> chat.post(alice, 5, T0))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(chat.recent()).isEmpty();
  }

  @Test
  void DI8_theSixthLineWithinTenSecondsIsRateLimitedAndAcceptedAgainAfterwards() {
    for (int i = 0; i < 5; i++) {
      chat.post(alice, "line " + i, T0.plusSeconds(i));
    }
    assertThat(rejected(() -> chat.post(alice, "too many", T0.plusSeconds(9))))
        .isEqualTo(ErrorCode.RATE_LIMITED);
    assertThat(chat.recent()).hasSize(5);
    // The limit is per player.
    chat.post(bob, "me too", T0.plusSeconds(9));

    // The first line (t=0) leaves the window at exactly t=10 s.
    chat.post(alice, "again", T0.plusSeconds(10));
    assertThat(rejected(() -> chat.post(alice, "no", T0.plusSeconds(10))))
        .isEqualTo(ErrorCode.RATE_LIMITED);
  }

  @Test
  void DI8_aRejectedLineDoesNotCountAgainstTheWindow() {
    for (int i = 0; i < 10; i++) {
      rejected(() -> chat.post(alice, "", T0));
    }
    for (int i = 0; i < 5; i++) {
      chat.post(alice, "ok " + i, T0);
    }
    assertThat(chat.recent()).hasSize(5);
  }

  @Test
  void DI8_atMostThirtyLinesAreKeptOldestFirst() {
    for (int i = 0; i < 40; i++) {
      chat.post(UUID.randomUUID(), "line " + i, T0);
    }
    assertThat(chat.recent()).hasSize(30);
    assertThat(chat.recent().getFirst()).containsEntry("text", "line 10");
    assertThat(chat.recent().getLast()).containsEntry("text", "line 39");
  }
}
