package com.dinoparty.games.impostor;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.SeededRandomSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** [DI-3] fair impostor rotation and [DI-5] seat order; pure, many seeds. */
class ImpostorRotationTest {

  private static final int SEEDS = 40;

  private static List<UUID> players(int n) {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ids.add(UUID.randomUUID());
    }
    return ids;
  }

  @Test
  void DI3_withNobodyLeavingEveryCountIsFloorOrCeilingOfRoundsOverPlayers() {
    for (int n = 4; n <= 10; n++) {
      for (int rounds = 3; rounds <= 10; rounds++) {
        for (long seed = 0; seed < SEEDS; seed++) {
          List<UUID> players = players(n);
          SeededRandomSource random = new SeededRandomSource(seed);
          Map<UUID, Integer> counts = new HashMap<>();
          for (int r = 0; r < rounds; r++) {
            UUID impostor = ImpostorRotation.pick(players, counts, random);
            assertThat(players).contains(impostor);
            counts.merge(impostor, 1, Integer::sum);
          }
          int floor = rounds / n;
          int ceil = (rounds + n - 1) / n;
          for (UUID p : players) {
            assertThat(counts.getOrDefault(p, 0))
                .as("n=%d R=%d seed=%d", n, rounds, seed)
                .isBetween(floor, ceil);
          }
        }
      }
    }
  }

  @Test
  void DI3_withPlayersLeavingTheRemainingCountsDifferByAtMostOne() {
    for (int n = 4; n <= 10; n++) {
      for (int rounds = 3; rounds <= 10; rounds++) {
        for (long seed = 0; seed < SEEDS; seed++) {
          List<UUID> players = new ArrayList<>(players(n));
          SeededRandomSource random = new SeededRandomSource(seed);
          SeededRandomSource leaves = new SeededRandomSource(seed + 1000);
          Map<UUID, Integer> counts = new HashMap<>();
          for (int r = 0; r < rounds; r++) {
            // Somebody leaves before roughly every third round while at least 3 remain ([C-1]).
            if (players.size() > 3 && leaves.nextInt(3) == 0) {
              players.remove(leaves.nextInt(players.size()));
            }
            UUID impostor = ImpostorRotation.pick(players, counts, random);
            counts.merge(impostor, 1, Integer::sum);
          }
          IntSummaryStatistics stats =
              players.stream().mapToInt(p -> counts.getOrDefault(p, 0)).summaryStatistics();
          assertThat(stats.getMax() - stats.getMin())
              .as("n=%d R=%d seed=%d", n, rounds, seed)
              .isLessThanOrEqualTo(1);
        }
      }
    }
  }

  @Test
  void DI3_theTieBreakIsUniformAmongTheFewestCounts() {
    List<UUID> players = players(4);
    Map<UUID, Integer> counts = Map.of(players.get(0), 1, players.get(1), 1);
    SeededRandomSource random = new SeededRandomSource(3);
    Map<UUID, Integer> picked = new HashMap<>();
    for (int i = 0; i < 4000; i++) {
      picked.merge(ImpostorRotation.pick(players, counts, random), 1, Integer::sum);
    }
    assertThat(picked).containsOnlyKeys(players.get(2), players.get(3));
    assertThat(picked.get(players.get(2))).isBetween(1800, 2200);
  }

  @Test
  void DI3_thePickComesFromTheGivenSourceOnly() {
    List<UUID> players = players(10);
    Set<UUID> firstPicks = new HashSet<>();
    for (long seed = 0; seed < 50; seed++) {
      firstPicks.add(ImpostorRotation.pick(players, Map.of(), new SeededRandomSource(seed)));
    }
    assertThat(firstPicks).hasSizeGreaterThan(5);
    assertThat(ImpostorRotation.pick(players, Map.of(), new SeededRandomSource(9)))
        .isEqualTo(ImpostorRotation.pick(players, Map.of(), new SeededRandomSource(9)));
  }

  @Test
  void DI5_theImpostorIsNeverInSeatZeroAndTheSeatsArePermutations() {
    for (int n = 4; n <= 10; n++) {
      for (long seed = 0; seed < 200; seed++) {
        List<UUID> players = players(n);
        UUID impostor = players.get((int) (seed % n));
        List<UUID> seats =
            ImpostorRotation.seatOrder(players, impostor, new SeededRandomSource(seed));
        assertThat(seats).containsExactlyInAnyOrderElementsOf(players);
        assertThat(seats.getFirst()).as("n=%d seed=%d", n, seed).isNotEqualTo(impostor);
      }
    }
  }

  @Test
  void DI5_theImpostorDrawnIntoSeatZeroMovesToSeatOneAndTheOthersKeepTheirOrder() {
    List<UUID> players = players(6);
    for (long seed = 0; seed < 200; seed++) {
      // The plain shuffle of this seed tells where the impostor would have landed.
      List<UUID> shuffled =
          com.dinoparty.engine.TurnOrder.shuffle(players, new SeededRandomSource(seed));
      UUID first = shuffled.getFirst();
      List<UUID> seats = ImpostorRotation.seatOrder(players, first, new SeededRandomSource(seed));
      assertThat(seats.get(1)).isEqualTo(first);
      List<UUID> othersBefore = new ArrayList<>(shuffled.subList(1, shuffled.size()));
      List<UUID> othersAfter = new ArrayList<>(seats);
      othersAfter.remove(first);
      assertThat(othersAfter).isEqualTo(othersBefore);

      // An impostor elsewhere leaves the shuffle untouched.
      UUID other = shuffled.get(3);
      assertThat(ImpostorRotation.seatOrder(players, other, new SeededRandomSource(seed)))
          .isEqualTo(shuffled);
    }
  }
}
