package com.dinoparty.games.drawguess;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.words.DifficultyFilter;
import com.dinoparty.words.TestWords;
import com.dinoparty.words.WordCatalogTypes;
import com.dinoparty.words.WordKey;
import com.dinoparty.words.WordSelection;
import com.dinoparty.words.WordSource;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pure Draw & Guess rules: [DG-4] normalization, [DG-5] close, [DG-12]/[DG-13], [DG-3] hint. */
class DrawGuessRulesTest {

  private static final String MIX = WordCatalogTypes.RANDOM_MIX;
  private static final UUID PACK = UUID.fromString("6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b");

  // ------------------------------------------------------------------ [DG-4] / [DG-5]

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Ice Cream|ice cream",
        "  ICE   cream!! |ice cream",
        "crème brûlée|creme brulee",
        "tug-of-war|tug of war",
        "snake_case|snake case",
        "ＦＵＬＬＷＩＤＴＨ|fullwidth",
        "Ⅻ o'clock|xii oclock",
        "ﬁsh|fish",
        "...|''",
        "dino 42|dino 42",
      })
  void DG4_normalization(String input, String expected) {
    assertThat(GuessMatcher.normalize(input)).isEqualTo(expected);
  }

  @Test
  void DG4_correctIgnoresSpacesAndCase() {
    String word = GuessMatcher.normalize("ice cream");
    assertThat(GuessMatcher.isCorrect(GuessMatcher.normalize("ICECREAM"), word)).isTrue();
    assertThat(GuessMatcher.isCorrect(GuessMatcher.normalize("ice-cream"), word)).isTrue();
    assertThat(GuessMatcher.isCorrect(GuessMatcher.normalize("ice creams"), word)).isFalse();
  }

  @Test
  void DG4_sanitizeStripsControlAndFormatCharactersThenTrims() {
    assertThat(GuessMatcher.sanitize("  he\u0000l​lo‮ \n")).isEqualTo("hello");
    assertThat(GuessMatcher.length("🦖".repeat(80))).isEqualTo(80);
  }

  @Test
  void DG5_closeNeedsSixLettersAndDistanceExactlyOne() {
    String word = GuessMatcher.normalize("elephant");
    assertThat(GuessMatcher.isClose("elefant", word)).isFalse(); // distance 2
    assertThat(GuessMatcher.isClose("elephan", word)).isTrue(); // deletion
    assertThat(GuessMatcher.isClose("elephants", word)).isTrue(); // insertion
    assertThat(GuessMatcher.isClose("elephent", word)).isTrue(); // substitution
    assertThat(GuessMatcher.isClose("elephant", word)).isFalse(); // that is correct, not close
    assertThat(GuessMatcher.isClose("cats", GuessMatcher.normalize("cat"))).isFalse(); // short
    assertThat(GuessMatcher.isClose("ice crem", GuessMatcher.normalize("ice cream"))).isTrue();
  }

  @Test
  void oneEditApartAgreesWithADirectLevenshtein() {
    List<String> samples = List.of("", "a", "ab", "ba", "abc", "acb", "abcd", "xbc", "abx", "bc");
    for (String a : samples) {
      for (String b : samples) {
        assertThat(GuessMatcher.oneEditApart(a, b)).as(a + "/" + b).isEqualTo(lev(a, b) == 1);
      }
    }
  }

  private static int lev(String a, String b) {
    int[][] d = new int[a.length() + 1][b.length() + 1];
    for (int i = 0; i <= a.length(); i++) {
      d[i][0] = i;
    }
    for (int j = 0; j <= b.length(); j++) {
      d[0][j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
      }
    }
    return d[a.length()][b.length()];
  }

  // ------------------------------------------------------------------ [DG-12] / [DG-13]

  @Test
  void DG12_guesserPointsExamplesAndBoundaries() {
    assertThat(Scoring.guesserPoints(60_000, 80, 0)).isEqualTo(400);
    assertThat(Scoring.guesserPoints(60_000, 80, 2)).isEqualTo(350);
    assertThat(Scoring.guesserPoints(80_000, 80, 0)).isEqualTo(500);
    assertThat(Scoring.guesserPoints(0, 80, 0)).isEqualTo(100);
    assertThat(Scoring.guesserPoints(1, 80, 0)).isEqualTo(100);
    assertThat(Scoring.guesserPoints(0, 80, 9)).isEqualTo(50); // floor
    assertThat(Scoring.guesserPoints(-5, 80, 0)).isEqualTo(100); // clamped
    // Half-up: 100 + 400 × 100/80000 = 100.5 → 101.
    assertThat(Scoring.guesserPoints(100, 80, 0)).isEqualTo(101);
    assertThat(Scoring.guesserPoints(99, 80, 0)).isEqualTo(100);
  }

  @Test
  void DG13_drawerPointsExamplesAndBoundaries() {
    assertThat(Scoring.drawerPoints(3, 4)).isEqualTo(75);
    assertThat(Scoring.drawerPoints(4, 4)).isEqualTo(150);
    assertThat(Scoring.drawerPoints(1, 1)).isEqualTo(150);
    assertThat(Scoring.drawerPoints(0, 4)).isZero();
    assertThat(Scoring.drawerPoints(1, 3)).isEqualTo(33);
    assertThat(Scoring.drawerPoints(2, 3)).isEqualTo(67);
    assertThat(Scoring.drawerPoints(1, 8)).isEqualTo(13); // 12.5 → 13
    assertThat(Scoring.drawerPoints(0, 0)).isZero();
  }

  // ------------------------------------------------------------------ [DG-3] hint

  @Test
  void DG3_hintKeepsSeparatorsAndRevealsAtMostHalf() {
    String word = "tug-of-war";
    String hint = HintMasker.initial(word);
    assertThat(hint).isEqualTo("___-__-___");
    assertThat(HintMasker.maxRevealed(word)).isEqualTo(4);

    for (long seed = 0; seed < 50; seed++) {
      SeededRandomSource random = new SeededRandomSource(seed);
      String h = hint;
      for (int i = 0; i < 10; i++) {
        h = HintMasker.reveal(word, h, random);
      }
      assertThat(HintMasker.revealed(h)).isEqualTo(4);
      for (int i = 0; i < word.length(); i++) {
        char c = h.charAt(i);
        assertThat(c == '_' || c == word.charAt(i)).isTrue();
      }
      assertThat(h.charAt(3)).isEqualTo('-');
    }
    assertThat(HintMasker.initial("ice cream")).isEqualTo("___ _____");
    assertThat(HintMasker.maxRevealed("cat")).isEqualTo(2);
  }

  @Test
  void DG3_hintRevealIsDeterministicForASeed() {
    String a = HintMasker.reveal("dinosaur", "________", new SeededRandomSource(5));
    String b = HintMasker.reveal("dinosaur", "________", new SeededRandomSource(5));
    assertThat(a).isEqualTo(b);
    assertThat(HintMasker.revealed(a)).isEqualTo(1);
  }

  // ------------------------------------------------------------------ [DG-15] word pool

  /** The pool's duplicate key is the [DG-4] correct-guess equivalence (pool: words module). */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Ice Cream",
        "ice-cream",
        "icecream",
        "crème brûlée",
        "Café",
        "ＦＵＬＬＷＩＤＴＨ",
        "ﬁsh",
        "İstanbul",
        "tug-of-war",
        "snake_case",
        "dino 42",
      })
  void DG15_poolDuplicatesAreExactlyTheWordsOneGuessMatches(String word) {
    assertThat(WordKey.of(word)).isEqualTo(GuessMatcher.spaceless(GuessMatcher.normalize(word)));
  }

  @Test
  void DG15_theLegacyWordListsAreGuessableDistinctWords() {
    Set<String> keys = new HashSet<>();
    for (String resource :
        List.of("/games/drawguess/words-easy.txt", "/games/drawguess/words-hard.txt")) {
      for (String w : TestWords.read(resource)) {
        assertThat(w).hasSizeBetween(3, 20).matches("[a-z]+(?:[ -][a-z]+)*");
        assertThat(HintMasker.letters(w)).isGreaterThanOrEqualTo(2);
        assertThat(keys.add(WordKey.of(w))).as(w).isTrue();
      }
    }
  }

  // ------------------------------------------------------------------ settings

  @Test
  void settingsFallBackToTheCatalogDefaults() {
    WordSelection defaultWords =
        new WordSelection(WordSource.BUILT_IN, List.of(MIX), DifficultyFilter.MIXED, List.of());
    assertThat(DrawGuessSettings.from(Map.of()))
        .isEqualTo(
            new DrawGuessSettings(
                2,
                80,
                15,
                true,
                new WordSelection(
                    WordSource.BUILT_IN, List.of(), DifficultyFilter.MIXED, List.of())));
    assertThat(DrawGuessSettings.from(DrawGuessSettings.defaults()))
        .isEqualTo(new DrawGuessSettings(2, 80, 15, true, defaultWords));
    Map<String, Object> custom = new HashMap<>();
    custom.put("cycles", 5);
    custom.put("drawSeconds", 30L);
    custom.put("wordChoiceSeconds", 99);
    custom.put("hintReveal", false);
    custom.put("difficulty", "hard");
    custom.put("wordSource", "combined");
    custom.put("wordCategories", List.of("animals", "sports"));
    custom.put("wordPackIds", List.of(PACK.toString()));
    assertThat(DrawGuessSettings.from(custom))
        .isEqualTo(
            new DrawGuessSettings(
                5,
                30,
                15,
                false,
                new WordSelection(
                    WordSource.COMBINED,
                    List.of("animals", "sports"),
                    DifficultyFilter.HARD,
                    List.of(PACK))));
    assertThat(DrawGuessSettings.defaults())
        .isEqualTo(
            Map.of(
                "cycles",
                2,
                "drawSeconds",
                80,
                "wordChoiceSeconds",
                15,
                "hintReveal",
                true,
                "difficulty",
                "mixed",
                "wordSource",
                "built-in",
                "wordCategories",
                List.of(MIX),
                "wordPackIds",
                List.of()));
  }

  @Test
  void DG15_everyDifficultySettingMapsToItsCatalogFilter() {
    for (String value : List.of("easy", "medium", "hard", "mixed")) {
      assertThat(DrawGuessSettings.from(Map.of("difficulty", value)).words().difficulty().name())
          .isEqualTo(value.toUpperCase(java.util.Locale.ROOT));
    }
  }

  @Test
  void unusableWordSettingsFallBackInsteadOfFailingTheGame() {
    Map<String, Object> broken = new HashMap<>();
    broken.put("difficulty", "extreme");
    broken.put("wordSource", "BUILT_IN");
    broken.put("wordCategories", List.of("animals", 7));
    broken.put("wordPackIds", List.of("not-a-uuid"));

    assertThat(DrawGuessSettings.from(broken).words())
        .isEqualTo(
            new WordSelection(WordSource.BUILT_IN, List.of(), DifficultyFilter.MIXED, List.of()));
  }
}
