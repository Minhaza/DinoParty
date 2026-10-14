package com.dinoparty.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScoreLedgerTest {

  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();

  @Test
  void totalsEqualTheSumOfDeltasForRandomSequences() {
    Random random = new Random(11); // test data only
    for (int round = 0; round < 100; round++) {
      ScoreLedger ledger = new ScoreLedger(List.of(a, b));
      Map<UUID, Integer> expected = new HashMap<>(Map.of(a, 0, b, 0));
      for (int i = 0; i < 200; i++) {
        UUID guest = random.nextBoolean() ? a : b;
        int points = random.nextInt(21) - 5;
        ledger.apply(new ScoreDelta(guest, points, "T"));
        expected.merge(guest, points, Integer::sum);
      }
      assertThat(ledger.totals()).isEqualTo(expected);
    }
  }

  @Test
  void aGuestOutsideTheRosterIsRejected() {
    ScoreLedger ledger = new ScoreLedger(List.of(a));

    assertThatThrownBy(() -> ledger.apply(new ScoreDelta(b, 1, "T")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void overflowFailsInsteadOfWrapping() {
    ScoreLedger ledger = new ScoreLedger(List.of(a));
    ledger.apply(new ScoreDelta(a, Integer.MAX_VALUE, "T"));

    assertThatThrownBy(() -> ledger.apply(new ScoreDelta(a, 1, "T")))
        .isInstanceOf(ArithmeticException.class);
    assertThat(ledger.totals().get(a)).isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void checkDoesNotChangeTheLedger() {
    ScoreLedger ledger = new ScoreLedger(List.of(a));

    ledger.check(new ScoreDelta(a, 5, "T"));

    assertThat(ledger.totals().get(a)).isZero();
  }
}
