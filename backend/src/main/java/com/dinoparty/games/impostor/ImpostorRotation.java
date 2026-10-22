package com.dinoparty.games.impostor;

import com.dinoparty.common.RandomSource;
import com.dinoparty.engine.TurnOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Who is the impostor ([DI-3]) and where everyone sits ([DI-5]). Pure functions. */
final class ImpostorRotation {

  private ImpostorRotation() {}

  /**
   * [DI-3] (D52): uniformly at random among the {@code eligible} players who have been impostor the
   * fewest times. {@code random} must be the injected source, not the session's seeded one, so the
   * stored session seed cannot reveal who was the impostor.
   *
   * @param eligible the roster members not {@code LEFT}, in a stable order
   * @param counts impostor rounds so far; a missing player counts 0
   */
  static UUID pick(List<UUID> eligible, Map<UUID, Integer> counts, RandomSource random) {
    if (eligible.isEmpty()) {
      throw new IllegalArgumentException("Nobody can be the impostor");
    }
    int fewest = Integer.MAX_VALUE;
    List<UUID> candidates = new ArrayList<>();
    for (UUID player : eligible) {
      int count = counts.getOrDefault(player, 0);
      if (count < fewest) {
        fewest = count;
        candidates.clear();
      }
      if (count == fewest) {
        candidates.add(player);
      }
    }
    return candidates.get(random.nextInt(candidates.size()));
  }

  /**
   * [DI-5] (D55): a fresh shuffle of the seated players from the session random source; an impostor
   * shuffled into seat 0 moves to seat 1 and everyone else keeps their order.
   */
  static List<UUID> seatOrder(List<UUID> seated, UUID impostor, RandomSource sessionRandom) {
    List<UUID> seats = new ArrayList<>(TurnOrder.shuffle(seated, sessionRandom));
    if (seats.size() > 1 && seats.getFirst().equals(impostor)) {
      seats.remove(0);
      seats.add(1, impostor);
    }
    return List.copyOf(seats);
  }
}
