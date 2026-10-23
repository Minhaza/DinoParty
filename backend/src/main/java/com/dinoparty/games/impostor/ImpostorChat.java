package com.dinoparty.games.impostor;

import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.Timestamps;
import com.dinoparty.engine.ActionRejected;
import com.dinoparty.words.GuessNormalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The public chat of the voting phase ([DI-8]): validation, the 10 s rate window, recent lines. */
final class ImpostorChat {

  private final List<Map<String, Object>> lines = new ArrayList<>();
  private final RateWindow rate =
      new RateWindow(ImpostorFamily.CHATS_PER_WINDOW, ImpostorFamily.RATE_WINDOW);

  /**
   * Accepts one line and returns it as {@code {guestId, text, ts}}.
   *
   * @throws ActionRejected {@code VALIDATION_FAILED} unless the text has 1 to 120 code points after
   *     stripping control characters and trimming; {@code RATE_LIMITED} for the sixth line within
   *     10 s. Nothing changes on a rejection.
   */
  Map<String, Object> post(UUID guest, Object raw, Instant now) {
    if (!(raw instanceof String s)) {
      throw invalid("must be text");
    }
    String text = GuessNormalizer.sanitize(s);
    int length = GuessNormalizer.length(text);
    if (length < 1 || length > ImpostorFamily.CHAT_MAX_LENGTH) {
      throw invalid("must be 1 to " + ImpostorFamily.CHAT_MAX_LENGTH + " characters");
    }
    rate.check(guest, now);
    rate.record(guest, now);
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("guestId", guest);
    line.put("text", text);
    line.put("ts", Timestamps.format(now));
    lines.add(line);
    if (lines.size() > ImpostorFamily.CHAT_KEPT) {
      lines.removeFirst();
    }
    return line;
  }

  /** At most 30 lines, oldest first. */
  List<Map<String, Object>> recent() {
    return List.copyOf(lines);
  }

  private static ActionRejected invalid(String message) {
    return new ActionRejected(ErrorCode.VALIDATION_FAILED, "payload.text " + message);
  }
}
