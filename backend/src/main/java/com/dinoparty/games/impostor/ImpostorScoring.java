package com.dinoparty.games.impostor;

import com.dinoparty.engine.ScoreDelta;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** [DI-12] scoring of one round. Pure. */
final class ImpostorScoring {

  static final String ESCAPED_REASON = "IMPOSTOR_ESCAPED";
  static final String GUESSED_REASON = "IMPOSTOR_GUESSED";
  static final String CAUGHT_REASON = "ARTIST_CAUGHT";
  static final String VOTE_REASON = "ARTIST_VOTE";

  private ImpostorScoring() {}

  /**
   * The points of a round, one delta per reason (never zero).
   *
   * @param scoringArtists the artists seated this round who are not {@code LEFT} at its end
   * @param finalVotes voter to target after the drops of leavers
   */
  static List<ScoreDelta> deltas(
      ImpostorOutcome outcome,
      UUID impostor,
      Collection<UUID> scoringArtists,
      Map<UUID, UUID> finalVotes) {
    List<ScoreDelta> deltas = new ArrayList<>();
    switch (outcome) {
      case ESCAPED -> deltas.add(new ScoreDelta(impostor, 2, ESCAPED_REASON));
      case GUESSED -> deltas.add(new ScoreDelta(impostor, 2, GUESSED_REASON));
      case CAUGHT -> {
        for (UUID artist : scoringArtists) {
          deltas.add(new ScoreDelta(artist, 1, CAUGHT_REASON));
          if (impostor.equals(finalVotes.get(artist))) {
            deltas.add(new ScoreDelta(artist, 1, VOTE_REASON));
          }
        }
      }
      case IMPOSTOR_LEFT -> {
        // No points: the round did not play out.
      }
    }
    return deltas;
  }

  /** Points per player over {@code seated} (0 included), in that order. */
  static Map<UUID, Integer> perPlayer(Collection<UUID> seated, List<ScoreDelta> deltas) {
    Map<UUID, Integer> points = new LinkedHashMap<>();
    seated.forEach(g -> points.put(g, 0));
    deltas.forEach(d -> points.merge(d.guestId(), d.points(), Integer::sum));
    return points;
  }
}
