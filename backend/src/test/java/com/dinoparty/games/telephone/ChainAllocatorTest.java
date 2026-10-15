package com.dinoparty.games.telephone;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** [TP-1] as invariants for n = 3..12 and every E <= n. */
class ChainAllocatorTest {

  @Test
  void TP1_exampleFourPlayersFourEntries() {
    // game-rules table: step 1 holders of c0..c3 are p1,p2,p3,p0
    assertThat(ChainAllocator.holder(0, 1, 4)).isEqualTo(1);
    assertThat(ChainAllocator.holder(3, 1, 4)).isEqualTo(0);
    assertThat(ChainAllocator.holder(0, 3, 4)).isEqualTo(3);
    assertThat(ChainAllocator.chainHeldBy(0, 1, 4)).isEqualTo(3);
  }

  @Test
  void TP1_everyStepIsAPermutation() {
    for (int n = 3; n <= 12; n++) {
      for (int step = 0; step < n; step++) {
        Set<Integer> holders = new HashSet<>();
        Set<Integer> chains = new HashSet<>();
        for (int c = 0; c < n; c++) {
          holders.add(ChainAllocator.holder(c, step, n));
          chains.add(ChainAllocator.chainHeldBy(c, step, n));
        }
        assertThat(holders).hasSize(n);
        assertThat(chains).hasSize(n);
      }
    }
  }

  @Test
  void TP1_holderAndChainHeldByAreInverse() {
    for (int n = 3; n <= 12; n++) {
      for (int step = 0; step < n; step++) {
        for (int c = 0; c < n; c++) {
          assertThat(ChainAllocator.chainHeldBy(ChainAllocator.holder(c, step, n), step, n))
              .isEqualTo(c);
        }
      }
    }
  }

  @Test
  void TP1_nobodyHoldsAChainTwiceAndOnlyTheOwnerHoldsItAtStepZero() {
    for (int n = 3; n <= 12; n++) {
      for (int entries = 1; entries <= n; entries++) {
        for (int c = 0; c < n; c++) {
          Set<Integer> seen = new HashSet<>();
          for (int step = 0; step < entries; step++) {
            int holder = ChainAllocator.holder(c, step, n);
            assertThat(seen.add(holder)).as("n=%d E=%d chain=%d", n, entries, c).isTrue();
            if (step == 0) {
              assertThat(holder).isEqualTo(c);
            } else {
              assertThat(holder).isNotEqualTo(c);
            }
          }
        }
      }
    }
  }

  @Test
  void TP1_previousAuthorIsNeverTheHolder() {
    for (int n = 3; n <= 12; n++) {
      for (int step = 1; step < n; step++) {
        for (int c = 0; c < n; c++) {
          assertThat(ChainAllocator.holder(c, step, n))
              .isNotEqualTo(ChainAllocator.holder(c, step - 1, n));
        }
      }
    }
  }
}
