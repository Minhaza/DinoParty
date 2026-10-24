package com.dinoparty.games.impostor;

import com.dinoparty.engine.ScoreDelta;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-session totals and results counters of an impostor game. The totals mirror the engine's
 * ledger so a round-end event can carry {@code scores}; the counters count recorded rounds only
 * (api-contracts §3.11 "Results").
 */
final class ImpostorScoreboard {

  private static final int AS_IMPOSTOR = 0;
  private static final int ESCAPES = 1;
  private static final int CORRECT_GUESSES = 2;
  private static final int FOUND = 3;

  private final Map<UUID, Integer> totals = new LinkedHashMap<>();
  private final Map<UUID, int[]> counters = new LinkedHashMap<>();

  ImpostorScoreboard(List<UUID> order) {
    order.forEach(
        g -> {
          totals.put(g, 0);
          counters.put(g, new int[4]);
        });
  }

  void apply(List<ScoreDelta> deltas) {
    deltas.forEach(d -> totals.merge(d.guestId(), d.points(), Integer::sum));
  }

  /** Counts a round that gets a {@code RoundRecord}; {@code finalVotes} are voter to target. */
  void record(UUID impostor, ImpostorOutcome outcome, Map<UUID, UUID> finalVotes) {
    counters.get(impostor)[AS_IMPOSTOR]++;
    if (outcome == ImpostorOutcome.ESCAPED) {
      counters.get(impostor)[ESCAPES]++;
    }
    if (outcome == ImpostorOutcome.GUESSED) {
      counters.get(impostor)[CORRECT_GUESSES]++;
    }
    if (outcome.caught()) {
      finalVotes.forEach(
          (voter, target) -> {
            if (impostor.equals(target)) {
              counters.get(voter)[FOUND]++;
            }
          });
    }
  }

  Map<UUID, Integer> totals() {
    return new LinkedHashMap<>(totals);
  }

  /** {@code standings[].detail} of §3.11, in {@code order}. */
  Map<UUID, Map<String, Object>> detail(List<UUID> order) {
    Map<UUID, Map<String, Object>> detail = new LinkedHashMap<>();
    for (UUID g : order) {
      int[] c = counters.get(g);
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("roundsAsImpostor", c[AS_IMPOSTOR]);
      d.put("escapes", c[ESCAPES]);
      d.put("correctGuesses", c[CORRECT_GUESSES]);
      d.put("impostorsFound", c[FOUND]);
      detail.put(g, d);
    }
    return detail;
  }
}
