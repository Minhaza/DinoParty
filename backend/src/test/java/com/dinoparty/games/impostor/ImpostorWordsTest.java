package com.dinoparty.games.impostor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.words.TestWords;
import com.dinoparty.words.TestWords.Catalog;
import com.dinoparty.words.WordCatalogTypes.CategorizedWord;
import com.dinoparty.words.WordDifficulty;
import com.dinoparty.words.WordKey;
import com.dinoparty.words.WordPoolService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** [DI-2] words of a session: distinct, with their category, from the set difficulty (D60). */
class ImpostorWordsTest {

  private static Catalog catalog() {
    Catalog c = new Catalog("animals", "objects");
    c.labels.put("animals", "Animals");
    c.labels.put("objects", "Objects");
    for (String w : List.of("cat", "dog", "cow", "fox")) {
      c.add(w, "animals", WordDifficulty.EASY);
    }
    for (String w : List.of("lamp", "chair", "spoon")) {
      c.add(w, "objects", WordDifficulty.MEDIUM);
    }
    for (String w : List.of("telescope", "harpsichord")) {
      c.add(w, "objects", WordDifficulty.HARD);
    }
    return c;
  }

  private static WordPoolService service(Catalog catalog) {
    return new WordPoolService(catalog, new TestWords.Packs());
  }

  @Test
  void DI2_drawsDistinctWordsWithTheirCategoryAtTheSetDifficulty() {
    ImpostorWords mixed = ImpostorWords.load(service(catalog()), ImpostorDifficulty.MIXED);
    for (long seed = 0; seed < 30; seed++) {
      List<CategorizedWord> words = mixed.draw(7, new SeededRandomSource(seed));
      assertThat(words).hasSize(7);
      assertThat(words.stream().map(w -> WordKey.of(w.text()))).doesNotHaveDuplicates();
      // mixed = EASY and MEDIUM only: hard words never come up.
      assertThat(words)
          .extracting(CategorizedWord::text)
          .doesNotContain("telescope", "harpsichord");
      for (CategorizedWord w : words) {
        assertThat(w.categoryLabel())
            .isEqualTo(w.categoryKey().equals("animals") ? "Animals" : "Objects");
      }
    }
    assertThat(ImpostorWords.load(service(catalog()), ImpostorDifficulty.EASY).size()).isEqualTo(4);
    assertThat(ImpostorWords.load(service(catalog()), ImpostorDifficulty.MEDIUM).size())
        .isEqualTo(3);
  }

  @Test
  void DI2_aPoolTooSmallRejectsTheStartWithTheCounts() {
    ImpostorWords medium = ImpostorWords.load(service(catalog()), ImpostorDifficulty.MEDIUM);
    assertThatThrownBy(() -> medium.draw(4, new SeededRandomSource(1)))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.WORD_POOL_TOO_SMALL);
              assertThat(e.details()).containsEntry("required", 4).containsEntry("available", 3);
              assertThat(e.getMessage()).doesNotContain("lamp", "chair", "spoon");
            });
    assertThat(medium.draw(3, new SeededRandomSource(1))).hasSize(3);
  }

  @Test
  void DI2_theDrawFollowsTheGivenSource() {
    ImpostorWords words = ImpostorWords.load(service(catalog()), ImpostorDifficulty.MIXED);
    assertThat(words.draw(5, new SeededRandomSource(4)))
        .isEqualTo(words.draw(5, new SeededRandomSource(4)));
    Set<List<CategorizedWord>> draws = new HashSet<>();
    for (long seed = 0; seed < 20; seed++) {
      draws.add(words.draw(5, new SeededRandomSource(seed)));
    }
    assertThat(draws).hasSizeGreaterThan(1);
  }

  @Test
  void theWordsNeverPrintThemselves() {
    ImpostorWords words = ImpostorWords.load(service(catalog()), ImpostorDifficulty.EASY);
    ImpostorSetup setup = new ImpostorSetup(words.draw(4, new SeededRandomSource(2)));
    assertThat(words.toString()).doesNotContain("cat", "dog", "cow", "fox");
    assertThat(setup.toString()).doesNotContain("cat", "dog", "cow", "fox");
  }
}
