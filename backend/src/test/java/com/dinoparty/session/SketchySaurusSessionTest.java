package com.dinoparty.session;

import static com.dinoparty.session.ImpostorDriver.REVEAL;
import static com.dinoparty.session.ImpostorDriver.ROUND_END;
import static com.dinoparty.session.ImpostorDriver.rejected;
import static com.dinoparty.session.SessionFixture.CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.RandomSource;
import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.engine.RoundRecord;
import com.dinoparty.games.impostor.SketchySaurusModule;
import com.dinoparty.room.RoomStatus;
import com.dinoparty.session.SessionFixture.Published;
import com.dinoparty.words.TestWords;
import com.dinoparty.words.TestWords.Catalog;
import com.dinoparty.words.WordDifficulty;
import com.dinoparty.words.WordPoolService;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sketchy Saurus through the real engine with a manual clock (P9-04): the sketching with its
 * private drafts ([SS-2]), the gallery on the shared voting code ([SS-1]), the three ways a caught
 * impostor can be treated ([SS-3]), leaving and disconnecting, results and rematch. The voting
 * details themselves are covered by {@link DinoImpostorVotingSessionTest}; here the voting phase is
 * called {@code GALLERY}.
 */
class SketchySaurusSessionTest {

  private static final Map<String, Object> KNOWS = Map.of("rounds", 3);
  private static final Map<String, Object> UNAWARE_NO_GUESS =
      Map.of("rounds", 3, "impostorKnows", false);
  private static final Map<String, Object> UNAWARE_GUESS =
      Map.of("rounds", 3, "impostorKnows", false, "unawareGuess", true);

  private SessionFixture f;
  private SketchyDriver s;
  private ImpostorDriver d;

  private static SessionFixture fixture(WordPoolService words, RandomSource secret) {
    return new SessionFixture(new SketchySaurusModule(words, secret));
  }

  private void begin(long seed, int players, Map<String, Object> settings) {
    f = fixture(TestWords.service(), new SeededRandomSource(seed));
    s = new SketchyDriver(f);
    d = s.d;
    s.start(players, settings);
  }

  @SuppressWarnings("unchecked")
  private <T> T field(Map<String, Object> m, String key) {
    return (T) m.get(key);
  }

  private static Object obj(Map<String, Object> m, String key) {
    return m.get(key);
  }

  private List<Map<String, Object>> strokesOf(UUID viewer) {
    Map<String, Object> draft = field(s.snapshot(viewer), "draft");
    return field(draft, "strokes");
  }

  private Map<UUID, Integer> scores() {
    return f.sessions.info(CODE).scores();
  }

  private int eventCount() {
    return f.sink.events.size();
  }

  // ------------------------------------------------------------------ phases

  @Test
  void SS1_aRoundRunsRoleRevealSketchGalleryRoundEndAndThenTheNextRound() {
    begin(21, 5, KNOWS);
    assertThat(d.lastPhase())
        .containsEntry("phase", "ROLE_REVEAL")
        .containsEntry("round", 1)
        .containsEntry("totalRounds", 3);
    assertThat(d.lastPhase().keySet())
        .containsExactlyInAnyOrder(
            "phase", "phaseId", "deadlineAt", "round", "totalRounds", "nicknames");

    s.toSketch();
    assertThat(d.phase()).isEqualTo("SKETCH");
    assertThat(d.lastEvent("SKETCHY_PROGRESS")).containsEntry("submittedGuestIds", List.of());

    s.everyoneSubmits(5);
    assertThat(d.phase()).isEqualTo("GALLERY");

    d.advance(Duration.ofSeconds(60));
    assertThat(d.phase()).isEqualTo("ROUND_END");
    d.advance(ROUND_END);
    assertThat(d.lastPhase()).containsEntry("phase", "ROLE_REVEAL").containsEntry("round", 2);
    assertThat(f.sink.ofType("PHASE_STARTED").stream().map(p -> p.payload().get("phase")))
        .startsWith("ROLE_REVEAL", "SKETCH", "GALLERY", "ROUND_END", "ROLE_REVEAL");
  }

  @Test
  void G8_theRoleRevealCarriesTheNicknamesAndNoSeatsOrColours() {
    begin(22, 4, KNOWS);

    Map<UUID, String> nicknames = field(d.lastPhase(), "nicknames");
    assertThat(nicknames).containsOnlyKeys(d.players);
    assertThat(nicknames).isEqualTo(s.snapshot(d.players.getFirst()).get("nicknames"));
    assertThat(d.lastPhase()).doesNotContainKeys("seatOrder", "colors");
  }

  // ------------------------------------------------------------------ SKETCH

  @Test
  void SS2_drawingCommandsAreNotPublishedAndNothingOfADraftReachesAnyoneElse() {
    begin(23, 5, KNOWS);
    s.toSketch();
    UUID a = d.players.get(1);
    UUID b = d.players.get(2);
    int before = eventCount();

    UUID first = s.stroke(a, 20);
    s.stroke(a, 30);
    s.undo(a);
    s.clear(a);
    s.stroke(a, 10);

    assertThat(eventCount()).as("no event of any kind").isEqualTo(before);
    assertThat(strokesOf(a)).hasSize(1);
    assertThat(strokesOf(b)).isEmpty();
    assertThat(s.snapshot(b).toString()).doesNotContain(first.toString());
    Map<String, Object> bView = s.snapshot(b);
    assertThat(obj(bView, "submittedGuestIds")).isEqualTo(List.of());
    assertThat(bView.get("gallery")).isNull();
  }

  @Test
  void SS2_undoAndClearTouchOnlyTheSendersOwnDraft() {
    begin(24, 5, KNOWS);
    s.toSketch();
    UUID a = d.players.get(0);
    UUID b = d.players.get(3);
    s.stroke(a, 10);
    s.stroke(a, 10);
    s.stroke(b, 10);
    s.stroke(b, 10);

    s.undo(a);
    assertThat(strokesOf(a)).hasSize(1);
    assertThat(strokesOf(b)).hasSize(2);

    s.clear(a);
    assertThat(strokesOf(a)).isEmpty();
    assertThat(strokesOf(b)).hasSize(2);

    s.undo(a); // nothing to undo: an acknowledged no-op
    s.clear(a);
    assertThat(strokesOf(b)).hasSize(2);
  }

  @Test
  void SS2_afterSubmittingEveryDrawingCommandIsAlreadySubmittedAndTheDraftIsUnchanged() {
    begin(25, 5, KNOWS);
    s.toSketch();
    UUID a = d.players.get(2);
    s.stroke(a, 12);
    s.submit(a);
    int events = eventCount();

    assertThat(rejected(() -> s.stroke(a, 5))).isEqualTo(ErrorCode.ALREADY_SUBMITTED);
    assertThat(rejected(() -> s.undo(a))).isEqualTo(ErrorCode.ALREADY_SUBMITTED);
    assertThat(rejected(() -> s.clear(a))).isEqualTo(ErrorCode.ALREADY_SUBMITTED);
    assertThat(rejected(() -> s.submit(a))).isEqualTo(ErrorCode.ALREADY_SUBMITTED);

    assertThat(strokesOf(a)).hasSize(1);
    assertThat(eventCount()).isEqualTo(events);
    // The others can still draw.
    s.stroke(d.players.getFirst(), 5);
  }

  @Test
  void SS2_aFullBudgetRejectsTheWholeBatchWithPayloadTooLargeAndTheDraftIsUnchanged() {
    begin(26, 5, KNOWS);
    s.toSketch();
    UUID a = d.players.get(1);
    UUID big = UUID.randomUUID();
    for (int batch = 0; batch < 4; batch++) {
      d.send(a, "SKETCHY_DRAW_STROKE", s.batch(big, batch, 200, false));
    }
    d.send(a, "SKETCHY_DRAW_STROKE", s.batch(big, 4, 100, true)); // 900 live points

    assertThat(
            rejected(
                () -> d.send(a, "SKETCHY_DRAW_STROKE", s.batch(UUID.randomUUID(), 0, 200, true))))
        .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);

    Map<String, Object> draft = field(s.snapshot(a), "draft");
    assertThat(obj(draft, "canvasBudget"))
        .isEqualTo(Map.of("points", 900, "maxPoints", 1000, "strokes", 1, "maxStrokes", 60));
    s.stroke(a, 100); // the same player can still use what is left
    assertThat(strokesOf(a)).hasSize(2);
  }

  @Test
  void SS2_aSixtyFirstStrokeIsRejectedAndOnlyTheStrokeBudgetIsExhausted() {
    begin(27, 5, KNOWS);
    s.toSketch();
    UUID a = d.players.get(1);
    for (int i = 0; i < 60; i++) {
      s.stroke(a, 1);
    }

    assertThat(rejected(() -> s.stroke(a, 1))).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
    assertThat(strokesOf(a)).hasSize(60);
    s.undo(a);
    s.stroke(a, 1); // freed by an undo
    assertThat(strokesOf(a)).hasSize(60);
  }

  @Test
  void SS2_theBatchLimitsOfDG14StillApply() {
    begin(28, 4, KNOWS);
    s.toSketch();
    UUID a = d.players.get(1);

    assertThat(rejected(() -> s.stroke(a, 201))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    Map<String, Object> eraser = s.batch(UUID.randomUUID(), 0, 3, true);
    eraser.put("tool", "ERASER");
    d.send(a, "SKETCHY_DRAW_STROKE", eraser); // the full toolset: the eraser is allowed
    assertThat(strokesOf(a)).hasSize(1);
    Map<String, Object> outOfOrder = s.batch(UUID.randomUUID(), 3, 3, true);
    assertThat(rejected(() -> d.send(a, "SKETCHY_DRAW_STROKE", outOfOrder)))
        .isEqualTo(ErrorCode.INVALID_ACTION);
  }

  @Test
  void SS2_everyoneSubmittingEndsTheSketchEarlyWithTheLastProgressBeforeTheGallery() {
    begin(29, 5, KNOWS);
    s.toSketch();
    List<UUID> all = d.players;
    for (int i = 0; i < 4; i++) {
      s.submit(all.get(i));
      List<UUID> ids = field(d.lastEvent("SKETCHY_PROGRESS"), "submittedGuestIds");
      assertThat(ids).hasSize(i + 1).contains(all.get(i));
      assertThat(d.phase()).isEqualTo("SKETCH");
    }

    s.submit(all.get(4));

    assertThat(d.phase()).isEqualTo("GALLERY");
    List<Published> events = f.sink.events;
    int galleryPhase = -1;
    for (int i = 0; i < events.size(); i++) {
      Published e = events.get(i);
      if (e.type().equals("PHASE_STARTED") && e.payload().get("phase").equals("GALLERY")) {
        galleryPhase = i;
      }
    }
    Published lastProgress = f.sink.ofType("SKETCHY_PROGRESS").getLast();
    assertThat(events.indexOf(lastProgress)).isLessThan(galleryPhase);
    assertThat((List<?>) lastProgress.payload().get("submittedGuestIds")).hasSize(5);
    assertThat(f.sink.ofType("SKETCHY_GALLERY_OPENED")).hasSize(1);
    assertThat(events.indexOf(f.sink.ofType("SKETCHY_GALLERY_OPENED").getFirst()))
        .isGreaterThan(galleryPhase);
  }

  @Test
  void SS2_theDeadlineTurnsEveryDraftIntoTheDrawingItIsAndEmptyOnesAreFlagged() {
    begin(30, 5, KNOWS);
    s.toSketch();
    List<UUID> p = d.players;
    s.stroke(p.get(0), 10);
    s.submit(p.get(0));
    s.stroke(p.get(1), 20); // never submitted
    UUID open = UUID.randomUUID();
    d.send(p.get(2), "SKETCHY_DRAW_STROKE", s.batch(open, 0, 7, false)); // an open stroke
    s.stroke(p.get(3), 4);
    s.undo(p.get(3)); // drew and took it back: empty
    // p4 did nothing

    d.advance(SketchyDriver.SKETCH);

    assertThat(d.phase()).isEqualTo("GALLERY");
    Map<UUID, Map<String, Object>> gallery = s.galleryEvent();
    assertThat(gallery).containsOnlyKeys(p);
    assertThat(obj(gallery.get(p.get(0)), "strokes")).asList().hasSize(1);
    assertThat(obj(gallery.get(p.get(1)), "strokes")).asList().hasSize(1);
    assertThat(gallery.get(p.get(0))).containsEntry("empty", false);
    assertThat(gallery.get(p.get(1))).containsEntry("empty", false);
    assertThat(gallery.get(p.get(2))).containsEntry("empty", false);
    List<Map<String, Object>> openStroke = field(gallery.get(p.get(2)), "strokes");
    assertThat(openStroke.getFirst()).containsEntry("closed", true);
    assertThat(gallery.get(p.get(3))).containsEntry("empty", true);
    assertThat(gallery.get(p.get(4))).containsEntry("empty", true);
    assertThat((List<?>) field(gallery.get(p.get(4)), "strokes")).isEmpty();
  }

  @Test
  void C6_aBatchOrSubmitAfterTheDeadlineIsStale() {
    begin(31, 5, KNOWS);
    s.toSketch();
    String sketchPhase = f.phaseId();
    UUID a = d.players.get(1);
    s.stroke(a, 10);

    d.advance(SketchyDriver.SKETCH);

    assertThat(
            rejected(
                () ->
                    f.sessions.submit(
                        CODE,
                        a,
                        "SKETCHY_DRAW_STROKE",
                        sketchPhase,
                        s.batch(UUID.randomUUID(), 0, 3, true))))
        .isEqualTo(ErrorCode.STALE_ACTION);
    assertThat(rejected(() -> f.sessions.submit(CODE, a, "SKETCHY_SUBMIT", sketchPhase, Map.of())))
        .isEqualTo(ErrorCode.STALE_ACTION);
    assertThat(obj(s.galleryEvent().get(a), "strokes")).asList().hasSize(1);
  }

  @Test
  void SS2_drawingOutsideSketchIsRejectedAndAChatInSketchToo() {
    begin(32, 5, KNOWS);
    UUID a = d.players.get(1);
    assertThat(rejected(() -> s.stroke(a, 3))).isEqualTo(ErrorCode.INVALID_PHASE); // ROLE_REVEAL
    s.toSketch();
    assertThat(rejected(() -> d.chat(a, "hello"))).isEqualTo(ErrorCode.INVALID_PHASE);
    assertThat(rejected(() -> d.vote(a, d.players.getFirst()))).isEqualTo(ErrorCode.INVALID_PHASE);

    s.everyoneSubmits(3);

    assertThat(d.phase()).isEqualTo("GALLERY");
    assertThat(rejected(() -> s.stroke(a, 3))).isEqualTo(ErrorCode.INVALID_PHASE);
    assertThat(rejected(() -> s.submit(a))).isEqualTo(ErrorCode.INVALID_PHASE);
    assertThat(rejected(() -> s.undo(a))).isEqualTo(ErrorCode.INVALID_PHASE);
  }

  @Test
  void G6_theSubmittedDraftStaysInTheViewersSnapshotUntilSketchEnds() {
    begin(33, 5, KNOWS);
    s.toSketch();
    UUID a = d.players.get(1);
    s.stroke(a, 15);
    s.submit(a);

    Map<String, Object> mine = s.snapshot(a);
    assertThat(mine).containsEntry("submitted", true);
    assertThat(strokesOf(a)).hasSize(1);
    assertThat(obj(mine, "submittedGuestIds")).isEqualTo(List.of(a));
    Map<String, Object> theirs = s.snapshot(d.players.getFirst());
    assertThat(theirs).containsEntry("submitted", false);
    assertThat(obj(theirs, "draft")).isNotNull();
    assertThat(strokesOf(d.players.getFirst())).isEmpty();

    s.everyoneSubmits(0);

    Map<String, Object> gallery = s.snapshot(a);
    assertThat(gallery.get("draft")).isNull();
    assertThat(gallery).containsEntry("submitted", false);
    assertThat(obj(gallery, "submittedGuestIds")).isEqualTo(List.of());
  }

  // ------------------------------------------------------------------ leaving and disconnecting

  @Test
  void SS2_aPlayerWhoLeavesInSketchHasNoDrawingAndTheEarlyEndIsCheckedAgain() {
    begin(34, 5, KNOWS);
    s.toSketch();
    UUID impostor = s.impostor();
    List<UUID> others = d.players.stream().filter(p -> !p.equals(impostor)).toList();
    UUID leaver = others.getFirst();
    for (UUID p : d.players) {
      if (!p.equals(leaver)) {
        s.stroke(p, 5);
        s.submit(p);
      }
    }
    assertThat(d.phase()).isEqualTo("SKETCH"); // the leaver has not submitted

    d.leave(leaver);

    assertThat(d.phase()).isEqualTo("GALLERY");
    assertThat(s.galleryEvent()).hasSize(4).doesNotContainKey(leaver);
    assertThat((List<?>) field(s.snapshot(impostor), "gallery")).hasSize(4);
  }

  @Test
  void SS2_aLeaverWhoHadSubmittedIsDroppedFromProgressAndGallery() {
    begin(35, 6, KNOWS);
    s.toSketch();
    UUID impostor = s.impostor();
    UUID leaver = s.artists().getFirst();
    s.stroke(leaver, 5);
    s.submit(leaver);

    d.leave(leaver);

    assertThat(d.phase()).isEqualTo("SKETCH");
    assertThat(obj(d.lastEvent("SKETCHY_PROGRESS"), "submittedGuestIds")).isEqualTo(List.of());
    assertThat(obj(s.snapshot(impostor), "submittedGuestIds")).isEqualTo(List.of());
    d.advance(SketchyDriver.SKETCH);
    assertThat(s.galleryEvent()).hasSize(5).doesNotContainKey(leaver);
  }

  @Test
  void SS2_aDisconnectedPlayerKeepsTheirDraftAndItBecomesTheirDrawing() {
    begin(36, 5, KNOWS);
    s.toSketch();
    UUID a = d.players.get(2);
    s.stroke(a, 9);

    f.room.setConnected(a, false);
    f.sessions.onConnection(CODE, a, false);
    d.advance(Duration.ofSeconds(20));
    assertThat(strokesOf(a)).hasSize(1);
    f.room.setConnected(a, true);
    f.sessions.onConnection(CODE, a, true);
    assertThat(strokesOf(a)).hasSize(1);
    s.stroke(a, 4);

    d.advance(Duration.ofSeconds(40));

    assertThat(d.phase()).isEqualTo("GALLERY");
    assertThat(obj(s.galleryEvent().get(a), "strokes")).asList().hasSize(2);
  }

  // ------------------------------------------------------------------ GALLERY

  @Test
  void SS1_theGalleryHoldsOneDrawingPerPlayerInSessionOrderAndTheSnapshotCarriesItToo() {
    begin(37, 5, KNOWS);
    s.toSketch();
    List<UUID> p = d.players;
    s.stroke(p.get(3), 6);
    s.stroke(p.get(0), 3);
    s.stroke(p.get(0), 4);
    s.everyoneSubmits(0);

    Map<UUID, Map<String, Object>> gallery = s.galleryEvent();
    Map<UUID, String> nicknames = field(s.snapshot(p.get(1)), "nicknames");
    assertThat(List.copyOf(gallery.keySet())).isEqualTo(List.copyOf(nicknames.keySet()));
    assertThat(gallery.get(p.get(0)).get("strokes")).asList().hasSize(2);
    assertThat(gallery.get(p.get(3)).get("strokes")).asList().hasSize(1);
    assertThat(gallery.get(p.get(3))).containsEntry("empty", false);
    assertThat(gallery.get(p.get(1))).containsEntry("empty", true);
    assertThat(d.lastEvent("SKETCHY_GALLERY_OPENED")).containsEntry("round", 1);
    assertThat(d.lastEvent("SKETCHY_GALLERY_OPENED").keySet()).containsExactly("round", "drawings");
    for (UUID viewer : p) {
      assertThat(s.snapshot(viewer).get("gallery"))
          .isEqualTo(d.lastEvent("SKETCHY_GALLERY_OPENED").get("drawings"));
    }
    // The gallery stays through the guess and the round end.
    d.advance(Duration.ofSeconds(60));
    assertThat(d.phase()).isEqualTo("ROUND_END");
    assertThat((List<?>) field(s.snapshot(p.get(1)), "gallery")).hasSize(5);
    d.advance(ROUND_END);
    assertThat(s.snapshot(p.get(1)).get("gallery")).isNull();
  }

  @Test
  void SS1_theSharedVotingRunsOnTheGalleryPhase() {
    begin(38, 5, KNOWS);
    s.toGallery();
    UUID a = s.artists().get(0);
    UUID b = s.artists().get(1);

    d.chat(a, "that one looks suspicious");
    assertThat(f.sink.ofType("IMPOSTOR_CHAT_MESSAGE")).hasSize(1);
    d.vote(a, b);
    assertThat(obj(d.lastEvent("IMPOSTOR_VOTE_PROGRESS"), "votedGuestIds")).isEqualTo(List.of(a));
    assertThat(s.snapshot(a)).containsEntry("myVote", b);
    assertThat(s.snapshot(b)).containsEntry("myVote", null);
    assertThat(rejected(() -> d.vote(a, a))).isEqualTo(ErrorCode.VALIDATION_FAILED);

    d.advance(Duration.ofSeconds(60));

    assertThat(d.phase()).isEqualTo("ROUND_END");
    assertThat(f.sink.ofType("IMPOSTOR_VOTE_CLOSED")).hasSize(1);
    assertThat(d.lastEvent("IMPOSTOR_ROUND_ENDED")).containsEntry("outcome", "ESCAPED");
  }

  // ------------------------------------------------------------------ [SS-3]

  @Test
  void SS3_aKnowingImpostorWhoIsCaughtGuessesAndTheRightWordWinsTheRound() {
    begin(40, 5, KNOWS);
    s.toGallery();
    UUID impostor = s.impostor();

    s.catchImpostor();

    assertThat(d.phase()).isEqualTo("GUESS");
    assertThat(s.privateTo(impostor, "IMPOSTOR_GUESS_OPEN")).hasSize(1);
    assertThat(f.sink.ofType("IMPOSTOR_GUESS_OPEN")).hasSize(1);
    assertThat(d.lastPhase()).containsEntry("impostorGuestId", impostor);
    d.guess(impostor, s.word());

    assertThat(d.lastEvent("IMPOSTOR_ROUND_ENDED"))
        .containsEntry("outcome", "GUESSED")
        .containsEntry("decoyWord", null)
        .containsEntry("guess", s.word());
    assertThat(scores().get(impostor)).isEqualTo(2);
    assertThat(scores().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
  }

  @Test
  void SS3_aKnowingImpostorWhoGuessesWrongLetsTheArtistsScore() {
    begin(41, 5, KNOWS);
    s.toGallery();
    UUID impostor = s.impostor();
    List<UUID> artists = s.artists();

    s.catchImpostor();
    d.guess(impostor, "definitely not it");

    assertThat(d.lastEvent("IMPOSTOR_ROUND_ENDED")).containsEntry("outcome", "CAUGHT");
    assertThat(scores().get(impostor)).isZero();
    for (UUID artist : artists) {
      assertThat(scores().get(artist)).isEqualTo(2); // caught +1, voted for the impostor +1
    }
  }

  @Test
  void SS3_anUnawareImpostorCaughtWithUnawareGuessOnLearnsItAndGuesses() {
    begin(42, 5, UNAWARE_GUESS);
    s.toGallery();
    UUID impostor = s.impostor();
    assertThat(d.roles().get(impostor)).containsEntry("role", "ARTIST");

    s.catchImpostor();

    assertThat(d.phase()).isEqualTo("GUESS");
    assertThat(f.sink.ofType("IMPOSTOR_GUESS_OPEN")).hasSize(1);
    assertThat(s.privateTo(impostor, "IMPOSTOR_GUESS_OPEN")).hasSize(1);
    // G7: the role stays ARTIST; voteClosed names the impostor to everyone.
    Map<String, Object> mine = s.snapshot(impostor);
    assertThat(obj(mine, "role"))
        .isEqualTo(
            d.roles().get(impostor).entrySet().stream()
                .filter(e -> List.of("role", "category", "word").contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(((Map<?, ?>) field(mine, "voteClosed")).get("impostorGuestId")).isEqualTo(impostor);
    assertThat(
            ((Map<?, ?>) field(s.snapshot(s.artists().getFirst()), "voteClosed"))
                .get("impostorGuestId"))
        .isEqualTo(impostor);

    d.guess(impostor, s.word());

    assertThat(d.lastEvent("IMPOSTOR_ROUND_ENDED"))
        .containsEntry("outcome", "GUESSED")
        .containsEntry("decoyWord", s.decoy());
    assertThat(scores().get(impostor)).isEqualTo(2);
  }

  @Test
  void SS3_anUnawareImpostorCaughtWithUnawareGuessOffGoesStraightToTheRoundEnd() {
    begin(43, 5, UNAWARE_NO_GUESS);
    s.toGallery();
    UUID impostor = s.impostor();
    List<UUID> artists = s.artists();
    String word = s.word();
    String decoy = s.decoy();

    s.catchImpostor();

    assertThat(d.phase()).isEqualTo("ROUND_END");
    assertThat(f.sink.ofType("IMPOSTOR_GUESS_OPEN")).isEmpty();
    assertThat(f.sink.ofType("PHASE_STARTED").stream().map(p -> p.payload().get("phase")))
        .doesNotContain("GUESS");
    assertThat(d.lastEvent("IMPOSTOR_VOTE_CLOSED"))
        .containsEntry("caught", true)
        .containsEntry("impostorGuestId", impostor);
    assertThat(d.lastEvent("IMPOSTOR_ROUND_ENDED"))
        .containsEntry("outcome", "CAUGHT")
        .containsEntry("word", word)
        .containsEntry("decoyWord", decoy)
        .containsEntry("guess", null);
    assertThat(scores().get(impostor)).isZero();
    for (UUID artist : artists) {
      assertThat(scores().get(artist)).isEqualTo(2);
    }
    assertThat(decoy).isNotEqualTo(word);
  }

  @ParameterizedTest
  @ValueSource(strings = {"knows", "unaware", "unawareGuess"})
  void SS3_anImpostorWhoEscapesScoresTwoInEverySetting(String setting) {
    begin(44, 5, settings(setting));
    s.toGallery();
    UUID impostor = s.impostor();
    UUID artist = s.artists().getFirst();
    for (UUID p : d.seated()) {
      d.vote(p, p.equals(artist) ? impostor : artist);
    }
    d.advance(Duration.ofSeconds(15));

    assertThat(d.phase()).isEqualTo("ROUND_END");
    assertThat(d.lastEvent("IMPOSTOR_ROUND_ENDED")).containsEntry("outcome", "ESCAPED");
    assertThat(scores().get(impostor)).isEqualTo(2);
    assertThat(f.sink.ofType("IMPOSTOR_GUESS_OPEN")).isEmpty();
  }

  private static Map<String, Object> settings(String name) {
    return switch (name) {
      case "knows" -> KNOWS;
      case "unaware" -> UNAWARE_NO_GUESS;
      default -> UNAWARE_GUESS;
    };
  }

  @Test
  void SS3_unawareGuessIsIgnoredWhenTheImpostorKnows() {
    begin(45, 5, Map.of("rounds", 3, "impostorKnows", true, "unawareGuess", false));
    s.toGallery();

    s.catchImpostor();

    assertThat(d.phase()).isEqualTo("GUESS");
  }

  @Test
  void SS3_theUnawareImpostorsRoleAndSnapshotHaveExactlyTheShapeOfAnArtists() {
    begin(46, 6, UNAWARE_NO_GUESS);
    List<Published> roles = f.sink.ofType("IMPOSTOR_ROLE");
    assertThat(roles).hasSize(6);
    assertThat(
            roles.stream().map(e -> List.copyOf(e.payload().keySet())).collect(Collectors.toSet()))
        .hasSize(1);
    assertThat(roles).allSatisfy(e -> assertThat(e.payload()).containsEntry("role", "ARTIST"));
    assertThat(roles.stream().map(e -> e.payload().get("category")).collect(Collectors.toSet()))
        .hasSize(1);
    assertThat(roles.stream().map(e -> e.payload().get("word")).collect(Collectors.toSet()))
        .hasSize(2); // the word and the decoy
    assertThat(roles.stream().map(Published::recipient).collect(Collectors.toSet())).hasSize(6);
    UUID impostor = s.impostor();
    UUID artist = s.artists().getFirst();

    for (Runnable step : List.<Runnable>of(() -> {}, s::toSketch)) {
      step.run();
      assertThat(shape(s.snapshot(impostor))).isEqualTo(shape(s.snapshot(artist)));
    }
    s.everyoneSubmits(4);
    assertThat(shape(s.snapshot(impostor))).isEqualTo(shape(s.snapshot(artist)));
    assertThat(obj(s.snapshot(impostor), "role")).isNotNull();
  }

  /** The keys of a view and of its nested role and draft, ignoring values. */
  private static Object shape(Object value) {
    return switch (value) {
      case Map<?, ?> m -> {
        Map<Object, Object> shape = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> shape.put(k, k.equals("word") ? "word" : shape(v)));
        yield shape;
      }
      case List<?> l -> l.isEmpty() ? "[]" : "[..]";
      case null -> "null";
      default -> value.getClass().getSimpleName();
    };
  }

  @Test
  void SS3_wordsAndDecoysAreNeverRepeatedAcrossTheSession() {
    begin(47, 5, Map.of("rounds", 5, "impostorKnows", false));
    Set<String> used = new HashSet<>();
    for (int round = 1; round <= 5; round++) {
      assertThat(used.add(s.word().toLowerCase())).as("word of round %d", round).isTrue();
      assertThat(used.add(s.decoy().toLowerCase())).as("decoy of round %d", round).isTrue();
      s.toGallery();
      d.advance(Duration.ofSeconds(60));
      d.advance(ROUND_END);
    }
    assertThat(used).hasSize(10);
  }

  @Test
  void SS3_aStartWhoseCategoriesCannotSupplyDecoysIsRejectedWithWordPoolTooSmall() {
    Catalog thin = new Catalog("a", "b", "c", "d", "e", "f");
    for (String c : List.of("a", "b", "c", "d", "e", "f")) {
      thin.add("word-" + c, c, WordDifficulty.EASY);
    }
    f = fixture(new WordPoolService(thin, new TestWords.Packs()), new SeededRandomSource(1));
    s = new SketchyDriver(f);
    f.room.settings = Map.of("rounds", 3, "impostorKnows", false);
    f.players(4);

    assertThat(rejected(() -> f.sessions.start(CODE, f.host())))
        .isEqualTo(ErrorCode.WORD_POOL_TOO_SMALL);
    assertThat(f.sessions.isActive(CODE)).isFalse();

    f.room.settings = Map.of("rounds", 3, "impostorKnows", true); // the same pool is enough
    f.sessions.start(CODE, f.host());
    assertThat(f.sessions.isActive(CODE)).isTrue();
  }

  // ------------------------------------------------------------------ leaving

  @ParameterizedTest
  @ValueSource(strings = {"ROLE_REVEAL", "SKETCH", "GALLERY", "GUESS"})
  void DI13_theImpostorLeavingEndsTheRoundWithNoPointsInAnyPhase(String when) {
    begin(50, 5, KNOWS);
    UUID impostor = s.impostor();
    switch (when) {
      case "SKETCH" -> {
        s.toSketch();
        s.stroke(d.players.getFirst(), 4);
      }
      case "GALLERY" -> s.toGallery();
      case "GUESS" -> {
        s.toGallery();
        s.catchImpostor();
        assertThat(d.phase()).isEqualTo("GUESS");
      }
      default -> {}
    }

    d.leave(impostor);

    assertThat(d.phase()).isEqualTo("ROUND_END");
    assertThat(d.lastEvent("IMPOSTOR_ROUND_ENDED"))
        .containsEntry("outcome", "IMPOSTOR_LEFT")
        .containsEntry("impostorGuestId", impostor);
    assertThat(scores().values()).containsOnly(0);
    UUID viewer = s.artists().getFirst();
    assertThat(s.snapshot(viewer).get("draft")).isNull();
    d.advance(ROUND_END);
    assertThat(d.lastPhase()).containsEntry("phase", "ROLE_REVEAL").containsEntry("round", 2);
    assertThat(f.recorder.rounds.getFirst().endReason()).isEqualTo("IMPOSTOR_LEFT");
  }

  @Test
  void DI13_anArtistLeavingInTheGalleryLosesTheirVoteAndKeepsTheirDrawing() {
    begin(51, 5, KNOWS);
    s.toSketch();
    UUID leaver = s.artists().getFirst();
    s.stroke(leaver, 6);
    s.everyoneSubmits(0);
    UUID target = s.artists().get(1);
    d.vote(leaver, target);
    assertThat(obj(d.lastEvent("IMPOSTOR_VOTE_PROGRESS"), "votedGuestIds"))
        .isEqualTo(List.of(leaver));

    d.leave(leaver);

    assertThat(obj(d.lastEvent("IMPOSTOR_VOTE_PROGRESS"), "votedGuestIds")).isEqualTo(List.of());
    assertThat(obj(s.snapshot(target), "gallery")).asList().hasSize(5);
    assertThat(d.phase()).isEqualTo("GALLERY");
  }

  @Test
  void C1_fewerThanThreeLeftDuringTheFirstSketchAbortsTheGame() {
    begin(52, 4, KNOWS);
    s.toSketch();
    d.leave(s.artists().get(0));
    assertThat(f.sink.ofType("GAME_ABORTED")).isEmpty();
    assertThat(d.phase()).isEqualTo("SKETCH");

    d.leave(s.artists().get(0));

    assertThat(f.sink.ofType("GAME_ABORTED")).hasSize(1);
    assertThat(f.sink.ofType("GAME_FINISHED")).isEmpty();
  }

  @Test
  void C1_fewerThanThreeLeftAfterARoundFinishesWithResults() {
    begin(54, 4, KNOWS);
    s.toGallery();
    d.advance(Duration.ofSeconds(60));
    d.advance(ROUND_END);
    s.toSketch();
    d.leave(s.artists().get(0));
    d.leave(s.artists().get(0));

    assertThat(d.lastEvent("GAME_FINISHED")).containsEntry("endReason", "INSUFFICIENT_PLAYERS");
    assertThat(f.room.status).isEqualTo(RoomStatus.RESULTS);
  }

  // ------------------------------------------------------------------ reconnecting

  @Test
  void SS2_aSnapshotAfterAReconnectIsTheSameViewInSketchGalleryAndGuess() {
    begin(53, 5, KNOWS);
    s.toSketch();
    UUID a = d.players.get(1);
    s.stroke(a, 12);
    Map<String, Object> inSketch = s.snapshot(a);
    reconnect(a);
    assertThat(json(s.snapshot(a))).isEqualTo(json(inSketch));

    s.everyoneSubmits(3);
    d.chat(a, "hi");
    d.vote(a, d.players.getFirst());
    Map<String, Object> inGallery = s.snapshot(a);
    reconnect(a);
    assertThat(json(s.snapshot(a))).isEqualTo(json(inGallery));
    assertThat(obj(inGallery, "gallery")).asList().hasSize(5);
    assertThat(obj(inGallery, "recentChat")).asList().hasSize(1);
    assertThat(inGallery).containsEntry("myVote", d.players.getFirst());

    UUID impostor = s.impostor();
    s.catchImpostor();
    assertThat(d.phase()).isEqualTo("GUESS");
    Map<String, Object> inGuess = s.snapshot(impostor);
    reconnect(impostor);
    assertThat(json(s.snapshot(impostor))).isEqualTo(json(inGuess));
    assertThat(obj(inGuess, "voteClosed")).isNotNull();
  }

  private static String json(Object view) {
    return JsonMapper.builder().build().writeValueAsString(view);
  }

  private void reconnect(UUID guest) {
    f.room.setConnected(guest, false);
    f.sessions.onConnection(CODE, guest, false);
    f.room.setConnected(guest, true);
    f.sessions.onConnection(CODE, guest, true);
  }

  // ------------------------------------------------------------------ results and rematch

  @ParameterizedTest
  @ValueSource(strings = {"knows", "unaware", "unawareGuess"})
  void C10_aWholeGameRecordsEveryRoundWithTheDecoyAndEndsInResults(String setting) {
    begin(60, 5, settings(setting));
    boolean decoys = !setting.equals("knows");
    Map<UUID, Integer> asImpostor = new HashMap<>();
    for (int round = 1; round <= 3; round++) {
      asImpostor.merge(s.impostor(), 1, Integer::sum);
      s.toSketch();
      s.everyoneSubmits(3);
      d.advance(Duration.ofSeconds(60));
      d.advance(ROUND_END);
    }

    assertThat(asImpostor.values()).containsOnly(1);
    List<RoundRecord> records = f.recorder.rounds;
    assertThat(records).extracting(RoundRecord::roundNo).containsExactly(1, 2, 3);
    assertThat(records).extracting(RoundRecord::endReason).containsOnly("ESCAPED");
    assertThat(records.getFirst().meta().keySet())
        .containsExactly(
            "impostorGuestId", "word", "category", "decoyWord", "outcome", "accusedGuestId");
    for (RoundRecord r : records) {
      assertThat(r.meta().get("decoyWord") != null).isEqualTo(decoys);
    }
    Map<String, Object> finished = d.lastEvent("GAME_FINISHED");
    assertThat(finished).containsEntry("endReason", "COMPLETED");
    assertThat(obj(finished, "standings")).asList().hasSize(5);
    assertThat(f.room.status).isEqualTo(RoomStatus.RESULTS);
  }

  @Test
  void C3_aRematchStartsANewSessionWithTheRotationCountsAtZero() {
    begin(61, 4, Map.of("rounds", 4));
    for (int round = 1; round <= 4; round++) {
      s.toGallery();
      d.advance(Duration.ofSeconds(60));
      d.advance(ROUND_END);
    }
    UUID oldSession = (UUID) d.lastEvent("GAME_FINISHED").get("sessionId");

    f.sessions.rematchVote(CODE, f.host(), true);
    f.startAndPlay();

    assertThat(d.lastEvent("GAME_STARTED").get("sessionId")).isNotEqualTo(oldSession);
    assertThat(scores().values()).containsOnly(0);
    assertThat(d.lastPhase()).containsEntry("phase", "ROLE_REVEAL").containsEntry("round", 1);
    Map<UUID, Integer> again = new HashMap<>();
    for (int round = 1; round <= 4; round++) {
      again.merge(s.impostor(), 1, Integer::sum);
      s.toGallery();
      d.advance(Duration.ofSeconds(60));
      d.advance(ROUND_END);
    }
    assertThat(again.values()).containsOnly(1);
    assertThat(REVEAL).isEqualTo(Duration.ofSeconds(5));
  }
}
