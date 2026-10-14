package com.dinoparty.session;

import static com.dinoparty.session.SessionFixture.CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.common.Timestamps;
import com.dinoparty.engine.GameDefinition;
import com.dinoparty.engine.RoundRecord;
import com.dinoparty.games.drawguess.DrawGuessModule;
import com.dinoparty.room.RoomStatus;
import com.dinoparty.session.SessionFixture.Published;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Draw & Guess running inside the real engine with a manual clock and in-memory collaborators:
 * transitions T1..T10 of docs/draw-guess-design.md §3, the rejection cells of §4 and the rules
 * [DG-1]..[DG-18]. The WebSocket path is covered by {@code DrawGuessIT}.
 */
class DrawGuessSessionTest {

  private final SessionFixture f = new SessionFixture(new DrawGuessModule());

  // ------------------------------------------------------------------ helpers

  private List<UUID> start(int players) {
    List<UUID> ids = f.players(players);
    f.startAndPlay();
    return ids;
  }

  @SuppressWarnings("unchecked")
  private List<UUID> order() {
    return (List<UUID>) f.sink.ofType("GAME_STARTED").getLast().payload().get("order");
  }

  private Map<String, Object> last(String type) {
    return f.sink.ofType(type).getLast().payload();
  }

  private String phase() {
    return (String) last("PHASE_STARTED").get("phase");
  }

  private UUID drawer() {
    return (UUID) last("PHASE_STARTED").get("drawerGuestId");
  }

  private List<UUID> guessers() {
    List<UUID> g = new ArrayList<>(order());
    g.remove(drawer());
    return g;
  }

  @SuppressWarnings("unchecked")
  private List<String> choices() {
    return (List<String>) last("WORD_CHOICES").get("choices");
  }

  private void submit(UUID guest, String type, Map<String, Object> payload) {
    f.sessions.submit(CODE, guest, type, f.phaseId(), payload);
  }

  private void guess(UUID guest, String text) {
    submit(guest, "SUBMIT_GUESS", Map.of("text", text));
  }

  private String choose(int index) {
    String word = choices().get(index);
    submit(drawer(), "CHOOSE_WORD", Map.of("wordIndex", index));
    return word;
  }

  /** Chooses the longest of the three words, so [DG-5] "close" applies when possible. */
  private String chooseLongest() {
    List<String> c = choices();
    int best = 0;
    for (int i = 1; i < c.size(); i++) {
      if (c.get(i).replace(" ", "").length() > c.get(best).replace(" ", "").length()) {
        best = i;
      }
    }
    return choose(best);
  }

  /** Moves the clock and lets the timer thread fire what is due. */
  private void advance(Duration d) {
    f.clock.advance(d);
    f.scheduler.fireDue(f.clock, false);
  }

  private static ErrorCode rejected(Runnable action) {
    try {
      action.run();
    } catch (DomainException e) {
      return e.code();
    }
    throw new AssertionError("expected a rejection");
  }

  private static Map<String, Object> stroke(UUID id, int batchNo, int points, boolean last) {
    List<List<Integer>> pts = new ArrayList<>();
    for (int i = 0; i < points; i++) {
      pts.add(List.of(10_000, 10_000));
    }
    Map<String, Object> p = new HashMap<>();
    p.put("strokeId", id.toString());
    p.put("batchNo", batchNo);
    p.put("tool", "PENCIL");
    p.put("color", "#123456");
    p.put("size", 2);
    p.put("points", pts);
    p.put("final", last);
    return p;
  }

  private Map<UUID, Map<String, Object>> views(Collection<UUID> viewers) {
    Map<UUID, Map<String, Object>> views = new LinkedHashMap<>();
    viewers.forEach(v -> views.put(v, f.sessions.gameSnapshot(CODE, v)));
    return views;
  }

  /** True if {@code word} occurs as a value anywhere in a payload (maps, lists, strings). */
  private static boolean containsValue(Object value, String word) {
    return switch (value) {
      case null -> false;
      case String s -> s.equals(word) || s.equalsIgnoreCase(word);
      case Map<?, ?> m -> m.values().stream().anyMatch(v -> containsValue(v, word));
      case Collection<?> c -> c.stream().anyMatch(v -> containsValue(v, word));
      default -> false;
    };
  }

  private void leave(UUID guest) {
    f.room.removeMember(guest);
    f.sessions.onMemberLeft(CODE, guest);
  }

  // ------------------------------------------------------------------ registration and start

  @Test
  void theModuleDeclaresTheContractDefinition() {
    GameDefinition d = new DrawGuessModule().definition();

    assertThat(d.key()).isEqualTo("draw-guess");
    assertThat(d.minPlayers()).isEqualTo(2);
    assertThat(d.maxPlayers()).isEqualTo(10);
    assertThat(d.minActivePlayers()).isEqualTo(2);
    assertThat(d.scored()).isTrue();
    assertThat(d.commandsByType())
        .containsOnlyKeys("CHOOSE_WORD", "DRAW_STROKE", "DRAW_UNDO", "DRAW_CLEAR", "SUBMIT_GUESS");
    var stroke = d.commandsByType().get("DRAW_STROKE");
    assertThat(stroke.maxPayloadBytes()).isEqualTo(8 * 1024);
    assertThat(stroke.ratePerSecond()).isEqualTo(30.0);
    assertThat(stroke.burst()).isEqualTo(40);
    assertThat(stroke.phases()).containsExactly("DRAWING");
    assertThat(d.commandsByType().get("CHOOSE_WORD").phases()).containsExactly("WORD_CHOICE");
    assertThat(d.publicEvents())
        .containsExactlyInAnyOrder(
            "DRAWING_UPDATED", "HINT_UPDATED", "GUESS_ACCEPTED", "GUESS_CHAT", "ROUND_ENDED");
    assertThat(d.privateEvents())
        .containsExactlyInAnyOrder("WORD_CHOICES", "DRAWER_WORD", "GUESS_FEEDBACK", "GUESSER_CHAT");
  }

  @Test
  void DG2_startingOpensWordChoiceAndOnlyTheDrawerGetsTheChoices() {
    List<UUID> p = start(3);
    Instant now = f.clock.instant();

    assertThat(last("GAME_STARTED").get("gameKey")).isEqualTo("draw-guess");
    Map<String, Object> phase = last("PHASE_STARTED");
    assertThat(phase.get("phase")).isEqualTo("WORD_CHOICE");
    assertThat(phase.get("round")).isEqualTo(1);
    assertThat(phase.get("totalRounds")).isEqualTo(6); // 3 players × 2 cycles
    assertThat(phase.get("drawerGuestId")).isEqualTo(order().getFirst());
    assertThat(phase.get("deadlineAt")).isEqualTo(Timestamps.format(now.plusSeconds(15)));
    assertThat(phase).doesNotContainKeys("choices", "word", "hint");

    Published choices = f.sink.ofType("WORD_CHOICES").getFirst();
    assertThat(f.sink.ofType("WORD_CHOICES")).hasSize(1);
    assertThat(choices.recipient()).isEqualTo(drawer());
    assertThat(choices.payload().get("phaseId")).isEqualTo(phase.get("phaseId"));
    assertThat(choices.payload().get("deadlineAt")).isEqualTo(phase.get("deadlineAt"));
    assertThat(choices()).hasSize(3).doesNotHaveDuplicates();
    // The public PHASE_STARTED precedes the private secret of its phase.
    assertThat(f.sink.events.indexOf(choices))
        .isGreaterThan(f.sink.events.indexOf(f.sink.ofType("PHASE_STARTED").getLast()));
    assertThat(p).containsExactlyInAnyOrderElementsOf(order());
  }

  @Test
  void playerCountIsValidatedAgainstTheGameBounds() {
    f.players(1);
    f.room.addMember(false);
    assertThat(rejected(() -> f.sessions.start(CODE, f.host())))
        .isEqualTo(ErrorCode.NOT_ENOUGH_PLAYERS);

    SessionFixture crowded = new SessionFixture(new DrawGuessModule());
    crowded.players(11);
    assertThat(rejected(() -> crowded.sessions.start(CODE, crowded.host())))
        .isEqualTo(ErrorCode.NOT_ENOUGH_PLAYERS); // more members than maxPlayers 10
    assertThat(crowded.room.status).isEqualTo(RoomStatus.WAITING);

    SessionFixture ten = new SessionFixture(new DrawGuessModule());
    ten.players(10);
    ten.startAndPlay();
    assertThat(ten.room.status).isEqualTo(RoomStatus.PLAYING);
  }

  // ------------------------------------------------------------------ [DG-1] rotation

  @Test
  void DG1_drawersFollowThePlayOrderRepeatedPerCycle() {
    start(3);
    List<UUID> drawers = new ArrayList<>();
    while (f.room.status == RoomStatus.PLAYING) {
      drawers.add(drawer());
      advance(Duration.ofSeconds(15)); // auto-pick
      advance(Duration.ofSeconds(80)); // time runs out
      advance(Duration.ofSeconds(6)); // summary
    }

    List<UUID> expected = new ArrayList<>(order());
    expected.addAll(order());
    assertThat(drawers).isEqualTo(expected);
    assertThat(f.recorder.rounds).hasSize(6);
    assertThat(f.recorder.rounds)
        .allSatisfy(
            r -> {
              assertThat(r.endReason()).isEqualTo("TIME");
              assertThat(r.meta().get("autoPicked")).isEqualTo(true);
            });
    assertThat(f.recorder.rounds.stream().map(r -> r.meta().get("word")).toList())
        .doesNotHaveDuplicates(); // [DG-15] no word twice in a session
    assertThat(last("GAME_FINISHED").get("endReason")).isEqualTo("COMPLETED");
    assertThat(f.room.status).isEqualTo(RoomStatus.RESULTS);
  }

  @Test
  void DG1_aLeftPlayersTurnIsSkippedAndTotalRoundsShrinks() {
    f.room.settings = Map.of("cycles", 1);
    start(3);
    UUID gone = order().get(2);
    assertThat(last("PHASE_STARTED").get("totalRounds")).isEqualTo(3);

    leave(gone); // a guesser leaves during WORD_CHOICE: the round goes on
    assertThat(phase()).isEqualTo("WORD_CHOICE");
    advance(Duration.ofSeconds(15));
    assertThat(last("PHASE_STARTED").get("totalRounds")).isEqualTo(2);
    advance(Duration.ofSeconds(80));
    assertThat(phase()).isEqualTo("ROUND_END");
    advance(Duration.ofSeconds(6));
    assertThat(drawer()).isEqualTo(order().get(1));
    assertThat(last("PHASE_STARTED").get("round")).isEqualTo(2);
    advance(Duration.ofSeconds(15));
    advance(Duration.ofSeconds(80));
    advance(Duration.ofSeconds(6));

    assertThat(f.room.status).isEqualTo(RoomStatus.RESULTS);
    assertThat(f.recorder.rounds).hasSize(2);
    assertThat(f.sink.ofType("PHASE_STARTED").stream().map(e -> e.payload().get("drawerGuestId")))
        .doesNotContain(gone);
  }

  // ------------------------------------------------------------------ [DG-2] word choice

  @Test
  void DG2_onlyTheDrawerChoosesAndBadIndexesChangeNothing() {
    start(3);
    UUID guesser = guessers().getFirst();
    String before = f.phaseId();

    assertThat(rejected(() -> submit(guesser, "CHOOSE_WORD", Map.of("wordIndex", 0))))
        .isEqualTo(ErrorCode.NOT_DRAWER);
    for (Object bad : new Object[] {3, -1, "1", 1.0}) {
      assertThat(rejected(() -> submit(drawer(), "CHOOSE_WORD", Map.of("wordIndex", bad))))
          .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
    assertThat(rejected(() -> submit(drawer(), "CHOOSE_WORD", Map.of())))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> guess(guesser, "hello"))).isEqualTo(ErrorCode.INVALID_PHASE);
    assertThat(f.phaseId()).isEqualTo(before);
    assertThat(phase()).isEqualTo("WORD_CHOICE");

    String word = choose(1);

    Map<String, Object> drawing = last("PHASE_STARTED");
    assertThat(drawing.get("phase")).isEqualTo("DRAWING");
    assertThat(drawing.get("hint")).isEqualTo(word.replaceAll("[a-z]", "_"));
    Published secret = f.sink.ofType("DRAWER_WORD").getLast();
    assertThat(secret.recipient()).isEqualTo(drawer());
    assertThat(secret.payload())
        .containsEntry("word", word)
        .containsEntry("phaseId", drawing.get("phaseId"));
    // Replaying the choice: the old phase is gone (STALE), the new one does not allow it.
    String chosenPhase = f.phaseId();
    assertThat(
            rejected(
                () ->
                    f.sessions.submit(
                        CODE, drawer(), "CHOOSE_WORD", before, Map.of("wordIndex", 0))))
        .isEqualTo(ErrorCode.STALE_ACTION);
    assertThat(rejected(() -> submit(drawer(), "CHOOSE_WORD", Map.of("wordIndex", 0))))
        .isEqualTo(ErrorCode.INVALID_PHASE);
    assertThat(f.phaseId()).isEqualTo(chosenPhase);
  }

  @Test
  void DG2_theServerPicksWhenTheChoiceDeadlinePassesAndALateChoiceIsStale() {
    start(2);
    List<String> offered = choices();
    String wordChoicePhase = f.phaseId();
    UUID drawer = drawer();

    f.clock.advance(Duration.ofSeconds(15)); // exactly at the deadline, timer not fired yet
    assertThat(
            rejected(
                () ->
                    f.sessions.submit(
                        CODE, drawer, "CHOOSE_WORD", wordChoicePhase, Map.of("wordIndex", 0))))
        .isEqualTo(ErrorCode.STALE_ACTION);

    assertThat(phase()).isEqualTo("DRAWING");
    String word = (String) last("DRAWER_WORD").get("word");
    assertThat(offered).contains(word);
    advance(Duration.ofSeconds(80));
    RoundRecord round = f.recorder.rounds.getFirst();
    assertThat(round.meta()).containsEntry("autoPicked", true).containsEntry("word", word);
  }

  // ------------------------------------------------------------------ secrecy

  @Test
  void DG18_theWordAndChoicesNeverReachAnyoneButTheDrawerBeforeTheRoundEnds() {
    List<UUID> p = start(3);
    UUID drawer = drawer();
    List<String> offered = choices();
    String word = chooseLongest();
    List<String> unchosen = new ArrayList<>(offered);
    unchosen.remove(word);
    UUID a = guessers().get(0);
    UUID b = guessers().get(1);

    // Snapshots during DRAWING: drawer only.
    for (UUID viewer : p) {
      Map<String, Object> view = f.sessions.gameSnapshot(CODE, viewer);
      assertThat(view.get("word")).isEqualTo(viewer.equals(drawer) ? word : null);
      assertThat(view.get("wordChoices")).isNull();
    }
    guess(a, "definitely not it");
    guess(a, word.toUpperCase()); // correct: a is now a solver
    assertThat(f.sessions.gameSnapshot(CODE, a).get("word")).isNull(); // solvers do not get it
    // Rejected guesses never echo the word in an error.
    try {
      submit(drawer, "SUBMIT_GUESS", Map.of("text", word));
    } catch (DomainException e) {
      assertThat(e.getMessage()).doesNotContainIgnoringCase(word);
    }
    int endIndex = f.sink.events.indexOf(f.sink.ofType("PHASE_STARTED").getLast());
    guess(b, word); // ends the round
    int roundEnded = f.sink.events.indexOf(f.sink.ofType("ROUND_ENDED").getFirst());
    assertThat(roundEnded).isGreaterThan(endIndex);

    for (int i = 0; i < roundEnded; i++) {
      Published e = f.sink.events.get(i);
      if (containsValue(e.payload(), word)) {
        assertThat(e.recipient()).as(e.type()).isEqualTo(drawer);
        assertThat(e.type()).isIn("WORD_CHOICES", "DRAWER_WORD");
      }
    }
    for (Published e : f.sink.events) {
      for (String other : unchosen) {
        if (containsValue(e.payload(), other)) {
          assertThat(e.type()).isEqualTo("WORD_CHOICES");
          assertThat(e.recipient()).isEqualTo(drawer);
        }
      }
    }
    assertThat(last("ROUND_ENDED").get("word")).isEqualTo(word);
    for (RoundRecord r : f.recorder.rounds) {
      unchosen.forEach(w -> assertThat(containsValue(r.meta(), w)).isFalse());
    }
    // In ROUND_END the word is public.
    p.forEach(v -> assertThat(f.sessions.gameSnapshot(CODE, v).get("word")).isEqualTo(word));
  }

  // ------------------------------------------------------------------ guesses and scoring

  @Test
  void DG4_DG12_DG13_guessesAreClassifiedScoredAndEndTheRoundWhenAllSolved() {
    start(3);
    UUID drawer = drawer();
    String word = chooseLongest();
    UUID a = guessers().get(0);
    UUID b = guessers().get(1);
    advance(Duration.ofSeconds(20)); // 60 s left

    guess(a, "zzzz qqq");
    Map<String, Object> chat = last("GUESS_CHAT");
    assertThat(chat).containsEntry("guestId", a).containsEntry("text", "zzzz qqq");

    int before = f.sink.ofType("GUESS_CHAT").size();
    guess(b, "ZZZZ-QQQ!"); // same normalized wrong guess: private DUPLICATE, not broadcast
    assertThat(f.sink.ofType("GUESS_CHAT")).hasSize(before);
    assertThat(f.sink.ofType("GUESS_FEEDBACK").getLast().recipient()).isEqualTo(b);
    assertThat(last("GUESS_FEEDBACK")).containsEntry("result", "DUPLICATE");

    if (word.replace(" ", "").length() >= 6) {
      String near = word.substring(0, word.length() - 1) + (word.endsWith("x") ? "y" : "x");
      guess(a, near);
      assertThat(f.sink.ofType("GUESS_FEEDBACK").getLast().recipient()).isEqualTo(a);
      assertThat(last("GUESS_FEEDBACK")).containsEntry("result", "CLOSE");
      assertThat(f.sink.ofType("GUESS_CHAT")).hasSize(before);
    }

    assertThat(rejected(() -> guess(drawer, word))).isEqualTo(ErrorCode.IS_DRAWER);
    assertThat(rejected(() -> guess(a, "   "))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> guess(a, "?!..."))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> guess(a, "y".repeat(81)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> submit(a, "SUBMIT_GUESS", Map.of("text", 5))))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    guess(a, "y".repeat(80)); // the boundary is accepted (as a wrong guess)

    guess(a, " " + word.toUpperCase().replace(' ', '-') + " ");
    Map<String, Object> accepted = last("GUESS_ACCEPTED");
    assertThat(accepted)
        .containsEntry("guestId", a)
        .containsEntry("scoreDelta", 400)
        .containsEntry("totalScore", 400)
        .containsEntry("order", 0)
        .doesNotContainKey("word");
    assertThat(phase()).isEqualTo("DRAWING");

    guess(a, "nice one");
    Published restricted = f.sink.ofType("GUESSER_CHAT").getLast();
    assertThat(f.sink.ofType("GUESSER_CHAT").stream().map(Published::recipient))
        .containsExactlyInAnyOrder(a, drawer);
    assertThat(restricted.payload()).containsEntry("text", "nice one");
    assertThat(f.sink.ofType("GUESS_CHAT").stream().map(e -> e.payload().get("text")))
        .doesNotContain("nice one");

    advance(Duration.ofSeconds(20)); // 40 s left
    int published = f.sink.events.size();
    guess(b, word);

    List<String> order =
        f.sink.events.subList(published, f.sink.events.size()).stream()
            .filter(Published::isPublic)
            .map(Published::type)
            .toList();
    assertThat(order).containsExactly("GUESS_ACCEPTED", "ROUND_ENDED", "PHASE_STARTED");
    assertThat(last("GUESS_ACCEPTED")).containsEntry("scoreDelta", 275).containsEntry("order", 1);
    Map<String, Object> ended = last("ROUND_ENDED");
    assertThat(ended)
        .containsEntry("reason", "ALL_GUESSED")
        .containsEntry("word", word)
        .containsEntry("drawerGuestId", drawer)
        .containsEntry("round", 1);
    assertThat(ended.get("scoreDeltas")).isEqualTo(Map.of(a, 400, b, 275, drawer, 150));
    assertThat(ended.get("scores")).isEqualTo(f.sessions.info(CODE).scores()); // ledger = deltas
    assertThat(phase()).isEqualTo("ROUND_END");

    Map<String, Object> view = f.sessions.gameSnapshot(CODE, b);
    assertThat(view.get("roundEnd"))
        .isEqualTo(Map.of("reason", "ALL_GUESSED", "scoreDeltas", ended.get("scoreDeltas")));
    assertThat(view.get("guessedGuestIds")).isEqualTo(List.of(a, b));
    assertThat(rejected(() -> guess(a, "too late"))).isEqualTo(ErrorCode.INVALID_PHASE);
  }

  @Test
  void DG9_theRoundEndsOnTimeAndAGuessAtTheDeadlineIsStale() {
    start(2);
    String word = choose(0);
    UUID a = guessers().getFirst();
    String drawingPhase = f.phaseId();

    f.clock.advance(Duration.ofSeconds(80)); // at the deadline; the timer has not fired yet
    assertThat(
            rejected(
                () ->
                    f.sessions.submit(CODE, a, "SUBMIT_GUESS", drawingPhase, Map.of("text", word))))
        .isEqualTo(ErrorCode.STALE_ACTION);

    Map<String, Object> ended = last("ROUND_ENDED");
    assertThat(ended).containsEntry("reason", "TIME").containsEntry("word", word);
    assertThat(ended.get("scoreDeltas")).isEqualTo(Map.of(a, 0, drawer(), 0));
    assertThat(f.sessions.info(CODE).scores().values()).containsOnly(0);
    assertThat(f.recorder.rounds.getFirst().endReason()).isEqualTo("TIME");
  }

  @Test
  void DG12_aGuessOneMillisecondBeforeTheDeadlineScoresTheMinimum() {
    start(2);
    String word = choose(0);
    UUID a = guessers().getFirst();
    f.clock.advance(Duration.ofSeconds(80).minusMillis(1));

    guess(a, word);

    assertThat(last("GUESS_ACCEPTED")).containsEntry("scoreDelta", 100);
    assertThat(last("ROUND_ENDED")).containsEntry("reason", "ALL_GUESSED");
    assertThat(f.sessions.info(CODE).scores()).containsEntry(drawer(), 150);
  }

  @Test
  void DG3_hintsAreRevealedAtHalfAndThreeQuartersOfTheDrawingTime() {
    start(2);
    String word = choose(0);
    String initial = (String) last("PHASE_STARTED").get("hint");
    assertThat(initial).isEqualTo(word.replaceAll("[a-z]", "_"));

    advance(Duration.ofSeconds(39));
    assertThat(f.sink.ofType("HINT_UPDATED")).isEmpty();
    advance(Duration.ofSeconds(1));
    String first = (String) last("HINT_UPDATED").get("hint");
    advance(Duration.ofSeconds(20));
    String second = (String) last("HINT_UPDATED").get("hint");

    assertThat(revealed(first)).isEqualTo(1);
    assertThat(revealed(second)).isEqualTo(2);
    for (int i = 0; i < word.length(); i++) {
      assertThat(second.charAt(i) == '_' || second.charAt(i) == word.charAt(i)).isTrue();
    }
    assertThat(f.sessions.gameSnapshot(CODE, guessers().getFirst()).get("hint")).isEqualTo(second);
  }

  @Test
  void DG3_noHintsWhenHintRevealIsOff() {
    f.room.settings = Map.of("hintReveal", false, "drawSeconds", 30);
    start(2);
    choose(0);
    advance(Duration.ofSeconds(29));
    assertThat(f.sink.ofType("HINT_UPDATED")).isEmpty();
    advance(Duration.ofSeconds(1));
    assertThat(last("ROUND_ENDED")).containsEntry("reason", "TIME");
  }

  private static long revealed(String hint) {
    return hint.chars().filter(c -> c != '_' && c != ' ' && c != '-').count();
  }

  // ------------------------------------------------------------------ drawing

  @Test
  void DG14_strokesUndoAndClearArePublishedAndRejectionsChangeNothing() {
    List<UUID> p = start(3);
    choose(0);
    UUID drawer = drawer();
    UUID guesser = guessers().getFirst();
    UUID s1 = UUID.randomUUID();

    assertThat(rejected(() -> submit(guesser, "DRAW_STROKE", stroke(s1, 0, 2, false))))
        .isEqualTo(ErrorCode.NOT_DRAWER);
    assertThat(rejected(() -> submit(guesser, "DRAW_UNDO", Map.of())))
        .isEqualTo(ErrorCode.NOT_DRAWER);
    assertThat(rejected(() -> submit(guesser, "DRAW_CLEAR", Map.of())))
        .isEqualTo(ErrorCode.NOT_DRAWER);

    submit(drawer, "DRAW_STROKE", stroke(s1, 0, 2, false));
    Map<String, Object> update = last("DRAWING_UPDATED");
    assertThat(update)
        .containsEntry("kind", "STROKE")
        .containsEntry("strokeId", s1)
        .containsEntry("batchNo", 0)
        .containsEntry("tool", "PENCIL")
        .containsEntry("final", false);
    assertThat(f.sink.ofType("DRAWING_UPDATED").getLast().isPublic()).isTrue();

    Map<UUID, Map<String, Object>> before = views(p);
    int events = f.sink.events.size();
    assertThat(rejected(() -> submit(drawer, "DRAW_STROKE", stroke(s1, 0, 1, false))))
        .isEqualTo(ErrorCode.INVALID_ACTION); // repeated batch
    assertThat(rejected(() -> submit(drawer, "DRAW_STROKE", stroke(s1, 2, 1, false))))
        .isEqualTo(ErrorCode.INVALID_ACTION); // gap
    Map<String, Object> bad = stroke(s1, 1, 1, false);
    bad.put("size", 9);
    assertThat(rejected(() -> submit(drawer, "DRAW_STROKE", bad)))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(views(p)).usingRecursiveComparison().isEqualTo(before);
    assertThat(f.sink.events).hasSize(events);

    submit(drawer, "DRAW_STROKE", stroke(s1, 1, 0, true));
    submit(drawer, "DRAW_UNDO", Map.of());
    assertThat(last("DRAWING_UPDATED")).isEqualTo(Map.of("kind", "UNDO", "strokeId", s1));
    events = f.sink.events.size();
    submit(drawer, "DRAW_UNDO", Map.of()); // empty canvas: acknowledged, nothing published
    submit(drawer, "DRAW_CLEAR", Map.of());
    assertThat(f.sink.events).hasSize(events);
    submit(drawer, "DRAW_STROKE", stroke(UUID.randomUUID(), 0, 1, true));
    submit(drawer, "DRAW_CLEAR", Map.of());
    assertThat(last("DRAWING_UPDATED")).isEqualTo(Map.of("kind", "CLEAR"));
  }

  @Test
  void DG14_aFullCanvasRejectsTheWholeBatchWithPayloadTooLarge() {
    start(2);
    choose(0);
    UUID drawer = drawer();
    for (int s = 0; s < 6; s++) { // 6 strokes × 10 batches × 200 points = 12 000
      UUID id = UUID.randomUUID();
      for (int b = 0; b < 10; b++) {
        submit(drawer, "DRAW_STROKE", stroke(id, b, 200, b == 9));
      }
    }
    int events = f.sink.events.size();

    assertThat(rejected(() -> submit(drawer, "DRAW_STROKE", stroke(UUID.randomUUID(), 0, 1, true))))
        .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
    assertThat(f.sink.events).hasSize(events);
    @SuppressWarnings("unchecked")
    Map<String, Object> budget =
        (Map<String, Object>) f.sessions.gameSnapshot(CODE, drawer).get("canvasBudget");
    assertThat(budget)
        .containsEntry("points", 12_000)
        .containsEntry("maxPoints", 12_000)
        .containsEntry("strokes", 6)
        .containsEntry("maxStrokes", 300);

    submit(drawer, "DRAW_UNDO", Map.of()); // frees budget
    submit(drawer, "DRAW_STROKE", stroke(UUID.randomUUID(), 0, 1, true));
  }

  @Test
  void DG14_theCanvasIsDiscardedWhenTheNextRoundStarts() {
    start(2);
    choose(0);
    submit(drawer(), "DRAW_STROKE", stroke(UUID.randomUUID(), 0, 3, true));
    advance(Duration.ofSeconds(80));
    assertThat((List<?>) f.sessions.gameSnapshot(CODE, drawer()).get("strokes")).hasSize(1);

    advance(Duration.ofSeconds(6));
    assertThat(phase()).isEqualTo("WORD_CHOICE");
    assertThat((List<?>) f.sessions.gameSnapshot(CODE, drawer()).get("strokes")).isEmpty();
  }

  // ------------------------------------------------------------------ concurrency

  @Test
  void C7_concurrentCorrectGuessesGetDistinctOrdersAndTheRoundEndsOnce() throws Exception {
    start(6);
    String word = choose(0);
    List<UUID> guessers = guessers();
    String phase = f.phaseId();
    CountDownLatch go = new CountDownLatch(1);
    Map<UUID, Throwable> failures = new ConcurrentHashMap<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(guessers.size())) {
      for (UUID g : guessers) {
        pool.submit(
            () -> {
              try {
                go.await();
                f.sessions.submit(CODE, g, "SUBMIT_GUESS", phase, Map.of("text", word));
              } catch (Throwable t) {
                failures.put(g, t);
              }
            });
      }
      go.countDown();
    }

    assertThat(failures).isEmpty();
    List<Published> accepted = f.sink.ofType("GUESS_ACCEPTED");
    assertThat(accepted.stream().map(e -> e.payload().get("order"))).containsExactly(0, 1, 2, 3, 4);
    assertThat(accepted.stream().map(e -> e.payload().get("guestId")).toList())
        .containsExactlyInAnyOrderElementsOf(guessers);
    List<Integer> points =
        accepted.stream().map(e -> (Integer) e.payload().get("scoreDelta")).toList();
    for (int i = 1; i < points.size(); i++) {
      assertThat(points.get(i)).isLessThan(points.get(i - 1));
    }
    assertThat(f.sink.ofType("ROUND_ENDED")).hasSize(1);
    assertThat(last("ROUND_ENDED")).containsEntry("reason", "ALL_GUESSED");
  }

  // ------------------------------------------------------------------ reconnect and leaving

  @Test
  void DG18_aReconnectSnapshotRestoresTheRoundForEachViewer() {
    List<UUID> p = start(3);
    UUID drawer = drawer();
    String word = choose(0);
    UUID a = guessers().get(0);
    UUID b = guessers().get(1);
    UUID s1 = UUID.randomUUID();
    submit(drawer, "DRAW_STROKE", stroke(s1, 0, 3, false));
    guess(b, "wrong one");
    guess(a, word);
    guess(a, "solver talk");

    f.sessions.onConnection(CODE, b, false);
    f.sessions.onConnection(CODE, b, true);
    assertThat(phase()).isEqualTo("DRAWING"); // a disconnect changes nothing

    Map<String, Object> forB = f.sessions.gameSnapshot(CODE, b);
    assertThat(forB.get("phase")).isEqualTo("DRAWING"); // header, not overwritten by the view
    assertThat(forB.get("gameKey")).isEqualTo("draw-guess");
    assertThat(forB.get("round")).isEqualTo(1);
    assertThat(forB.get("drawerGuestId")).isEqualTo(drawer);
    assertThat(forB.get("word")).isNull();
    assertThat(forB.get("hint")).isEqualTo(word.replaceAll("[a-z]", "_"));
    assertThat(forB.get("guessedGuestIds")).isEqualTo(List.of(a));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> strokes = (List<Map<String, Object>>) forB.get("strokes");
    assertThat(strokes).hasSize(1);
    assertThat(strokes.getFirst()).containsEntry("strokeId", s1).containsEntry("closed", false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> chatB = (List<Map<String, Object>>) forB.get("recentChat");
    assertThat(chatB).extracting(m -> m.get("text")).containsExactly("wrong one");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> chatA =
        (List<Map<String, Object>>) f.sessions.gameSnapshot(CODE, a).get("recentChat");
    assertThat(chatA).extracting(m -> m.get("scope")).containsExactly("PUBLIC", "GUESSERS");
    Map<String, Object> forDrawer = f.sessions.gameSnapshot(CODE, drawer);
    assertThat(forDrawer.get("word")).isEqualTo(word);
    assertThat((List<?>) forDrawer.get("recentChat")).hasSize(2);
    assertThat(p).hasSize(3);
  }

  @Test
  void DG10_theDrawerLeavingDuringWordChoiceEndsTheRoundWithoutAWord() {
    start(3);
    UUID drawer = drawer();
    List<String> offered = choices();

    leave(drawer);

    Map<String, Object> ended = last("ROUND_ENDED");
    assertThat(ended).containsEntry("reason", "DRAWER_LEFT").containsEntry("word", null);
    assertThat(phase()).isEqualTo("ROUND_END");
    assertThat(f.recorder.rounds.getFirst().meta()).containsEntry("word", null);
    for (Published e : f.sink.events) {
      if (!e.type().equals("WORD_CHOICES")) {
        offered.forEach(w -> assertThat(containsValue(e.payload(), w)).isFalse());
      }
    }
    advance(Duration.ofSeconds(6));
    assertThat(phase()).isEqualTo("WORD_CHOICE");
    assertThat(drawer()).isNotEqualTo(drawer);
  }

  @Test
  void DG10_D24_theDrawerLeavingDuringDrawingRevealsTheWordAndKeepsGuesserPoints() {
    start(3);
    UUID drawer = drawer();
    String word = choose(0);
    UUID a = guessers().get(0);
    guess(a, word);
    int awarded = (Integer) last("GUESS_ACCEPTED").get("scoreDelta");

    leave(drawer);

    Map<String, Object> ended = last("ROUND_ENDED");
    assertThat(ended).containsEntry("reason", "DRAWER_LEFT").containsEntry("word", word);
    @SuppressWarnings("unchecked")
    Map<UUID, Integer> deltas = (Map<UUID, Integer>) ended.get("scoreDeltas");
    assertThat(deltas).containsEntry(a, awarded).containsEntry(drawer, 0);
    assertThat(f.sessions.info(CODE).scores()).containsEntry(a, awarded).containsEntry(drawer, 0);
    assertThat(f.recorder.rounds.getFirst().endReason()).isEqualTo("DRAWER_LEFT");
  }

  @Test
  void DG9_D25_aDisconnectedGuesserBlocksAllGuessedUntilTheyAreLeft() {
    start(3);
    String word = choose(0);
    UUID a = guessers().get(0);
    UUID b = guessers().get(1);

    f.sessions.onConnection(CODE, b, false); // in grace: still eligible
    guess(a, word);
    assertThat(phase()).isEqualTo("DRAWING");

    leave(b); // grace expired (or left): no longer eligible, re-evaluated at once
    Map<String, Object> ended = last("ROUND_ENDED");
    assertThat(ended).containsEntry("reason", "ALL_GUESSED");
    assertThat(f.sessions.info(CODE).scores()).containsEntry(drawer(), 150); // 1 of 1 eligible
  }

  @Test
  void C1_twoPlayersAndOneLeavesBeforeAnyRoundAborts() {
    start(2);
    choose(0);

    leave(guessers().getFirst());

    assertThat(last("GAME_ABORTED")).containsEntry("reason", "NO_COMPLETED_ROUND");
    assertThat(f.room.status).isEqualTo(RoomStatus.WAITING);
    assertThat(f.sessions.activeCount()).isZero();
  }

  @Test
  void C1_D30_theDrawerLeavingRecordsTheirRoundThenTheEngineFinishes() {
    start(2);
    String word = choose(0);
    UUID guesser = guessers().getFirst();
    guess(guesser, word); // round 1 done
    advance(Duration.ofSeconds(6));
    String second = choose(0);
    guess(order().getFirst(), "nope"); // drawer of round 1 now guesses
    assertThat(second).isNotEqualTo(word);

    leave(drawer());

    // The drawer left: the module records a DRAWER_LEFT round, then the engine finishes.
    assertThat(last("GAME_FINISHED")).containsEntry("endReason", "INSUFFICIENT_PLAYERS");
    assertThat(f.recorder.rounds)
        .extracting(RoundRecord::endReason)
        .containsExactly("ALL_GUESSED", "DRAWER_LEFT");
    assertThat(f.room.status).isEqualTo(RoomStatus.RESULTS);
  }

  @Test
  void DG16_aGuesserLeavingMidRoundWithTwoPlayersDiscardsThatRound() {
    start(2);
    String word = choose(0);
    guess(guessers().getFirst(), word);
    advance(Duration.ofSeconds(6));
    choose(0);

    leave(guessers().getFirst()); // the only guesser: no solver, the engine's [C-1] decides

    assertThat(f.recorder.rounds).hasSize(1); // round 2 discarded, not recorded
    assertThat(last("GAME_FINISHED")).containsEntry("endReason", "INSUFFICIENT_PLAYERS");
  }

  // ------------------------------------------------------------------ results and rematch

  @Test
  void DG11_aCompleteGameFinishesWithDetailedStandingsAndRematchStartsFresh() {
    f.room.settings = Map.of("cycles", 1);
    start(2);
    UUID first = drawer();
    String w1 = choose(0);
    UUID second = guessers().getFirst();
    advance(Duration.ofSeconds(20));
    guess(second, w1); // 400 + drawer 150
    advance(Duration.ofSeconds(6));
    assertThat(drawer()).isEqualTo(second);
    String w2 = choose(2);
    guess(first, w2); // full time left: 500 + drawer 150
    assertThat(f.room.status).isEqualTo(RoomStatus.PLAYING); // the last round gets its pause
    advance(Duration.ofSeconds(6));

    Map<String, Object> finished = last("GAME_FINISHED");
    assertThat(finished).containsEntry("endReason", "COMPLETED");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> standings = (List<Map<String, Object>>) finished.get("standings");
    assertThat(standings.get(0))
        .containsEntry("rank", 1)
        .containsEntry("guestId", first)
        .containsEntry("score", 650);
    assertThat(standings.get(0).get("detail"))
        .isEqualTo(
            Map.of(
                "correctGuesses", 1, "guesserPoints", 500, "drawerPoints", 150, "roundsDrawn", 1));
    assertThat(standings.get(1)).containsEntry("rank", 2).containsEntry("score", 550);
    assertThat(f.recorder.results.getFirst().standings()).hasSize(2);

    assertThat(f.recorder.rounds).hasSize(2);
    RoundRecord r1 = f.recorder.rounds.getFirst();
    assertThat(r1.roundNo()).isEqualTo(1);
    assertThat(r1.endReason()).isEqualTo("ALL_GUESSED");
    assertThat(r1.scoreDeltas()).isEqualTo(Map.of(first, 150, second, 400));
    assertThat(r1.meta())
        .containsEntry("drawerGuestId", first)
        .containsEntry("word", w1)
        .containsEntry("autoPicked", false)
        .containsEntry("correctGuessers", List.of(second));
    assertThat(r1.endedAt()).isAfter(r1.startedAt());

    // Rematch: the host's yes resets the room; the next start is a brand-new session.
    UUID oldSession = (UUID) finished.get("sessionId");
    f.sessions.rematchVote(CODE, f.host(), true);
    assertThat(f.room.status).isEqualTo(RoomStatus.WAITING);
    f.startAndPlay();
    assertThat(last("GAME_STARTED").get("sessionId")).isNotEqualTo(oldSession);
    assertThat(f.sessions.info(CODE).scores().values()).containsOnly(0);
    assertThat(last("PHASE_STARTED")).containsEntry("round", 1).containsEntry("totalRounds", 2);
    assertThat(f.sessions.gameSnapshot(CODE, f.host()).get("guessedGuestIds")).isEqualTo(List.of());
  }

  // ------------------------------------------------------------------ bounds and invariants

  @Test
  void DG14_D28_aMaximalSnapshotStaysUnder256KiB() throws Exception {
    start(10);
    choose(0);
    UUID drawer = drawer();
    for (int s = 0; s < 300; s++) { // 300 strokes × 40 points = 12 000 live points
      submit(drawer, "DRAW_STROKE", stroke(UUID.randomUUID(), 0, 40, s % 2 == 0));
    }
    UUID talker = guessers().getFirst();
    for (int i = 0; i < 40; i++) { // more than 30 lines of 80 four-byte letters
      guess(talker, "𐐀".repeat(78) + String.format("%02d", i));
    }

    Map<String, Object> snapshot = f.sessions.gameSnapshot(CODE, talker);
    int bytes =
        JsonMapper.builder()
            .build()
            .writeValueAsString(snapshot)
            .getBytes(StandardCharsets.UTF_8)
            .length;

    assertThat((List<?>) snapshot.get("recentChat")).hasSize(30);
    assertThat(bytes).isGreaterThan(150_000).isLessThan(262_144);
  }

  @Test
  void invariantsHoldUnderRandomInterleavings() {
    for (long seed = 1; seed <= 8; seed++) {
      SessionFixture g = new SessionFixture(new DrawGuessModule());
      List<UUID> players = g.players(4);
      g.room.settings = Map.of("cycles", 1, "drawSeconds", 30, "wordChoiceSeconds", 5);
      g.startAndPlay();
      SeededRandomSource random = new SeededRandomSource(seed);
      Set<UUID> left = new HashSet<>();
      for (int step = 0; step < 300 && g.room.status == RoomStatus.PLAYING; step++) {
        UUID who = players.get(random.nextInt(players.size()));
        try {
          switch (random.nextInt(7)) {
            case 0 ->
                g.sessions.submit(
                    CODE, who, "CHOOSE_WORD", g.phaseId(), Map.of("wordIndex", random.nextInt(4)));
            case 1 ->
                g.sessions.submit(
                    CODE, who, "DRAW_STROKE", g.phaseId(), stroke(UUID.randomUUID(), 0, 3, true));
            case 2 -> {
              List<Published> words = g.sink.ofType("DRAWER_WORD");
              String text =
                  words.isEmpty() || random.nextInt(2) == 0
                      ? "guess" + random.nextInt(5)
                      : (String) words.getLast().payload().get("word");
              g.sessions.submit(CODE, who, "SUBMIT_GUESS", g.phaseId(), Map.of("text", text));
            }
            case 3 -> g.sessions.submit(CODE, who, "DRAW_UNDO", g.phaseId(), Map.of());
            case 4 -> {
              if (random.nextInt(10) == 0 && left.size() < 2 && left.add(who)) {
                g.room.removeMember(who);
                g.sessions.onMemberLeft(CODE, who);
              }
            }
            default -> {
              g.clock.advance(Duration.ofSeconds(1 + random.nextInt(12)));
              g.scheduler.fireDue(g.clock, false);
            }
          }
        } catch (DomainException expected) {
          // rejections are part of the walk
        }
        assertInvariants(g, players, left);
      }
    }
  }

  private static void assertInvariants(SessionFixture g, List<UUID> players, Set<UUID> left) {
    if (g.sessions.activeCount() == 0 || g.room.status != RoomStatus.PLAYING) {
      return;
    }
    Map<String, Object> phase = g.sink.ofType("PHASE_STARTED").getLast().payload();
    UUID drawer = (UUID) phase.get("drawerGuestId");
    String name = (String) phase.get("phase");
    for (UUID viewer : players) {
      if (left.contains(viewer)) {
        continue;
      }
      Map<String, Object> view = g.sessions.gameSnapshot(CODE, viewer);
      assertThat(view.get("phase")).isEqualTo(name);
      if (!viewer.equals(drawer)) {
        assertThat(view.get("wordChoices")).isNull();
        if (!name.equals("ROUND_END")) {
          assertThat(view.get("word")).isNull();
        }
      }
      @SuppressWarnings("unchecked")
      List<UUID> solvers = (List<UUID>) view.get("guessedGuestIds");
      assertThat(solvers).doesNotHaveDuplicates().doesNotContain(drawer);
    }
    // The ledger equals the sum of every published score.
    Map<UUID, Integer> sum = new HashMap<>();
    players.forEach(p -> sum.put(p, 0));
    for (Published e : g.sink.ofType("GUESS_ACCEPTED")) {
      sum.merge(
          (UUID) e.payload().get("guestId"), (Integer) e.payload().get("scoreDelta"), Integer::sum);
    }
    for (Published e : g.sink.ofType("ROUND_ENDED")) {
      UUID d = (UUID) e.payload().get("drawerGuestId");
      @SuppressWarnings("unchecked")
      Map<UUID, Integer> deltas = (Map<UUID, Integer>) e.payload().get("scoreDeltas");
      // Guesser deltas were already counted at GUESS_ACCEPTED; add only the drawer's award.
      sum.merge(d, deltas.get(d), Integer::sum);
    }
    assertThat(g.sessions.info(CODE).scores()).isEqualTo(sum);
  }
}
