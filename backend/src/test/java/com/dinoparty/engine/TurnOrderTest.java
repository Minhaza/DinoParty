package com.dinoparty.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.SeededRandomSource;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** [DG-1] / [TP-1]: the order is a permutation shuffled once, left players are skipped. */
class TurnOrderTest {

  private static List<UUID> players(int n) {
    return IntStream.range(0, n).mapToObj(i -> UUID.randomUUID()).toList();
  }

  @Test
  void shuffleIsAPermutationForEverySizeAndSeed() {
    for (int n = 1; n <= 12; n++) {
      List<UUID> players = players(n);
      for (long seed = 0; seed < 50; seed++) {
        List<UUID> order = TurnOrder.shuffle(players, new SeededRandomSource(seed));
        assertThat(order).hasSize(n).containsExactlyInAnyOrderElementsOf(players);
      }
    }
  }

  @Test
  void sameSeedGivesTheSameOrderAndSeedsDiffer() {
    List<UUID> players = players(8);

    assertThat(TurnOrder.shuffle(players, new SeededRandomSource(1)))
        .isEqualTo(TurnOrder.shuffle(players, new SeededRandomSource(1)));
    Set<List<UUID>> distinct = new HashSet<>();
    for (long seed = 0; seed < 30; seed++) {
      distinct.add(TurnOrder.shuffle(players, new SeededRandomSource(seed)));
    }
    assertThat(distinct.size()).isGreaterThan(20);
  }

  @Test
  void nextActiveWrapsAndSkipsPlayersWhoLeft() {
    List<UUID> order = players(4);
    Set<UUID> left = Set.of(order.get(1), order.get(2));

    assertThat(TurnOrder.nextActive(order, 0, g -> !left.contains(g))).contains(order.get(3));
    assertThat(TurnOrder.nextActive(order, 3, g -> !left.contains(g))).contains(order.get(0));
    assertThat(TurnOrder.nextActive(order, 0, g -> false)).isEqualTo(Optional.empty());
  }
}
