package com.dinoparty.games.telephone;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.SeededRandomSource;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PromptPoolTest {

  private final List<String> pool = List.of("cat", "dog", "emu", "fox");

  @Test
  void TP3_drawsWithoutReplacementUntilExhausted() {
    Set<String> used = new HashSet<>();
    SeededRandomSource random = new SeededRandomSource(3);
    Set<String> drawn = new HashSet<>();
    for (int i = 0; i < pool.size(); i++) {
      assertThat(drawn.add(PromptPool.draw(pool, used, random))).isTrue();
    }
    assertThat(drawn).containsExactlyInAnyOrderElementsOf(pool);
  }

  @Test
  void TP3_exhaustionFallsBackToRepeats() {
    Set<String> used = new HashSet<>();
    SeededRandomSource random = new SeededRandomSource(3);
    for (int i = 0; i < 10; i++) {
      assertThat(PromptPool.draw(pool, used, random)).isIn(pool);
    }
  }

  @Test
  void TP3_sameSeedGivesTheSameDraws() {
    String a = PromptPool.draw(pool, new HashSet<>(), new SeededRandomSource(9));
    String b = PromptPool.draw(pool, new HashSet<>(), new SeededRandomSource(9));
    assertThat(a).isEqualTo(b);
  }
}
