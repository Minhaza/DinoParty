package com.dinoparty.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Competition ranking: equal scores share a rank and the next rank skips (1, 2, 2, 4). */
public final class Ranking {

  public record Entry(int rank, UUID guestId, int score) {}

  private Ranking() {}

  /** Ties keep the order of the given play order, so the list is deterministic. */
  public static List<Entry> of(Map<UUID, Integer> totals, List<UUID> order) {
    List<UUID> sorted = new ArrayList<>(order.stream().filter(totals::containsKey).toList());
    sorted.sort(Comparator.comparing((UUID g) -> totals.get(g)).reversed());
    List<Entry> entries = new ArrayList<>();
    int rank = 0;
    Integer previous = null;
    for (int i = 0; i < sorted.size(); i++) {
      int score = totals.get(sorted.get(i));
      if (previous == null || score != previous) {
        rank = i + 1;
        previous = score;
      }
      entries.add(new Entry(rank, sorted.get(i), score));
    }
    return entries;
  }
}
