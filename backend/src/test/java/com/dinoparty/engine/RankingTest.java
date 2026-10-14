package com.dinoparty.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** [C-8] competition ranking: 1, 2, 2, 4. */
class RankingTest {

  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();
  private final UUID c = UUID.randomUUID();
  private final UUID d = UUID.randomUUID();

  @Test
  void tiesShareARankAndTheNextRankSkips() {
    List<Ranking.Entry> ranking = Ranking.of(Map.of(a, 10, b, 7, c, 7, d, 1), List.of(a, b, c, d));

    assertThat(ranking.stream().map(Ranking.Entry::rank).toList()).containsExactly(1, 2, 2, 4);
    assertThat(ranking.get(0).guestId()).isEqualTo(a);
    assertThat(ranking.get(3).guestId()).isEqualTo(d);
  }

  @Test
  void everyoneTiedIsRankOne() {
    List<Ranking.Entry> ranking = Ranking.of(Map.of(a, 0, b, 0, c, 0), List.of(a, b, c));

    assertThat(ranking).allMatch(e -> e.rank() == 1);
  }

  @Test
  void tiesKeepPlayOrderSoTheListIsDeterministic() {
    List<Ranking.Entry> first = Ranking.of(Map.of(a, 5, b, 5), List.of(b, a));

    assertThat(first.stream().map(Ranking.Entry::guestId).toList()).containsExactly(b, a);
  }
}
