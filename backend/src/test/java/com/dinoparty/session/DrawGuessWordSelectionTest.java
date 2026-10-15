package com.dinoparty.session;

import static com.dinoparty.session.SessionFixture.CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.RandomSource;
import com.dinoparty.games.drawguess.DrawGuessTestModules;
import com.dinoparty.room.RoomStatus;
import com.dinoparty.session.SessionFixture.Published;
import com.dinoparty.words.CustomWordProvider.CustomWord;
import com.dinoparty.words.TestWords;
import com.dinoparty.words.TestWords.Catalog;
import com.dinoparty.words.TestWords.Packs;
import com.dinoparty.words.WordDifficulty;
import com.dinoparty.words.WordPoolService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Word selection of Draw & Guess inside the real engine ([DG-15], WP-02): the start check, the
 * filters, the freeze at start, rematch and secrecy of the pool. Pool rules in isolation: {@code
 * WordPoolServiceTest}.
 */
class DrawGuessWordSelectionTest {

  /** Word draws always take the first unused words in pool order, so tests can predict them. */
  private static final RandomSource FIRST =
      new RandomSource() {
        @Override
        public int nextInt(int bound) {
          return 0;
        }

        @Override
        public byte[] nextBytes(int length) {
          return new byte[length];
        }
      };

  private final Catalog catalog = TestWords.legacyCatalog();
  private final Packs packs = new Packs();
  private SessionFixture f =
      new SessionFixture(DrawGuessTestModules.module(new WordPoolService(catalog, packs)));

  private void useFirstWordDraws() {
    f = new SessionFixture(DrawGuessTestModules.module(new WordPoolService(catalog, packs), FIRST));
  }

  private static Map<String, Object> settings(Object... keyValues) {
    Map<String, Object> s = new HashMap<>();
    s.put("cycles", 1);
    for (int i = 0; i < keyValues.length; i += 2) {
      s.put((String) keyValues[i], keyValues[i + 1]);
    }
    return s;
  }

  private static DomainException rejected(Runnable action) {
    try {
      action.run();
    } catch (DomainException e) {
      return e;
    }
    throw new AssertionError("expected a rejection");
  }

  @SuppressWarnings("unchecked")
  private List<String> choices() {
    return (List<String>) f.sink.ofType("WORD_CHOICES").getLast().payload().get("choices");
  }

  private UUID drawer() {
    return (UUID) f.sink.ofType("PHASE_STARTED").getLast().payload().get("drawerGuestId");
  }

  private void advance(Duration d) {
    f.clock.advance(d);
    f.scheduler.fireDue(f.clock, false);
  }

  /** Plays every remaining round: choose word 0, let drawing and the summary time out. */
  private List<String> playToTheEnd() {
    List<String> chosen = new ArrayList<>();
    while (f.room.status == RoomStatus.PLAYING) {
      chosen.add(choices().getFirst());
      f.sessions.submit(CODE, drawer(), "CHOOSE_WORD", f.phaseId(), Map.of("wordIndex", 0));
      advance(Duration.ofSeconds(80));
      advance(Duration.ofSeconds(6));
    }
    return chosen;
  }

  private Set<String> offered() {
    Set<String> words = new HashSet<>();
    for (Published p : f.sink.ofType("WORD_CHOICES")) {
      @SuppressWarnings("unchecked")
      List<String> c = (List<String>) p.payload().get("choices");
      words.addAll(c);
    }
    return words;
  }

  private Set<String> catalogWords(Collection<String> categories, Set<WordDifficulty> levels) {
    Set<String> words = new HashSet<>();
    catalog.entries.stream()
        .filter(w -> categories.contains(w.categoryKey()) && levels.contains(w.difficulty()))
        .forEach(w -> words.add(w.text()));
    return words;
  }

  private void assertNothingInitialized() {
    assertThat(f.room.status).isEqualTo(RoomStatus.WAITING);
    assertThat(f.room.changes).isEmpty();
    assertThat(f.sink.events).isEmpty();
    assertThat(f.recorder.starts).isEmpty();
    assertThat(f.sessions.activeCount()).isZero();
    assertThat(f.scheduler.liveHandles()).isZero();
  }

  // ------------------------------------------------------------------ start check

  @Test
  void DG15_aTooSmallPoolRejectsTheStartWithCountsAndInitializesNothing() {
    UUID pack = packs.add("comet", "Comet", "planet");
    f.players(2);
    f.room.settings = settings("wordSource", "custom", "wordPackIds", List.of(pack.toString()));

    DomainException e = rejected(() -> f.sessions.start(CODE, f.host()));

    assertThat(e.code()).isEqualTo(ErrorCode.WORD_POOL_TOO_SMALL);
    assertThat(e.details()).isEqualTo(Map.of("required", 3, "available", 2));
    assertThat(e.getMessage()).doesNotContain("comet").doesNotContain("planet");
    assertNothingInitialized();

    packs.packs.get(pack).add(new CustomWord("asteroid", null, null));
    f.startAndPlay();
    assertThat(f.room.status).isEqualTo(RoomStatus.PLAYING);
    assertThat(choices()).containsExactlyInAnyOrder("comet", "planet", "asteroid");
  }

  @Test
  void DG15_invalidWordSettingsRejectTheStartAndInitializeNothing() {
    f.players(2);
    f.room.settings = settings("wordCategories", List.of("animals", "retired-category"));
    DomainException category = rejected(() -> f.sessions.start(CODE, f.host()));
    assertThat(category.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(category.fieldErrors())
        .extracting("field")
        .containsExactly("settings.wordCategories");

    f.room.settings = settings("wordSource", "combined", "wordPackIds", List.of());
    DomainException packless = rejected(() -> f.sessions.start(CODE, f.host()));
    assertThat(packless.fieldErrors()).extracting("field").containsExactly("settings.wordPackIds");

    UUID foreign = packs.add("one", "two", "three");
    packs.owners.put(foreign, UUID.randomUUID()); // somebody else's pack
    f.room.settings = settings("wordSource", "custom", "wordPackIds", List.of(foreign.toString()));
    assertThat(rejected(() -> f.sessions.start(CODE, f.host())).code())
        .isEqualTo(ErrorCode.VALIDATION_FAILED);

    assertNothingInitialized();
  }

  // ------------------------------------------------------------------ filters in a game

  @Test
  void DG15_everyOfferedWordMatchesTheCategoryAndDifficultyFilters() {
    f.players(3);
    f.room.settings =
        settings("cycles", 3, "wordCategories", List.of("animals", "nature"), "difficulty", "hard");
    f.startAndPlay();
    List<String> chosen = playToTheEnd();

    assertThat(chosen).hasSize(9).doesNotHaveDuplicates();
    assertThat(catalogWords(List.of("animals", "nature"), Set.of(WordDifficulty.HARD)))
        .containsAll(offered());
  }

  @Test
  void DG15_combinedGamesOfferBuiltInAndPackWords() {
    UUID pack = packs.add("volcano", "rainbow", "lighthouse");
    f.players(2);
    f.room.settings =
        settings(
            "wordSource",
            "combined",
            "wordCategories",
            List.of("objects"),
            "difficulty",
            "easy",
            "wordPackIds",
            List.of(pack.toString()));
    f.startAndPlay();
    playToTheEnd();

    Set<String> allowed = catalogWords(List.of("objects"), Set.of(WordDifficulty.EASY));
    allowed.addAll(List.of("volcano", "rainbow", "lighthouse"));
    assertThat(allowed).containsAll(offered());
  }

  // ------------------------------------------------------------------ freeze, rematch

  @Test
  void DG15_aRunningGameIgnoresLaterSettingsAndPackChanges() {
    UUID pack = packs.add("sun", "moon", "star", "comet");
    UUID other = packs.add("apple", "pear", "plum");
    f.players(2);
    f.room.settings = settings("wordSource", "custom", "wordPackIds", List.of(pack.toString()));
    f.sessions.start(CODE, f.host());

    // During the countdown and the game: another pack is selected and the pack is edited.
    f.room.settings = settings("wordSource", "custom", "wordPackIds", List.of(other.toString()));
    packs.packs.get(pack).clear();
    packs.packs.get(pack).add(new CustomWord("galaxy", null, null));
    advance(Duration.ofSeconds(3));
    List<String> chosen = playToTheEnd();

    assertThat(List.of("sun", "moon", "star", "comet")).containsAll(offered());
    assertThat(chosen).hasSize(2).doesNotHaveDuplicates();
    @SuppressWarnings("unchecked")
    Map<String, Object> started =
        (Map<String, Object>) f.sink.ofType("GAME_STARTED").getFirst().payload().get("settings");
    assertThat(started.get("wordPackIds")).isEqualTo(List.of(pack.toString()));
  }

  @Test
  void DG15_aRematchUsesTheCurrentSettingsAndAFreshUsedSet() {
    useFirstWordDraws();
    UUID pack = packs.add("aaa", "bbb", "ccc", "ddd", "eee");
    f.players(2);
    f.room.settings = settings("wordSource", "custom", "wordPackIds", List.of(pack.toString()));
    f.startAndPlay();
    assertThat(choices()).containsExactly("aaa", "bbb", "ccc");
    assertThat(playToTheEnd()).containsExactly("aaa", "bbb"); // round 2 offered bbb, ccc, ddd

    f.sessions.rematchVote(CODE, f.host(), true);
    f.startAndPlay();
    // A kept used set would offer ccc, ddd, eee; the new session starts empty ([DG-15]).
    assertThat(choices()).containsExactly("aaa", "bbb", "ccc");
    playToTheEnd();

    // Settings changed between games apply to the next one.
    UUID next = packs.add("xxx", "yyy", "zzz");
    f.sessions.rematchVote(CODE, f.host(), true);
    f.room.settings = settings("wordSource", "custom", "wordPackIds", List.of(next.toString()));
    f.startAndPlay();
    assertThat(choices()).containsExactly("xxx", "yyy", "zzz");
  }

  @Test
  void DG15_exhaustingASmallPoolRepeatsWordsInsteadOfStopping() {
    UUID pack = packs.add("sun", "moon", "star", "comet");
    f.players(3);
    f.room.settings =
        settings("cycles", 2, "wordSource", "custom", "wordPackIds", List.of(pack.toString()));
    f.startAndPlay();
    List<String> chosen = playToTheEnd();

    assertThat(chosen).hasSize(6); // 6 rounds from 4 words
    assertThat(new HashSet<>(chosen)).hasSizeLessThan(6);
    assertThat(List.of("sun", "moon", "star", "comet")).containsAll(chosen);
    assertThat(f.sink.ofType("WORD_CHOICES"))
        .allSatisfy(p -> assertThat((List<?>) p.payload().get("choices")).doesNotHaveDuplicates());
  }

  // ------------------------------------------------------------------ secrecy

  @Test
  void DG2_poolWordsReachOnlyTheDrawerUntilTheyAreRevealed() {
    UUID pack = packs.add("sun", "moon", "star", "comet", "planet", "nebula");
    List<UUID> players = f.players(3);
    f.room.settings = settings("wordSource", "custom", "wordPackIds", List.of(pack.toString()));
    f.startAndPlay();
    List<String> chosen = playToTheEnd();

    Set<String> pool = Set.of("sun", "moon", "star", "comet", "planet", "nebula");
    for (Published p : f.sink.events) {
      Set<String> mentioned = new HashSet<>();
      collect(p.payload(), pool, mentioned);
      if (p.isPublic()) {
        // Public: only the words of finished rounds, in ROUND_ENDED.
        if (!mentioned.isEmpty()) {
          assertThat(p.type()).isEqualTo("ROUND_ENDED");
          assertThat(chosen).containsAll(mentioned);
        }
      } else if (!mentioned.isEmpty()) {
        assertThat(p.type()).isIn("WORD_CHOICES", "DRAWER_WORD");
        assertThat(players).contains(p.recipient());
      }
    }
    // Settings carry pack ids, never words.
    Set<String> inSettings = new HashSet<>();
    collect(f.sink.ofType("GAME_STARTED").getFirst().payload(), pool, inSettings);
    assertThat(inSettings).isEmpty();
  }

  private static void collect(Object value, Set<String> pool, Set<String> found) {
    switch (value) {
      case String s when pool.contains(s) -> found.add(s);
      case Map<?, ?> m -> m.values().forEach(v -> collect(v, pool, found));
      case Collection<?> c -> c.forEach(v -> collect(v, pool, found));
      case null, default -> {}
    }
  }
}
