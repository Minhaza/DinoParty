package com.dinoparty.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeadlineBookTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private final UUID phase = UUID.randomUUID();
  private final DeadlineBook book = new DeadlineBook();

  @Test
  void dueEntriesComeOutInTimeThenRegistrationOrder() {
    book.set("late", DeadlineBook.Kind.INTERNAL, phase, T0.plusSeconds(5));
    book.set("b", DeadlineBook.Kind.INTERNAL, phase, T0.plusSeconds(1));
    book.set("a", DeadlineBook.Kind.INTERNAL, phase, T0.plusSeconds(1));

    assertThat(book.pollDue(T0)).isEmpty();
    assertThat(book.pollDue(T0.plusSeconds(1)).orElseThrow().id()).isEqualTo("b");
    assertThat(book.pollDue(T0.plusSeconds(1)).orElseThrow().id()).isEqualTo("a");
    assertThat(book.pollDue(T0.plusSeconds(1))).isEmpty();
    assertThat(book.pollDue(T0.plusSeconds(9)).orElseThrow().id()).isEqualTo("late");
  }

  @Test
  void replacingAnIdMovesItAndBumpsTheGeneration() {
    book.set("x", DeadlineBook.Kind.INTERNAL, phase, T0.plusSeconds(1));
    book.set("x", DeadlineBook.Kind.INTERNAL, phase, T0.plusSeconds(10));

    assertThat(book.size()).isEqualTo(1);
    assertThat(book.pollDue(T0.plusSeconds(5))).isEmpty();
    DeadlineBook.Entry entry = book.pollDue(T0.plusSeconds(10)).orElseThrow();
    assertThat(entry.generation()).isEqualTo(2);
  }

  @Test
  void aPhaseChangeDropsModuleEntriesButKeepsEngineOnes() {
    book.set("countdown", DeadlineBook.Kind.ENGINE, null, T0.plusSeconds(1));
    book.set("phase-end", DeadlineBook.Kind.PHASE_END, phase, T0.plusSeconds(2));
    book.set("hint", DeadlineBook.Kind.INTERNAL, phase, T0.plusSeconds(3));

    book.removeModuleEntries();

    assertThat(book.entries()).extracting(DeadlineBook.Entry::id).containsExactly("countdown");
  }

  @Test
  void earliestAndRemove() {
    assertThat(book.earliest()).isEmpty();
    book.set("x", DeadlineBook.Kind.INTERNAL, phase, T0.plusSeconds(3));
    book.set("y", DeadlineBook.Kind.INTERNAL, phase, T0.plusSeconds(2));

    assertThat(book.earliest().orElseThrow().id()).isEqualTo("y");
    assertThat(book.remove("y")).isTrue();
    assertThat(book.remove("y")).isFalse();
    assertThat(book.earliest().orElseThrow().id()).isEqualTo("x");
  }
}
