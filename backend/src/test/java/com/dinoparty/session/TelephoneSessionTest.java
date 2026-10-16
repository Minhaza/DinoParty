package com.dinoparty.session;

import static com.dinoparty.session.SessionFixture.CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.Timestamps;
import com.dinoparty.engine.GameDefinition;
import com.dinoparty.engine.RoundRecord;
import com.dinoparty.engine.SubmissionRecord;
import com.dinoparty.games.telephone.TelephoneModule;
import com.dinoparty.room.RoomStatus;
import com.dinoparty.session.SessionFixture.Published;
import com.dinoparty.words.TestWords;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dino Telephone steps running inside the real engine with a manual clock and in-memory
 * collaborators: transitions T1..T6 of docs/telephone-design.md §4 (the reveal T3..T5 since P6-03),
 * the rejection cells and the rules [TP-1]..[TP-14]. The WebSocket path is covered by {@code
 * TelephoneIT}.
 */
class TelephoneSessionTest {

  private final SessionFixture f = new SessionFixture(new TelephoneModule(TestWords.service()));

  // ------------------------------------------------------------------ helpers

  private List<UUID> start(int players) {
    f.players(players);
    f.startAndPlay();
    return order();
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

  private void submit(UUID guest, String type, Map<String, Object> payload) {
    f.sessions.submit(CODE, guest, type, f.phaseId(), payload);
  }

  private void text(UUID guest, String text) {
    submit(guest, "TELEPHONE_SUBMIT_TEXT", Map.of("text", text));
  }

  private void draftText(UUID guest, String text) {
    submit(guest, "TELEPHONE_TEXT_DRAFT", Map.of("text", text));
  }

  private void stroke(UUID guest, UUID strokeId, int points) {
    submit(guest, "TELEPHONE_DRAW_STROKE", strokePayload(strokeId, 0, points, true));
  }

  private void submitDrawing(UUID guest) {
    submit(guest, "TELEPHONE_SUBMIT_DRAWING", Map.of());
  }

  private static Map<String, Object> strokePayload(UUID id, int batchNo, int points, boolean last) {
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

  private void advance(Duration d) {
    f.clock.advance(d);
    f.scheduler.fireDue(f.clock, false);
  }

  private void leave(UUID guest) {
    f.room.removeMember(guest);
    f.sessions.onMemberLeft(CODE, guest);
  }

  private static ErrorCode rejected(Runnable action) {
    try {
      action.run();
    } catch (DomainException e) {
      return e.code();
    }
    throw new AssertionError("expected a rejection");
  }

  /**
   * The player at order index {@code chain}'s holder in {@code step} (independent of the module).
   */
  private static UUID holder(List<UUID> order, int chain, int step) {
    return order.get((chain + step) % order.size());
  }

  private static int chainOf(List<UUID> order, UUID guest, int step) {
    return Math.floorMod(order.indexOf(guest) - step, order.size());
  }

  /** The private tasks of the running phase by recipient. */
  private Map<UUID, Map<String, Object>> tasks() {
    String phaseId = f.phaseId();
    Map<UUID, Map<String, Object>> tasks = new HashMap<>();
    for (Published e : f.sink.ofType("TELEPHONE_TASK")) {
      if (e.payload().get("phaseId").toString().equals(phaseId)) {
        assertThat(tasks.put(e.recipient(), e.payload())).as("one task per holder").isNull();
      }
    }
    return tasks;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> source(Map<String, Object> task) {
    return (Map<String, Object>) task.get("source");
  }

  /** Everybody still in the game submits something unique for the running step. */
  private void everybodySubmits(List<UUID> players, String tag) {
    for (UUID g : players) {
      switch (phase()) {
        case "DRAW" -> {
          stroke(g, UUID.randomUUID(), 3);
          submitDrawing(g);
        }
        default -> text(g, tag + "-" + players.indexOf(g));
      }
    }
  }

  /** Plays every step; the session is then in REVEAL. */
  private void playSteps(List<UUID> players) {
    int guard = 0;
    while (!"REVEAL".equals(phase()) && guard++ < 20) {
      everybodySubmits(players, "s" + guard);
    }
  }

  private void playToTheEnd(List<UUID> players) {
    playSteps(players);
    revealAll();
  }

  /** The cursor of the latest ALBUM_REVEALED, as the host's client would send it back. */
  private Map<String, Object> cursor() {
    Map<String, Object> r = last("ALBUM_REVEALED");
    return Map.of("chainIndex", r.get("chainIndex"), "entryIndex", r.get("entryIndex"));
  }

  private void revealNext(UUID who) {
    submit(who, "REVEAL_NEXT", cursor());
  }

  private void revealPrev(UUID who) {
    submit(who, "REVEAL_PREV", cursor());
  }

  /** The host steps through every remaining entry; the last NEXT finishes the game. */
  private void revealAll() {
    int guard = 0;
    while ("RUNNING".equals(f.sessions.gameSnapshot(CODE, f.host()).get("status"))
        && guard++ < 100) {
      revealNext(f.host());
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Object value) {
    return (List<Map<String, Object>>) value;
  }

  private static boolean containsValue(Object value, String needle) {
    return switch (value) {
      case null -> false;
      case String s -> s.contains(needle);
      case UUID u -> u.toString().contains(needle);
      case Map<?, ?> m -> m.values().stream().anyMatch(v -> containsValue(v, needle));
      case Collection<?> c -> c.stream().anyMatch(v -> containsValue(v, needle));
      default -> false;
    };
  }

  // ------------------------------------------------------------------ definition and start

  @Test
  void theModuleDeclaresTheContractDefinition() {
    GameDefinition d = new TelephoneModule(TestWords.service()).definition();

    assertThat(d.key()).isEqualTo("telephone");
    assertThat(d.minPlayers()).isEqualTo(4);
    assertThat(d.maxPlayers()).isEqualTo(8);
    assertThat(d.minActivePlayers()).isEqualTo(3);
    assertThat(d.scored()).isFalse();
    assertThat(d.commandsByType())
        .containsOnlyKeys(
            "TELEPHONE_SUBMIT_TEXT",
            "TELEPHONE_TEXT_DRAFT",
            "TELEPHONE_DRAW_STROKE",
            "TELEPHONE_DRAW_UNDO",
            "TELEPHONE_DRAW_CLEAR",
            "TELEPHONE_SUBMIT_DRAWING",
            "REVEAL_NEXT",
            "REVEAL_PREV");
    assertThat(d.commandsByType().get("REVEAL_NEXT").phases()).containsExactly("REVEAL");
    assertThat(d.commandsByType().get("REVEAL_NEXT").ratePerSecond()).isEqualTo(5.0);
    assertThat(d.commandsByType().get("REVEAL_NEXT").maxPayloadBytes()).isEqualTo(256);
    var strokeSpec = d.commandsByType().get("TELEPHONE_DRAW_STROKE");
    assertThat(strokeSpec.maxPayloadBytes()).isEqualTo(8 * 1024);
    assertThat(strokeSpec.ratePerSecond()).isEqualTo(30.0);
    assertThat(strokeSpec.burst()).isEqualTo(40);
    assertThat(strokeSpec.phases()).containsExactly("DRAW");
    assertThat(d.commandsByType().get("TELEPHONE_SUBMIT_TEXT").phases())
        .containsExactlyInAnyOrder("PROMPT", "DESCRIBE");
    assertThat(d.commandsByType().get("TELEPHONE_TEXT_DRAFT").ratePerSecond()).isEqualTo(2.0);
    assertThat(d.publicEvents())
        .containsExactlyInAnyOrder("TELEPHONE_PROGRESS", "TELEPHONE_STEP_ENDED", "ALBUM_REVEALED");
    assertThat(d.privateEvents()).containsExactly("TELEPHONE_TASK");
  }

  @Test
  void T1_startOpensPromptStepAndEveryHolderGetsAPromptTask() {
    List<UUID> p = start(4);

    Map<String, Object> phase = last("PHASE_STARTED");
    assertThat(phase.get("phase")).isEqualTo("PROMPT");
    assertThat(phase.get("step")).isEqualTo(0);
    assertThat(phase.get("totalSteps")).isEqualTo(4);
    assertThat(phase.get("kind")).isEqualTo("PROMPT");
    assertThat(phase.get("deadlineAt")).isNotNull();
    assertThat(last("TELEPHONE_PROGRESS"))
        .containsEntry("submittedCount", 0)
        .containsEntry("total", 4);

    Map<UUID, Map<String, Object>> tasks = tasks();
    assertThat(tasks.keySet()).containsExactlyInAnyOrderElementsOf(p);
    tasks.values().forEach(t -> assertThat(t.get("source")).isNull());
    f.sink.ofType("TELEPHONE_TASK").forEach(e -> assertThat(e.isPublic()).isFalse());
  }

  // ------------------------------------------------------------------ a full game

  @Test
  void TP1_TP4_fourPlayersPlayFourStepsAndEachHolderOnlySeesThePreviousEntry() {
    List<UUID> p = start(4);
    List<String> kinds = new ArrayList<>(List.of(phase()));
    Map<Integer, String> prompt = new HashMap<>();

    // step 0: everybody writes a unique prompt
    for (int i = 0; i < 4; i++) {
      prompt.put(i, "prompt-" + i);
      text(p.get(i), prompt.get(i));
    }
    // step 1 (DRAW): the holder of chain c is p[(c+1)%4] and sees the prompt of chain c only
    kinds.add(phase());
    Map<UUID, Map<String, Object>> t1 = tasks();
    for (int c = 0; c < 4; c++) {
      Map<String, Object> source = source(t1.get(holder(p, c, 1)));
      assertThat(source).containsEntry("kind", "TEXT").containsEntry("text", prompt.get(c));
      assertThat(source)
          .containsEntry("placeholder", false)
          .doesNotContainKeys("author", "guestId");
    }
    Map<Integer, UUID> strokeIds = new HashMap<>();
    for (int c = 0; c < 4; c++) {
      UUID id = UUID.randomUUID();
      strokeIds.put(c, id);
      stroke(holder(p, c, 1), id, 2);
      submitDrawing(holder(p, c, 1));
    }
    // step 2 (DESCRIBE): sees the drawing, not the prompt
    kinds.add(phase());
    Map<UUID, Map<String, Object>> t2 = tasks();
    for (int c = 0; c < 4; c++) {
      Map<String, Object> source = source(t2.get(holder(p, c, 2)));
      assertThat(source).containsEntry("kind", "DRAWING");
      assertThat(source).doesNotContainKey("text");
      assertThat(containsValue(source, strokeIds.get(c).toString())).isTrue();
      assertThat(containsValue(t2.get(holder(p, c, 2)), prompt.get(c))).isFalse();
      text(holder(p, c, 2), "describe-" + c);
    }
    // step 3 (DRAW), the last
    kinds.add(phase());
    Map<UUID, Map<String, Object>> t3 = tasks();
    for (int c = 0; c < 4; c++) {
      assertThat(source(t3.get(holder(p, c, 3)))).containsEntry("text", "describe-" + c);
      submitDrawing(holder(p, c, 3)); // an empty canvas is a valid submission
    }

    assertThat(kinds).containsExactly("PROMPT", "DRAW", "DESCRIBE", "DRAW");
    assertThat(phase()).isEqualTo("REVEAL");
    revealAll();
    assertThat(f.sink.publicTypes()).contains("GAME_FINISHED");
    assertThat(f.recorder.rounds).hasSize(4);
    assertThat(f.recorder.rounds.stream().map(RoundRecord::roundNo).toList())
        .containsExactly(1, 2, 3, 4);
    assertThat(f.recorder.rounds)
        .allSatisfy(r -> assertThat(r.endReason()).isEqualTo("ALL_SUBMITTED"));
    assertThat(f.recorder.rounds.getFirst().meta())
        .containsEntry("step", 0)
        .containsEntry("kind", "PROMPT")
        .containsEntry("submittedCount", 4)
        .containsEntry("timedOutCount", 0)
        .containsEntry("leftCount", 0);
    assertThat(f.recorder.results.getFirst().standings()).isEmpty();
  }

  @Test
  void TP2_fivePlayersAutoGivesFourEntries() {
    List<UUID> p = start(5);

    assertThat(last("PHASE_STARTED").get("totalSteps")).isEqualTo(4);
    playToTheEnd(p);

    assertThat(f.recorder.rounds).hasSize(4);
    assertThat(f.sink.publicTypes()).contains("GAME_FINISHED");
  }

  @Test
  void TP2_eightPlayersPlayEightSteps() {
    List<UUID> p = start(8);

    assertThat(last("PHASE_STARTED").get("totalSteps")).isEqualTo(8);
    playToTheEnd(p);

    assertThat(f.recorder.rounds).hasSize(8);
    assertThat(f.sink.ofType("TELEPHONE_TASK")).hasSize(8 * 8);
  }

  @Test
  void TP4_noOneEverReceivesAnEntryTheyAuthored() {
    List<UUID> p = start(6);
    Map<String, UUID> authorOf = new HashMap<>();
    int step = 0;
    while (!phase().equals("REVEAL")) {
      for (Map.Entry<UUID, Map<String, Object>> t : tasks().entrySet()) {
        Map<String, Object> src = source(t.getValue());
        if (src != null && src.get("text") != null) {
          assertThat(authorOf.get(src.get("text"))).isNotEqualTo(t.getKey());
        }
      }
      for (UUID g : p) {
        if (phase().equals("DRAW")) {
          submitDrawing(g);
        } else {
          String tag = "x" + step + "-" + p.indexOf(g);
          authorOf.put(tag, g);
          text(g, tag);
        }
      }
      step++;
    }
    assertThat(step).isEqualTo(6);
  }

  // ------------------------------------------------------------------ progress and early end

  @Test
  void TP4_progressNamesWhoSubmittedAndTheLastSubmissionEndsTheStepAtOnce() {
    List<UUID> p = start(4);

    text(p.get(0), "a");
    assertThat(last("TELEPHONE_PROGRESS"))
        .containsEntry("submittedCount", 1)
        .containsEntry("total", 4)
        .containsEntry("submittedGuestIds", List.of(p.get(0)));
    assertThat(phase()).isEqualTo("PROMPT");
    text(p.get(1), "b");
    text(p.get(2), "c");
    text(p.get(3), "d");

    assertThat(phase()).isEqualTo("DRAW");
    assertThat(last("TELEPHONE_STEP_ENDED"))
        .containsEntry("step", 0)
        .containsEntry("kind", "PROMPT")
        .containsEntry("reason", "ALL_SUBMITTED")
        .containsEntry("timedOutGuestIds", List.of())
        .containsEntry("leftGuestIds", List.of());
    // the step's events precede the next PHASE_STARTED
    List<String> types = f.sink.events.stream().map(Published::type).toList();
    assertThat(types.lastIndexOf("TELEPHONE_STEP_ENDED"))
        .isLessThan(types.lastIndexOf("PHASE_STARTED"));
  }

  @Test
  void TP4_disconnectedHolderBlocksTheEarlyEndUntilTheyAreLeft() {
    List<UUID> p = start(4);
    f.sessions.onConnection(CODE, p.get(3), false);

    text(p.get(0), "a");
    text(p.get(1), "b");
    text(p.get(2), "c");
    assertThat(phase()).isEqualTo("PROMPT");

    leave(p.get(3)); // grace over: LEFT, filled at once, step re-evaluated (D48)
    assertThat(phase()).isEqualTo("DRAW");
    assertThat(last("TELEPHONE_STEP_ENDED").get("leftGuestIds")).isEqualTo(List.of(p.get(3)));
  }

  // ------------------------------------------------------------------ rejections

  @Test
  void TP7_aSecondSubmissionAndAnyLaterInputAreRejectedAndChangeNothing() {
    List<UUID> p = start(4);
    text(p.get(0), "first");

    assertThat(rejected(() -> text(p.get(0), "second"))).isEqualTo(ErrorCode.ALREADY_SUBMITTED);
    assertThat(rejected(() -> draftText(p.get(0), "more"))).isEqualTo(ErrorCode.ALREADY_SUBMITTED);
    assertThat(last("TELEPHONE_PROGRESS")).containsEntry("submittedCount", 1);

    text(p.get(1), "b");
    text(p.get(2), "c");
    text(p.get(3), "d");
    assertThat(source(tasks().get(holder(p, 0, 1)))).containsEntry("text", "first");

    // DRAW step: same finality for drawing commands
    UUID drawer = holder(p, 0, 1);
    stroke(drawer, UUID.randomUUID(), 2);
    submitDrawing(drawer);
    assertThat(rejected(() -> submitDrawing(drawer))).isEqualTo(ErrorCode.ALREADY_SUBMITTED);
    assertThat(rejected(() -> stroke(drawer, UUID.randomUUID(), 1)))
        .isEqualTo(ErrorCode.ALREADY_SUBMITTED);
    assertThat(rejected(() -> submit(drawer, "TELEPHONE_DRAW_UNDO", Map.of())))
        .isEqualTo(ErrorCode.ALREADY_SUBMITTED);
    assertThat(rejected(() -> submit(drawer, "TELEPHONE_DRAW_CLEAR", Map.of())))
        .isEqualTo(ErrorCode.ALREADY_SUBMITTED);
  }

  @Test
  void TP14_textIsValidatedAndSanitized() {
    List<UUID> p = start(4);

    assertThat(rejected(() -> text(p.get(0), "  ​ "))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> text(p.get(0), "x".repeat(141))))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> submit(p.get(0), "TELEPHONE_SUBMIT_TEXT", Map.of("text", 5))))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> submit(p.get(0), "TELEPHONE_SUBMIT_TEXT", Map.of())))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> draftText(p.get(0), "x".repeat(141))))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(last("TELEPHONE_PROGRESS")).containsEntry("submittedCount", 0);

    text(p.get(0), "x".repeat(140)); // exactly 140 is fine
    text(p.get(1), "  a\u0000b‮  ");
    text(p.get(2), "c");
    text(p.get(3), "d");
    assertThat(source(tasks().get(holder(p, 1, 1)))).containsEntry("text", "ab");
  }

  @Test
  void TP4_commandsOfAnotherPhaseKindAreRejected() {
    List<UUID> p = start(4);

    assertThat(rejected(() -> submitDrawing(p.get(0)))).isEqualTo(ErrorCode.INVALID_PHASE);
    assertThat(rejected(() -> stroke(p.get(0), UUID.randomUUID(), 1)))
        .isEqualTo(ErrorCode.INVALID_PHASE);

    everybodySubmits(p, "a"); // now DRAW
    assertThat(rejected(() -> text(p.get(0), "x"))).isEqualTo(ErrorCode.INVALID_PHASE);
    assertThat(rejected(() -> draftText(p.get(0), "x"))).isEqualTo(ErrorCode.INVALID_PHASE);
  }

  @Test
  void C6_aCommandWithAnOldPhaseIdIsStale() {
    List<UUID> p = start(4);
    String old = f.phaseId();
    everybodySubmits(p, "a");

    assertThat(
            rejected(
                () -> f.sessions.submit(CODE, p.get(0), "TELEPHONE_SUBMIT_DRAWING", old, Map.of())))
        .isEqualTo(ErrorCode.STALE_ACTION);
  }

  @Test
  void TP9_drawingBudgetsRejectTheWholeBatchAndLeaveTheDraftUnchanged() {
    List<UUID> p = start(4);
    everybodySubmits(p, "a");
    UUID drawer = holder(p, 0, 1);

    submit(drawer, "TELEPHONE_DRAW_STROKE", strokePayload(UUID.randomUUID(), 0, 200, true));
    UUID id = UUID.randomUUID();
    ErrorCode code =
        rejected(
            () -> {
              for (int b = 0; b < 12; b++) {
                submit(drawer, "TELEPHONE_DRAW_STROKE", strokePayload(id, b, 200, false));
              }
            });
    assertThat(code).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);

    @SuppressWarnings("unchecked")
    Map<String, Object> budget =
        (Map<String, Object>)
            ((Map<String, Object>) f.sessions.gameSnapshot(CODE, drawer).get("draft"))
                .get("canvasBudget");
    assertThat(budget.get("points")).isEqualTo(2_000 + 200); // 200 + the 10 accepted of the stroke
    assertThat(f.sink.ofType("DRAWING_UPDATED")).isEmpty(); // nothing is broadcast ([TP-9])
  }

  // ------------------------------------------------------------------ timeouts and fills

  @Test
  void TP3_TP5_deadlineKeepsDraftsAndFillsTheRestFromThePool() {
    List<UUID> p = start(4);
    draftText(p.get(0), "half typed");
    draftText(p.get(1), "x");
    draftText(p.get(1), ""); // cleared again
    text(p.get(2), "done");

    advance(Duration.ofSeconds(60));

    assertThat(phase()).isEqualTo("DRAW");
    RoundRecord round = f.recorder.rounds.getFirst();
    assertThat(round.endReason()).isEqualTo("TIME");
    assertThat(round.meta()).containsEntry("submittedCount", 1).containsEntry("timedOutCount", 3);
    assertThat(last("TELEPHONE_STEP_ENDED").get("timedOutGuestIds"))
        .isEqualTo(List.of(p.get(0), p.get(1), p.get(3)));

    Map<UUID, Map<String, Object>> tasks = tasks();
    assertThat(source(tasks.get(holder(p, 0, 1)))).containsEntry("text", "half typed");
    assertThat(source(tasks.get(holder(p, 2, 1)))).containsEntry("text", "done");
    for (int c : new int[] {1, 3}) {
      Map<String, Object> auto = source(tasks.get(holder(p, c, 1)));
      assertThat(auto.get("text")).asString().isNotBlank();
      assertThat(auto).containsEntry("placeholder", false);
    }
    // the late command of a timed-out holder is stale ([TP-12])
    assertThat(
            rejected(
                () ->
                    f.sessions.submit(
                        CODE,
                        p.get(3),
                        "TELEPHONE_SUBMIT_TEXT",
                        f.sink.ofType("PHASE_STARTED").get(0).payload().get("phaseId").toString(),
                        Map.of("text", "late"))))
        .isEqualTo(ErrorCode.STALE_ACTION);
  }

  @Test
  void TP3_autoPromptsAreDrawnWithoutReplacement() {
    List<UUID> p = start(8);

    advance(Duration.ofSeconds(60));

    Set<Object> texts = new java.util.HashSet<>();
    tasks().values().forEach(t -> texts.add(source(t).get("text")));
    assertThat(texts).hasSize(8);
  }

  @Test
  void TP5_aPartialDrawingIsKeptAndAnEmptyOneBecomesAPlaceholderThatIsPassedOn() {
    List<UUID> p = start(4);
    everybodySubmits(p, "a");
    UUID id = UUID.randomUUID();
    stroke(holder(p, 0, 1), id, 5); // chain 0 drew something, never submitted
    // chains 1..3: nothing drawn, nothing submitted

    advance(Duration.ofSeconds(90));

    assertThat(phase()).isEqualTo("DESCRIBE");
    Map<UUID, Map<String, Object>> tasks = tasks();
    Map<String, Object> kept = source(tasks.get(holder(p, 0, 2)));
    assertThat(kept).containsEntry("kind", "DRAWING").containsEntry("placeholder", false);
    assertThat(containsValue(kept, id.toString())).isTrue();
    Map<String, Object> blank = source(tasks.get(holder(p, 1, 2)));
    assertThat(blank).containsEntry("kind", "DRAWING").containsEntry("placeholder", true);
    assertThat(blank.get("strokes")).isEqualTo(List.of());

    advance(Duration.ofSeconds(45)); // nobody describes: placeholders
    assertThat(phase()).isEqualTo("DRAW");
    for (int c = 0; c < 4; c++) {
      Map<String, Object> s = source(tasks().get(holder(p, c, 3)));
      assertThat(s).containsEntry("text", "(no description)");
      assertThat(s).containsEntry("placeholder", true);
    }
  }

  @Test
  void TP5_aDescriptionDraftSurvivesTheDeadline() {
    List<UUID> p = start(4);
    everybodySubmits(p, "a");
    everybodySubmits(p, "b"); // now DESCRIBE
    draftText(holder(p, 2, 2), "a pink thing");

    advance(Duration.ofSeconds(45));

    assertThat(source(tasks().get(holder(p, 2, 3))))
        .containsEntry("text", "a pink thing")
        .containsEntry("placeholder", false);
  }

  @Test
  void TP12_theNextStepDeadlineIsMeasuredFromTheMomentItStarts() {
    List<UUID> p = start(4);
    advance(Duration.ofSeconds(20));
    everybodySubmits(p, "a");

    Instant drawStarted = f.clock.instant();
    assertThat(last("PHASE_STARTED").get("deadlineAt"))
        .isEqualTo(com.dinoparty.common.Timestamps.format(drawStarted.plusSeconds(90)));
  }

  // ------------------------------------------------------------------ leaving

  @Test
  void TP10_aHolderWhoLeavesIsFilledFromTheirDraftAndTheStepIsReEvaluated() {
    List<UUID> p = start(5);
    text(p.get(0), "a");
    text(p.get(1), "b");
    text(p.get(2), "c");
    text(p.get(3), "d");
    draftText(p.get(4), "unfinished");

    leave(p.get(4));

    assertThat(phase()).isEqualTo("DRAW");
    assertThat(last("TELEPHONE_STEP_ENDED")).containsEntry("reason", "ALL_SUBMITTED");
    assertThat(last("TELEPHONE_STEP_ENDED").get("leftGuestIds")).isEqualTo(List.of(p.get(4)));
    assertThat(source(tasks().get(holder(p, 4, 1)))).containsEntry("text", "unfinished");
    assertThat(tasks()).doesNotContainKey(p.get(4)); // a left holder is never asked again
  }

  @Test
  void TP10_aLeftHoldersLaterCellsAreFilledWhenTheStepStarts() {
    List<UUID> p = start(5);
    leave(p.get(4));
    everybodySubmitsExcept(p, p.get(4), "a");

    assertThat(phase()).isEqualTo("DRAW");
    assertThat(tasks().keySet()).doesNotContain(p.get(4));
    assertThat(tasks()).hasSize(4);
    assertThat(last("TELEPHONE_PROGRESS")).containsEntry("total", 4);
    // the cell of p4's chain holder in step 1 is p0's... p4 is the holder of chain 3 at step 1
    UUID leftHolderChain = holder(p, 3, 1);
    assertThat(leftHolderChain).isEqualTo(p.get(4));
    submitEveryoneBut(p.get(4));
    assertThat(phase()).isEqualTo("DESCRIBE");
    assertThat(last("TELEPHONE_STEP_ENDED").get("leftGuestIds")).isEqualTo(List.of(p.get(4)));
    // chain 3's drawing is a placeholder: the next holder is told so
    assertThat(source(tasks().get(holder(p, 3, 2)))).containsEntry("placeholder", true);
  }

  private void everybodySubmitsExcept(List<UUID> p, UUID skip, String tag) {
    for (UUID g : p) {
      if (!g.equals(skip)) {
        text(g, tag + p.indexOf(g));
      }
    }
  }

  private void submitEveryoneBut(UUID skip) {
    for (UUID g : tasks().keySet()) {
      if (!g.equals(skip)) {
        submitDrawing(g);
      }
    }
  }

  @Test
  void C1_fewerThanThreeActivePlayersEndTheGame() {
    List<UUID> p = start(4);
    everybodySubmits(p, "a"); // step 0 done: one completed round
    leave(p.get(3));
    leave(p.get(2));

    assertThat(last("GAME_FINISHED")).containsEntry("endReason", "INSUFFICIENT_PLAYERS");
    assertThat(f.recorder.rounds).hasSize(1); // the step in progress is discarded
  }

  @Test
  void C1_fewerThanThreeActivePlayersBeforeAnyStepCompletedAbort() {
    List<UUID> p = start(4);
    leave(p.get(3));
    leave(p.get(2));

    assertThat(f.recorder.aborts).hasSize(1);
    assertThat(f.sink.publicTypes()).doesNotContain("GAME_FINISHED");
  }

  // ------------------------------------------------------------------ snapshots and secrecy

  @Test
  void TP9_aReconnectingHolderGetsTaskDraftAndSubmittedFromTheSnapshot() {
    List<UUID> p = start(4);
    draftText(p.get(0), "typing...");
    f.sessions.onConnection(CODE, p.get(0), false);
    f.sessions.onConnection(CODE, p.get(0), true);

    Map<String, Object> snap = f.sessions.gameSnapshot(CODE, p.get(0));
    assertThat(snap).containsEntry("phase", "PROMPT").containsEntry("submitted", false);
    assertThat(snap).containsEntry("step", 0).containsEntry("totalSteps", 4);
    assertThat(snap).containsEntry("entriesPerChain", 4).containsEntry("kind", "PROMPT");
    assertThat(snap.get("draft")).isEqualTo(Map.of("text", "typing..."));
    @SuppressWarnings("unchecked")
    Map<String, Object> task = (Map<String, Object>) snap.get("task");
    assertThat(task).containsEntry("kind", "PROMPT");
    assertThat(task.get("source")).isNull();
    assertThat(snap).containsEntry("waitingOn", 4).containsEntry("reveal", null);

    text(p.get(0), "done");
    snap = f.sessions.gameSnapshot(CODE, p.get(0));
    assertThat(snap).containsEntry("submitted", true).containsEntry("waitingOn", 3);
    assertThat(snap.get("draft")).isNull();
    assertThat(snap.get("submittedGuestIds")).isEqualTo(List.of(p.get(0)));
  }

  @Test
  void TP9_aDrawingDraftIsRestoredFromTheSnapshot() {
    List<UUID> p = start(4);
    everybodySubmits(p, "a");
    UUID drawer = holder(p, 0, 1);
    UUID id = UUID.randomUUID();
    stroke(drawer, id, 4);

    @SuppressWarnings("unchecked")
    Map<String, Object> draft =
        (Map<String, Object>) f.sessions.gameSnapshot(CODE, drawer).get("draft");
    assertThat(containsValue(draft.get("strokes"), id.toString())).isTrue();
    assertThat(draft).containsKey("canvasBudget");
  }

  @Test
  void TP8_anEntryReachesOnlyItsNextHolderAndNeverAPublicEventOrAnotherSnapshot() {
    List<UUID> p = start(4);
    for (int i = 0; i < 4; i++) {
      text(p.get(i), "secret-" + i);
    }
    // step 1 running: "secret-c" belongs to chain c's holder only
    for (int c = 0; c < 4; c++) {
      String needle = "secret-" + c;
      UUID next = holder(p, c, 1);
      for (Published e : f.sink.events) {
        boolean carries = containsValue(e.payload(), needle);
        if (e.isPublic()) {
          assertThat(carries).as("public %s carries %s", e.type(), needle).isFalse();
        } else if (e.type().equals("TELEPHONE_TASK")) {
          boolean step1 = e.payload().get("step").equals(1);
          assertThat(carries).isEqualTo(step1 && e.recipient().equals(next));
        } else {
          assertThat(carries).isFalse();
        }
      }
      for (UUID viewer : p) {
        Map<String, Object> snap = f.sessions.gameSnapshot(CODE, viewer);
        assertThat(containsValue(snap, needle)).isEqualTo(viewer.equals(next));
      }
    }
    // an outsider has no game view at all
    assertThat(f.sessions.gameSnapshot(CODE, UUID.randomUUID())).isNull();
  }

  @Test
  void TP8_theSnapshotOfAFinishedGameCarriesNoTaskOrContent() {
    List<UUID> p = start(4);
    for (int i = 0; i < 4; i++) {
      everybodySubmits(p, "k" + i);
    }
    revealAll();

    Map<String, Object> snap = f.sessions.gameSnapshot(CODE, p.get(0));
    assertThat(snap).containsEntry("status", "FINISHED");
    assertThat(snap.get("reveal")).isNull();
    assertThat(snap.get("task")).isNull();
    assertThat(snap.get("draft")).isNull();
    assertThat(snap.get("kind")).isNull();
    assertThat(snap).containsEntry("step", 4);
    assertThat(containsValue(snap, "k0")).isFalse();
  }

  // ------------------------------------------------------------------ size bound

  /** A drawing of 300 strokes with 40 points each: the 12 000-point budget at maximal digits. */
  private void maximalDrawing(UUID drawer) {
    for (int i = 0; i < 300; i++) {
      stroke(drawer, UUID.randomUUID(), 40);
    }
  }

  @Test
  void TP9_aMaximalDraftInTheDrawSnapshotStaysBelowTheEngineBound() {
    List<UUID> p = start(4);
    for (UUID g : p) {
      text(g, "t".repeat(140));
    }
    UUID drawer = holder(p, 0, 1);
    maximalDrawing(drawer);

    byte[] snapshot =
        JsonMapper.builder().build().writeValueAsBytes(f.sessions.gameSnapshot(CODE, drawer));
    assertThat(snapshot.length).isLessThan(262_144);
    assertThat(snapshot.length).isGreaterThan(150_000); // it really is the large case
  }

  @Test
  void TP9_aMaximalDrawingSourceFitsTheDescribeSnapshotAndTheTaskEvent() {
    List<UUID> p = start(4);
    for (UUID g : p) {
      text(g, "t");
    }
    UUID drawer = holder(p, 0, 1);
    maximalDrawing(drawer);
    for (UUID g : p) {
      submitDrawing(g);
    }
    UUID describer = holder(p, 0, 2);
    draftText(describer, "d".repeat(140));

    JsonMapper json = JsonMapper.builder().build();
    byte[] snapshot = json.writeValueAsBytes(f.sessions.gameSnapshot(CODE, describer));
    byte[] task = json.writeValueAsBytes(tasks().get(describer));
    assertThat(snapshot.length).isLessThan(262_144);
    assertThat(task.length).isLessThan(262_144);
    assertThat(snapshot.length).isGreaterThan(150_000); // it really is the large case
  }

  // ------------------------------------------------------------------ invariants

  @Test
  void invariantWalk_randomInterleavingsKeepEveryStepAPermutation() {
    for (int seed = 0; seed < 12; seed++) {
      SessionFixture g = new SessionFixture(new TelephoneModule(TestWords.service()));
      java.util.Random random = new java.util.Random(seed);
      int n = 4 + random.nextInt(5);
      g.players(n);
      g.startAndPlay();
      @SuppressWarnings("unchecked")
      List<UUID> order =
          (List<UUID>) g.sink.ofType("GAME_STARTED").getLast().payload().get("order");
      Set<UUID> left = new java.util.HashSet<>();
      Map<UUID, String> leftIn = new HashMap<>();
      Map<String, UUID> authorOf = new HashMap<>();
      int counter = 0;
      int guard = 0;
      while ("RUNNING".equals(g.sessions.gameSnapshot(CODE, firstActive(order, left)).get("status"))
          && guard++ < 600) {
        String phaseId = g.phaseId();
        String phase = (String) g.sink.ofType("PHASE_STARTED").getLast().payload().get("phase");
        if (phase.equals("REVEAL")) { // nobody steps: the idle auto-advance ends the reveal
          g.clock.advance(Duration.ofSeconds(60));
          g.scheduler.fireDue(g.clock, false);
          continue;
        }
        // every non-left holder got exactly one task this step, none went to a left player
        Map<UUID, Map<String, Object>> tasks = new HashMap<>();
        for (Published e : g.sink.ofType("TELEPHONE_TASK")) {
          if (e.payload().get("phaseId").toString().equals(phaseId)) {
            assertThat(tasks.put(e.recipient(), e.payload())).isNull();
          }
        }
        // nobody who left in an earlier phase is asked again
        tasks.keySet().forEach(who -> assertThat(leftIn.get(who)).isIn(null, phaseId));
        tasks.forEach(
            (who, task) -> {
              Object text = source(task) == null ? null : source(task).get("text");
              if (text != null && authorOf.containsKey(text.toString())) {
                assertThat(authorOf.get(text.toString())).isNotEqualTo(who);
              }
            });
        int action = random.nextInt(10);
        UUID who = order.get(random.nextInt(n));
        try {
          if (action < 6 && !left.contains(who)) {
            if (phase.equals("DRAW")) {
              g.sessions.submit(CODE, who, "TELEPHONE_SUBMIT_DRAWING", phaseId, Map.of());
            } else {
              String token = "w" + counter++;
              authorOf.put(token, who);
              g.sessions.submit(CODE, who, "TELEPHONE_SUBMIT_TEXT", phaseId, Map.of("text", token));
            }
          } else if (action == 6 && left.size() < n - 3 && !left.contains(who)) {
            left.add(who);
            leftIn.put(who, phaseId);
            g.room.removeMember(who);
            g.sessions.onMemberLeft(CODE, who);
          } else if (action == 7) {
            g.sessions.onConnection(CODE, who, random.nextBoolean());
          } else if (action == 8) {
            g.clock.advance(Duration.ofSeconds(1 + random.nextInt(100)));
            g.scheduler.fireDue(g.clock, false);
          }
        } catch (DomainException expected) {
          assertThat(expected.code())
              .isIn(ErrorCode.ALREADY_SUBMITTED, ErrorCode.NOT_IN_ROOM, ErrorCode.INVALID_PHASE);
        }
      }
      assertThat(g.sink.publicTypes()).as("seed %d finishes", seed).contains("GAME_FINISHED");
    }
  }

  // ------------------------------------------------------------------ reveal (P6-03)

  @Test
  void T3_afterTheLastStepTheRevealStartsWithThePromptOfTheFirstAlbum() {
    List<UUID> p = start(4);
    for (int i = 0; i < 4; i++) {
      text(p.get(i), "first-" + i);
    }
    playSteps(p);

    Map<String, Object> started = last("PHASE_STARTED");
    assertThat(started)
        .containsEntry("phase", "REVEAL")
        .containsEntry("deadlineAt", null)
        .containsEntry("totalSteps", 4)
        .containsEntry("chains", 4)
        .containsEntry("cursor", Map.of("chainIndex", 0, "entryIndex", 0))
        .containsEntry("autoAdvanceAt", Timestamps.format(f.clock.instant().plusSeconds(60)));
    Map<String, Object> revealed = last("ALBUM_REVEALED");
    assertThat(revealed)
        .containsEntry("chainIndex", 0)
        .containsEntry("entryIndex", 0)
        .containsEntry("newlyRevealed", true)
        .containsEntry("autoAdvanced", false);
    assertThat(map(revealed.get("entry")))
        .containsEntry("entryIndex", 0)
        .containsEntry("kind", "TEXT")
        .containsEntry("text", "first-0")
        .containsEntry("authorGuestId", p.getFirst())
        .containsEntry("authorNickname", nickname(p.getFirst()))
        .containsEntry("omitted", false)
        .containsEntry("fill", "NONE")
        .containsEntry("placeholder", false)
        .containsEntry("timedOut", false)
        .doesNotContainKey("strokes");
    // the reveal is part of the running game: no results yet
    assertThat(f.sink.publicTypes()).doesNotContain("GAME_FINISHED");
    assertThat(f.recorder.results).isEmpty();
    // only (0,0) is public: no other album's prompt left the server
    for (int c = 1; c < 4; c++) {
      String needle = "first-" + c;
      assertThat(f.sink.events)
          .filteredOn(Published::isPublic)
          .noneMatch(e -> containsValue(e.payload(), needle));
    }
  }

  private String nickname(UUID guest) {
    return f.room.members.stream()
        .filter(m -> m.guestId().equals(guest))
        .findFirst()
        .orElseThrow()
        .nickname();
  }

  @Test
  void TP6_onlyTheCurrentHostMovesTheCursorAndAStaleOrMalformedCursorIsRejected() {
    List<UUID> p = start(4);
    playSteps(p);
    UUID host = f.host();
    UUID other = p.stream().filter(g -> !g.equals(host)).findFirst().orElseThrow();

    assertThat(rejected(() -> revealNext(other))).isEqualTo(ErrorCode.NOT_HOST);
    assertThat(rejected(() -> submit(host, "REVEAL_NEXT", Map.of("chainIndex", 0))))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(
            rejected(() -> submit(host, "REVEAL_NEXT", Map.of("chainIndex", "0", "entryIndex", 0))))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    Map<String, Object> seen = cursor();

    revealNext(host);
    assertThat(last("ALBUM_REVEALED"))
        .containsEntry("chainIndex", 0)
        .containsEntry("entryIndex", 1)
        .containsEntry("newlyRevealed", true);
    assertThat(map(last("ALBUM_REVEALED").get("entry"))).containsEntry("kind", "DRAWING");

    // a double click carries the cursor it saw: nothing is skipped
    assertThat(rejected(() -> submit(host, "REVEAL_NEXT", seen))).isEqualTo(ErrorCode.STALE_ACTION);
    assertThat(f.sink.ofType("ALBUM_REVEALED")).hasSize(2);
    // step commands are refused in the reveal
    assertThat(rejected(() -> text(host, "late"))).isEqualTo(ErrorCode.INVALID_PHASE);
  }

  @Test
  void TP6_prevWalksBackOverRevealedEntriesAcrossAlbumsAndIsANoOpAtTheStart() {
    List<UUID> p = start(4);
    playSteps(p);
    UUID host = f.host();

    revealPrev(host); // (0,0): acknowledged, nothing published
    assertThat(f.sink.ofType("ALBUM_REVEALED")).hasSize(1);

    for (int i = 0; i < 4; i++) {
      revealNext(host); // up to (1,0)
    }
    assertThat(cursor()).isEqualTo(Map.of("chainIndex", 1, "entryIndex", 0));
    revealPrev(host);
    assertThat(last("ALBUM_REVEALED"))
        .containsEntry("chainIndex", 0)
        .containsEntry("entryIndex", 3)
        .containsEntry("newlyRevealed", false)
        .containsEntry("entry", null);
    revealNext(host); // back to (1,0): already revealed, so no content again
    assertThat(last("ALBUM_REVEALED"))
        .containsEntry("chainIndex", 1)
        .containsEntry("entryIndex", 0)
        .containsEntry("newlyRevealed", false)
        .containsEntry("entry", null);
    revealNext(host);
    assertThat(last("ALBUM_REVEALED")).containsEntry("newlyRevealed", true);
  }

  @Test
  void T5_nextOnTheLastEntryOfTheLastAlbumFinishesTheGameWithoutStandings() {
    List<UUID> p = start(4);
    playSteps(p);

    revealAll();

    assertThat(f.sink.ofType("ALBUM_REVEALED")).hasSize(16);
    assertThat(f.sink.ofType("ALBUM_REVEALED"))
        .allSatisfy(e -> assertThat(e.payload()).containsEntry("newlyRevealed", true));
    assertThat(last("GAME_FINISHED")).containsEntry("endReason", "COMPLETED");
    assertThat(f.recorder.results.getFirst().standings()).isEmpty();
    assertThat(f.room.status).isEqualTo(RoomStatus.RESULTS);
  }

  @Test
  void TP6_theIdleAutoAdvanceMovesAfter60sAndAfter15sWhileTheHostIsDisconnected() {
    List<UUID> p = start(4);
    playSteps(p);
    UUID host = f.host();
    Map<String, Object> seen = cursor();

    advance(Duration.ofSeconds(59));
    assertThat(f.sink.ofType("ALBUM_REVEALED")).hasSize(1);
    advance(Duration.ofSeconds(1));
    assertThat(last("ALBUM_REVEALED"))
        .containsEntry("entryIndex", 1)
        .containsEntry("autoAdvanced", true)
        .containsEntry("newlyRevealed", true);
    // the host's click that raced the auto-advance carries the old cursor
    assertThat(rejected(() -> submit(host, "REVEAL_NEXT", seen))).isEqualTo(ErrorCode.STALE_ACTION);

    advance(Duration.ofSeconds(10));
    f.sessions.onConnection(CODE, host, false);
    advance(Duration.ofSeconds(14));
    assertThat(last("ALBUM_REVEALED")).containsEntry("entryIndex", 1);
    advance(Duration.ofSeconds(1));
    assertThat(last("ALBUM_REVEALED")).containsEntry("entryIndex", 2);
    // still away: the next one follows 15 s after that move
    advance(Duration.ofSeconds(15));
    assertThat(last("ALBUM_REVEALED")).containsEntry("entryIndex", 3);
    assertThat(map(f.sessions.gameSnapshot(CODE, p.getFirst()).get("reveal")))
        .containsEntry("autoAdvanceAt", Timestamps.format(f.clock.instant().plusSeconds(15)));
  }

  @Test
  void TP10_aNewHostDrivesTheRevealAndTheOldOneCannot() {
    List<UUID> p = start(4);
    playSteps(p);
    UUID old = f.host();
    UUID next = p.stream().filter(g -> !g.equals(old)).findFirst().orElseThrow();

    f.room.host = next;
    f.sessions.onHostChanged(CODE, next);

    assertThat(rejected(() -> revealNext(old))).isEqualTo(ErrorCode.NOT_HOST);
    revealNext(next);
    assertThat(last("ALBUM_REVEALED")).containsEntry("entryIndex", 1);
  }

  @Test
  void TP11_fewerThanThreeActivePlayersEndARunningReveal() {
    List<UUID> p = start(4);
    playSteps(p);
    revealNext(f.host());
    List<UUID> others = p.stream().filter(g -> !g.equals(f.host())).toList();

    leave(others.get(0));
    leave(others.get(1));

    assertThat(last("GAME_FINISHED")).containsEntry("endReason", "INSUFFICIENT_PLAYERS");
    assertThat(f.recorder.rounds).hasSize(4);
  }

  @Test
  void TP6_theRevealSnapshotRestoresCursorHighWaterAndOnlyRevealedEntries() {
    List<UUID> p = start(4);
    for (int i = 0; i < 4; i++) {
      text(p.get(i), "prompt-" + i);
    }
    playSteps(p);
    UUID host = f.host();
    for (int i = 0; i < 4; i++) {
      revealNext(host); // up to (1,0)
    }
    revealPrev(host); // cursor (0,3), highWater (1,0)

    Map<String, Object> snap = f.sessions.gameSnapshot(CODE, p.get(2));
    assertThat(snap)
        .containsEntry("phase", "REVEAL")
        .containsEntry("deadlineAt", null)
        .containsEntry("step", 4)
        .containsEntry("kind", null)
        .containsEntry("task", null)
        .containsEntry("draft", null)
        .containsEntry("waitingOn", 0);
    Map<String, Object> reveal = map(snap.get("reveal"));
    assertThat(reveal)
        .containsEntry("cursor", Map.of("chainIndex", 0, "entryIndex", 3))
        .containsEntry("highWater", Map.of("chainIndex", 1, "entryIndex", 0))
        .containsEntry("chains", 4);
    List<Map<String, Object>> albums = list(reveal.get("albums"));
    assertThat(albums).hasSize(4);
    assertThat(albums.get(0))
        .containsEntry("chainIndex", 0)
        .containsEntry("ownerGuestId", p.get(0))
        .containsEntry("entryCount", 4)
        .containsEntry("revealedCount", 4);
    assertThat(list(albums.get(0).get("entries")).stream().map(e -> e.get("entryIndex")))
        .containsExactly(0, 1, 2, 3);
    assertThat(albums.get(1)).containsEntry("revealedCount", 1);
    assertThat(list(albums.get(1).get("entries")))
        .singleElement()
        .satisfies(e -> assertThat(e).containsEntry("text", "prompt-1"));
    assertThat(albums.get(2)).containsEntry("revealedCount", 0);
    assertThat(list(albums.get(2).get("entries"))).isEmpty();
    // unrevealed albums are nowhere in the snapshot
    assertThat(containsValue(snap, "prompt-2")).isFalse();
    assertThat(containsValue(snap, "prompt-3")).isFalse();
  }

  @Test
  void TP13_everyEntryIsRecordedOnceWithItsCellAuthorAndFill() {
    List<UUID> p = start(4);
    everybodySubmits(p, "a"); // step 0 by hand
    advance(Duration.ofSeconds(90)); // step 1 (DRAW) times out: empty placeholders
    playSteps(p);

    assertThat(f.recorder.submissions).hasSize(16);
    assertThat(
            f.recorder.submissions.stream()
                .map(r -> r.chainIndex() + ":" + r.stepNo())
                .distinct()
                .count())
        .isEqualTo(16);
    assertThat(f.recorder.submissions)
        .allSatisfy(r -> assertThat(r.entriesPerChain()).isEqualTo(4));
    for (SubmissionRecord r : f.recorder.submissions) {
      assertThat(r.authorGuestId()).isEqualTo(holder(p, r.chainIndex(), r.stepNo()));
      if (r.stepNo() == 1) {
        assertThat(r.kind()).isEqualTo(SubmissionRecord.Kind.DRAWING);
        assertThat(r.strokes()).isEmpty();
        assertThat(r.fill()).isEqualTo("TIMEOUT");
        assertThat(r.placeholder()).isTrue();
        assertThat(r.timedOut()).isTrue();
        assertThat(r.text()).isNull();
      }
      if (r.stepNo() == 0) {
        assertThat(r.kind()).isEqualTo(SubmissionRecord.Kind.TEXT);
        assertThat(r.fill()).isEqualTo("NONE");
        assertThat(r.strokes()).isNull();
      }
    }
  }

  @Test
  void TP9_theMaximalRevealSnapshotStaysBelowTheEngineBoundAndOmitsOlderDrawings() {
    List<UUID> p = start(8); // E = 8: 32 drawings, 32 texts
    for (UUID g : p) {
      text(g, "t".repeat(140));
    }
    for (int step = 1; step < 8; step++) {
      for (UUID g : p) {
        if (step % 2 == 1) {
          maximalDrawing(g);
          submitDrawing(g);
        } else {
          text(g, "d".repeat(140));
        }
      }
    }
    assertThat(phase()).isEqualTo("REVEAL");
    for (int i = 0; i < 63; i++) {
      revealNext(f.host()); // cursor on the last entry: everything revealed
    }

    JsonMapper json = JsonMapper.builder().build();
    Map<String, Object> snap = f.sessions.gameSnapshot(CODE, p.get(3));
    byte[] bytes = json.writeValueAsBytes(snap);
    assertThat(bytes.length).isLessThan(262_144);
    assertThat(bytes.length).isGreaterThan(200_000); // it really is the large case
    List<Map<String, Object>> entries =
        list(map(snap.get("reveal")).get("albums")).stream()
            .flatMap(a -> list(a.get("entries")).stream())
            .toList();
    assertThat(entries).hasSize(64);
    assertThat(entries)
        .filteredOn(e -> e.get("kind").equals("TEXT"))
        .hasSize(32)
        .allSatisfy(e -> assertThat(e).containsKey("text"));
    // the cursor entry (7,7) always carries its strokes; most older drawings are withheld
    Map<String, Object> cursorEntry = entries.getLast();
    assertThat(cursorEntry).containsEntry("omitted", false);
    assertThat(list(cursorEntry.get("strokes"))).hasSize(300);
    List<Map<String, Object>> omitted =
        entries.stream().filter(e -> Boolean.TRUE.equals(e.get("omitted"))).toList();
    assertThat(omitted).hasSizeGreaterThan(25);
    assertThat(omitted).allSatisfy(e -> assertThat(e).containsEntry("strokes", null));
  }

  private static UUID firstActive(List<UUID> order, Set<UUID> left) {
    return order.stream().filter(x -> !left.contains(x)).findFirst().orElseThrow();
  }
}
