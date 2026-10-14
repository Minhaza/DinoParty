package com.dinoparty.engine;

import com.dinoparty.common.RandomSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Play order helpers shared by every game: shuffled once per session, left players skipped. */
public final class TurnOrder {

  private TurnOrder() {}

  /** Fisher-Yates shuffle with the session random source; returns a new permutation. */
  public static List<UUID> shuffle(List<UUID> players, RandomSource random) {
    List<UUID> order = new ArrayList<>(players);
    for (int i = order.size() - 1; i > 0; i--) {
      int j = random.nextInt(i + 1);
      UUID tmp = order.get(i);
      order.set(i, order.get(j));
      order.set(j, tmp);
    }
    return List.copyOf(order);
  }

  /**
   * The next player after position {@code from} (wrapping) for whom {@code active} holds, or empty
   * when nobody qualifies. Slots are never removed, so indexes stay stable for the whole session.
   */
  public static Optional<UUID> nextActive(List<UUID> order, int from, Predicate<UUID> active) {
    for (int step = 1; step <= order.size(); step++) {
      UUID candidate = order.get(Math.floorMod(from + step, order.size()));
      if (active.test(candidate)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }
}
