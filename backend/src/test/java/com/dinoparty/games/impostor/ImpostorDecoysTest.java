package com.dinoparty.games.impostor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.RandomSource;
import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.games.impostor.ImpostorWords.WithDecoys;
import com.dinoparty.words.GuessNormalizer;
import com.dinoparty.words.TestWords;
import com.dinoparty.words.TestWords.Catalog;
import com.dinoparty.words.WordCatalogTypes.CategorizedWord;
import com.dinoparty.words.WordDifficulty;
import com.dinoparty.words.WordPoolService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** [SS-3] / D63: the decoy of each round of an unaware-impostor Sketchy Saurus session. */
class ImpostorDecoysTest {

  private static WordPoolService service(Catalog catalog) {
    return new WordPoolService(catalog, new TestWords.Packs());
  }

  private static Catalog catalog() {
    Catalog c = new Catalog("animals", "objects", "lonely");
    for (String w : List.of("cat", "dog", "cow", "fox", "owl")) {
      c.add(w, "animals", WordDifficulty.EASY);
    }
    for (String w : List.of("lamp", "chair", "spoon", "table")) {
      c.add(w, "objects", WordDifficulty.MEDIUM);
    }
    c.add("telescope", "objects", WordDifficulty.HARD);
    c.add("harpsichord", "objects", WordDifficulty.HARD);
    c.add("hermit", "lonely", WordDifficulty.EASY); // a category with a single word
    return c;
  }

  private static ImpostorWords mixed() {
    return ImpostorWords.load(service(catalog()), ImpostorDifficulty.MIXED);
  }

  @Test
  void SS3_eachDecoyIsAnotherWordOfTheSameCategoryAtTheSetDifficulty() {
    for (long seed = 0; seed < 40; seed++) {
      WithDecoys drawn = mixed().drawWithDecoys(4, new SeededRandomSource(seed));
      assertThat(drawn.words()).hasSize(4);
      assertThat(drawn.decoys()).hasSize(4);
      for (int i = 0; i < 4; i++) {
        CategorizedWord word = drawn.words().get(i);
        CategorizedWord decoy = drawn.decoys().get(i);
        assertThat(decoy.categoryKey()).isEqualTo(word.categoryKey());
        assertThat(decoy.categoryLabel()).isEqualTo(word.categoryLabel());
        assertThat(GuessNormalizer.normalize(decoy.text()))
            .isNotEqualTo(GuessNormalizer.normalize(word.text()));
      }
      // mixed = EASY and MEDIUM only: hard words are neither word nor decoy.
      assertThat(drawn.words()).extracting(CategorizedWord::text).doesNotContain("telescope");
      assertThat(drawn.decoys())
          .extracting(CategorizedWord::text)
          .doesNotContain("telescope", "harpsichord");
    }
  }

  @Test
  void SS3_noWordOrDecoyIsUsedTwiceInTheSession() {
    for (long seed = 0; seed < 40; seed++) {
      WithDecoys drawn = mixed().drawWithDecoys(4, new SeededRandomSource(seed));
      Set<String> seen = new HashSet<>();
      for (CategorizedWord w : drawn.words()) {
        assertThat(seen.add(GuessNormalizer.normalize(w.text()))).isTrue();
      }
      for (CategorizedWord d : drawn.decoys()) {
        assertThat(seen.add(GuessNormalizer.normalize(d.text()))).isTrue();
      }
      assertThat(seen).hasSize(8);
    }
  }

  @Test
  void SS3_aCategoryWithoutAPartnerNeverGivesAWord() {
    for (long seed = 0; seed < 40; seed++) {
      WithDecoys drawn = mixed().drawWithDecoys(4, new SeededRandomSource(seed));
      assertThat(drawn.words()).extracting(CategorizedWord::text).doesNotContain("hermit");
      assertThat(drawn.decoys()).extracting(CategorizedWord::text).doesNotContain("hermit");
    }
  }

  @Test
  void SS3_theWordsAndDecoysAreDrawnFromTheInjectedSourceNotAFixedOrder() {
    Set<List<String>> outcomes = new HashSet<>();
    for (long seed = 0; seed < 30; seed++) {
      WithDecoys drawn = mixed().drawWithDecoys(3, new SeededRandomSource(seed));
      outcomes.add(drawn.words().stream().map(CategorizedWord::text).toList());
    }
    assertThat(outcomes).hasSizeGreaterThan(5);
    // The same source gives the same draw: it is the source that decides, nothing else.
    assertThat(mixed().drawWithDecoys(3, new SeededRandomSource(9)))
        .isEqualTo(mixed().drawWithDecoys(3, new SeededRandomSource(9)));
  }

  @Test
  void SS3_theDrawUsesOnlyTheRandomSourceItIsGiven() {
    int[] calls = {0};
    RandomSource inner = new SeededRandomSource(3);
    RandomSource counting =
        new RandomSource() {
          @Override
          public int nextInt(int bound) {
            calls[0]++;
            return inner.nextInt(bound);
          }

          @Override
          public byte[] nextBytes(int length) {
            return inner.nextBytes(length);
          }
        };

    mixed().drawWithDecoys(4, counting);

    assertThat(calls[0]).isGreaterThan(4);
  }

  @Test
  void SS3_aPoolThatCannotSupplyTheWordsPlusTheDecoysIsRejected() {
    // 10 usable words, but only 4 disjoint same-category pairs: 5 rounds need 10 words and decoys.
    assertThatThrownBy(() -> mixed().drawWithDecoys(5, new SeededRandomSource(1)))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.WORD_POOL_TOO_SMALL);
              assertThat(e.details()).containsEntry("required", 10).containsEntry("available", 10);
            });
  }

  @Test
  void SS3_wordsThatNormalizeAlikeCannotBeTheDecoyOfEachOther() {
    Catalog c = new Catalog("animals");
    c.add("Cat", "animals", WordDifficulty.EASY);
    c.add("cat!", "animals", WordDifficulty.EASY);
    ImpostorWords pool = ImpostorWords.load(service(c), ImpostorDifficulty.EASY);

    assertThatThrownBy(() -> pool.drawWithDecoys(1, new SeededRandomSource(1)))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.WORD_POOL_TOO_SMALL));
  }

  @Test
  void SS3_nothingPrintsAWord() {
    WithDecoys drawn = mixed().drawWithDecoys(3, new SeededRandomSource(2));

    assertThat(drawn.toString()).doesNotContain(drawn.words().getFirst().text());
    assertThat(new ImpostorSetup(drawn.words(), drawn.decoys()).toString())
        .doesNotContain(drawn.decoys().getFirst().text());
  }
}
