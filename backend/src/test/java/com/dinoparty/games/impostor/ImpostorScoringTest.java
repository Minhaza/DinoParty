package com.dinoparty.games.impostor;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.engine.ScoreDelta;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImpostorScoringTest {

  private final UUID impostor = UUID.randomUUID();
  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();
  private final UUID c = UUID.randomUUID();
  private final List<UUID> seated = List.of(impostor, a, b, c);

  private Map<UUID, Integer> points(
      ImpostorOutcome outcome, List<UUID> scoring, Map<UUID, UUID> votes) {
    return ImpostorScoring.perPlayer(
        seated, ImpostorScoring.deltas(outcome, impostor, scoring, votes));
  }

  @Test
  void DI12_anImpostorWhoEscapedGetsTwoAndNobodyElseScores() {
    assertThat(points(ImpostorOutcome.ESCAPED, List.of(a, b, c), Map.of()))
        .containsEntry(impostor, 2)
        .containsEntry(a, 0)
        .containsEntry(b, 0)
        .containsEntry(c, 0);
  }

  @Test
  void DI12_aCaughtImpostorWhoGuessedRightGetsTwoAndTheArtistsNothing() {
    Map<UUID, UUID> votes = Map.of(a, impostor, b, impostor);
    assertThat(points(ImpostorOutcome.GUESSED, List.of(a, b, c), votes))
        .containsEntry(impostor, 2)
        .containsEntry(a, 0)
        .containsEntry(b, 0);
  }

  @Test
  void DI12_whenCaughtAndWrongEveryArtistGetsOneAndOneMoreForAFinalVoteOnTheImpostor() {
    Map<UUID, UUID> votes = new LinkedHashMap<>();
    votes.put(a, impostor);
    votes.put(b, impostor);
    votes.put(c, a);

    assertThat(points(ImpostorOutcome.CAUGHT, List.of(a, b, c), votes))
        .containsEntry(impostor, 0)
        .containsEntry(a, 2)
        .containsEntry(b, 2)
        .containsEntry(c, 1);
  }

  @Test
  void DI12_anArtistWhoLeftGetsNothingWhenTheRoundIsCaught() {
    Map<UUID, UUID> votes = Map.of(a, impostor, b, impostor);

    assertThat(points(ImpostorOutcome.CAUGHT, List.of(a, c), votes))
        .containsEntry(a, 2)
        .containsEntry(b, 0)
        .containsEntry(c, 1);
  }

  @Test
  void DI12_theReasonsAreOneDeltaPerCause() {
    List<ScoreDelta> deltas =
        ImpostorScoring.deltas(
            ImpostorOutcome.CAUGHT, impostor, List.of(a, b), Map.of(a, impostor));

    assertThat(deltas)
        .containsExactly(
            new ScoreDelta(a, 1, "ARTIST_CAUGHT"),
            new ScoreDelta(a, 1, "ARTIST_VOTE"),
            new ScoreDelta(b, 1, "ARTIST_CAUGHT"));
    assertThat(ImpostorScoring.deltas(ImpostorOutcome.ESCAPED, impostor, List.of(a), Map.of()))
        .containsExactly(new ScoreDelta(impostor, 2, "IMPOSTOR_ESCAPED"));
    assertThat(ImpostorScoring.deltas(ImpostorOutcome.GUESSED, impostor, List.of(a), Map.of()))
        .containsExactly(new ScoreDelta(impostor, 2, "IMPOSTOR_GUESSED"));
  }

  @Test
  void DI12_DI13_anImpostorWhoLeftGivesNoPoints() {
    assertThat(
            ImpostorScoring.deltas(
                ImpostorOutcome.IMPOSTOR_LEFT, impostor, List.of(a, b, c), Map.of(a, impostor)))
        .isEmpty();
    assertThat(points(ImpostorOutcome.IMPOSTOR_LEFT, List.of(a, b, c), Map.of()).values())
        .containsOnly(0);
  }
}
