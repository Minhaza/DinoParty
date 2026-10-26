package com.dinoparty.session;

import static com.dinoparty.session.SessionFixture.CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.RandomSource;
import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.engine.GameDefinition;
import com.dinoparty.games.impostor.DinoImpostorModule;
import com.dinoparty.session.SessionFixture.Published;
import com.dinoparty.words.TestWords;
import com.dinoparty.words.TestWords.Catalog;
import com.dinoparty.words.WordDifficulty;
import com.dinoparty.words.WordPoolService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Dino Impostor through the real engine with a manual clock (P9-02): roles, seats, turns, the
 * stroke policy and every rejection of [DI-6]/[DI-7], the edge cases of game-rules Game 3, leaving
 * and disconnecting. Voting, the guess and the reveal are in {@link DinoImpostorVotingSessionTest}.
 */
class DinoImpostorSessionTest {

  private static final Duration TURN = Duration.ofSeconds(25);
  private static final Duration REVEAL = Duration.ofSeconds(5);
  private static final Duration VOTE = Duration.ofSeconds(60);
  private static final Duration ROUND_END = Duration.ofSeconds(8);

  private SessionFixture f = fixture(new SeededRandomSource(11));
  private List<UUID> players;

  private static SessionFixture fixture(RandomSource secret) {
    return fixture(TestWords.service(), secret);
  }

  private static SessionFixture fixture(WordPoolService words, RandomSource secret) {
    return new SessionFixture(new DinoImpostorModule(words, secret));
  }

  // ------------------------------------------------------------------ helpers

  /** Starts a game of {@code n} and lets the countdown pass: ROLE_REVEAL of round 1 is running. */
  private void start(int n) {
    start(n, Map.of());
  }

  private void start(int n, Map<String, Object> settings) {
    f.room.settings = settings;
    players = f.players(n);
    f.startAndPlay();
  }

  private void advance(Duration d) {
    f.clock.advance(d);
    f.scheduler.fireDue(f.clock, false);
  }

  private Map<String, Object> lastPhase() {
    return f.sink.ofType("PHASE_STARTED").getLast().payload();
  }

  private String phase() {
    return (String) lastPhase().get("phase");
  }

  private UUID drawer() {
    return (UUID) lastPhase().get("drawerGuestId");
  }

  private int turn() {
    return (Integer) lastPhase().get("turn");
  }

  /** The IMPOSTOR_ROLE events of the latest round, by recipient. */
  private Map<UUID, Map<String, Object>> roles() {
    int round = (Integer) lastPhase().get("round");
    Map<UUID, Map<String, Object>> roles = new HashMap<>();
    for (Published e : f.sink.ofType("IMPOSTOR_ROLE")) {
      if (e.payload().get("round").equals(round)) {
        roles.put(e.recipient(), e.payload());
      }
    }
    return roles;
  }

  private UUID impostor() {
    return roles().entrySet().stream()
        .filter(e -> e.getValue().get("role").equals("IMPOSTOR"))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow();
  }

  private Map<String, Object> snapshot(UUID viewer) {
    return f.sessions.gameSnapshot(CODE, viewer);
  }

  @SuppressWarnings("unchecked")
  private String colorOf(UUID guest) {
    return ((Map<UUID, String>) snapshot(players.getFirst()).get("colors")).get(guest);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> strokes() {
    return (List<Map<String, Object>>) snapshot(players.getFirst()).get("strokes");
  }

  @SuppressWarnings("unchecked")
  private List<UUID> seats() {
    return (List<UUID>) snapshot(players.getFirst()).get("seatOrder");
  }

  private static Map<String, Object> batch(
      UUID strokeId, int batchNo, String tool, String color, int points, boolean last) {
    List<List<Integer>> pts = new ArrayList<>();
    for (int i = 0; i < points; i++) {
      pts.add(List.of(100 * i, 50));
    }
    Map<String, Object> p = new HashMap<>();
    p.put("strokeId", strokeId.toString());
    p.put("batchNo", batchNo);
    p.put("tool", tool);
    p.put("color", color);
    p.put("size", 2);
    p.put("points", pts);
    p.put("final", last);
    return p;
  }

  private void draw(UUID guest, UUID strokeId, int batchNo, int points, boolean last) {
    draw(guest, f.phaseId(), batch(strokeId, batchNo, "PENCIL", colorOf(guest), points, last));
  }

  private void draw(UUID guest, String phaseId, Map<String, Object> payload) {
    f.sessions.submit(CODE, guest, "IMPOSTOR_DRAW_STROKE", phaseId, payload);
  }

  private static ErrorCode rejected(Runnable action) {
    try {
      action.run();
    } catch (DomainException e) {
      return e.code();
    }
    throw new AssertionError("expected a rejection");
  }

  /** Plays timeouts until the latest phase is a DRAWING turn whose drawer satisfies {@code ok}. */
  private void toTurnOf(java.util.function.Predicate<UUID> ok) {
    for (int guard = 0; guard < 40; guard++) {
      if (phase().equals("DRAWING") && ok.test(drawer())) {
        return;
      }
      advance(phase().equals("ROLE_REVEAL") ? REVEAL : TURN);
    }
    throw new AssertionError("no such turn");
  }

  /** Nobody votes: the vote times out (escaped), then the 8 s reveal passes. */
  private void endRound() {
    advance(VOTE);
    advance(ROUND_END);
  }

  private void leave(UUID guest) {
    f.room.removeMember(guest);
    f.sessions.onMemberLeft(CODE, guest);
  }

  // ------------------------------------------------------------------ definition and start

  @Test
  void theModuleDeclaresTheContractDefinition() {
    GameDefinition d =
        new DinoImpostorModule(TestWords.service(), new SeededRandomSource(1)).definition();
    assertThat(d.key()).isEqualTo("dino-impostor");
    assertThat(d.minPlayers()).isEqualTo(4);
    assertThat(d.maxPlayers()).isEqualTo(10);
    assertThat(d.minActivePlayers()).isEqualTo(3);
    assertThat(d.scored()).isTrue();
    assertThat(d.commandsByType())
        .containsOnlyKeys(
            "IMPOSTOR_DRAW_STROKE", "IMPOSTOR_CHAT", "IMPOSTOR_VOTE", "IMPOSTOR_GUESS");
    var spec = d.commandsByType().get("IMPOSTOR_DRAW_STROKE");
    assertThat(spec.maxPayloadBytes()).isEqualTo(8 * 1024);
    assertThat(spec.ratePerSecond()).isEqualTo(30.0);
    assertThat(spec.burst()).isEqualTo(40);
    assertThat(spec.phases()).containsExactly("DRAWING");
    assertThat(d.publicEvents())
        .containsExactlyInAnyOrder(
            "IMPOSTOR_DRAWING_UPDATED",
            "IMPOSTOR_CHAT_MESSAGE",
            "IMPOSTOR_VOTE_PROGRESS",
            "IMPOSTOR_VOTE_CLOSED",
            "IMPOSTOR_ROUND_ENDED");
    assertThat(d.privateEvents()).containsExactlyInAnyOrder("IMPOSTOR_ROLE", "IMPOSTOR_GUESS_OPEN");
    assertThat(d.commandsByType().get("IMPOSTOR_CHAT").phases())
        .containsExactlyInAnyOrder("VOTE", "GALLERY");
    assertThat(d.commandsByType().get("IMPOSTOR_VOTE").phases())
        .containsExactlyInAnyOrder("VOTE", "GALLERY");
    assertThat(d.commandsByType().get("IMPOSTOR_GUESS").phases()).containsExactly("GUESS");
    assertThat(d.metadata().iconKey()).isEqualTo("dino-impostor");
  }

  @Test
  void DI2_aWordPoolTooSmallRejectsTheStartAndNoSessionExists() {
    Catalog tiny = new Catalog("animals").add("cat", "animals", WordDifficulty.EASY);
    tiny.add("dog", "animals", WordDifficulty.EASY).add("cow", "animals", WordDifficulty.MEDIUM);
    f = fixture(new WordPoolService(tiny, new TestWords.Packs()), new SeededRandomSource(1));
    f.room.settings = Map.of("rounds", 3, "difficulty", "easy");
    f.players(4);

    assertThat(rejected(() -> f.sessions.start(CODE, f.host())))
        .isEqualTo(ErrorCode.WORD_POOL_TOO_SMALL);
    assertThat(f.sessions.isActive(CODE)).isFalse();
    assertThat(f.sink.events).isEmpty();
  }

  @Test
  void DI2_automaticRoundsAreThePlayerCountAndFixedRoundsAreKept() {
    start(5);
    assertThat(lastPhase()).containsEntry("phase", "ROLE_REVEAL").containsEntry("round", 1);
    assertThat(lastPhase()).containsEntry("totalRounds", 5);

    f = fixture(new SeededRandomSource(2));
    start(6, Map.of("rounds", 3));
    assertThat(lastPhase()).containsEntry("totalRounds", 3);
  }

  // ------------------------------------------------------------------ roles and seats

  @Test
  void DI4_everySeatedPlayerGetsOneRoleOfTheSameShapeInTheRevealTransition() {
    start(6);
    String revealPhase = f.phaseId();
    List<Published> events = f.sink.events;
    int phaseIndex = events.indexOf(f.sink.ofType("PHASE_STARTED").getLast());
    List<Published> roleEvents = f.sink.ofType("IMPOSTOR_ROLE");

    assertThat(roleEvents).hasSize(6);
    assertThat(roleEvents)
        .extracting(Published::recipient)
        .containsExactlyInAnyOrderElementsOf(players);
    // One transition: right after its PHASE_STARTED, nothing in between, same phaseId.
    assertThat(events.subList(phaseIndex + 1, phaseIndex + 7))
        .containsExactlyElementsOf(roleEvents);
    Set<List<String>> shapes = new HashSet<>();
    for (Published e : roleEvents) {
      assertThat(e.payload().get("phaseId").toString()).isEqualTo(revealPhase);
      shapes.add(List.copyOf(e.payload().keySet()));
    }
    assertThat(shapes).containsExactly(List.of("phaseId", "round", "role", "category", "word"));

    Map<UUID, Map<String, Object>> roles = roles();
    UUID impostor = impostor();
    assertThat(roles.values()).filteredOn(r -> r.get("role").equals("IMPOSTOR")).hasSize(1);
    assertThat(roles.get(impostor).get("word")).isNull();
    Set<Object> words = new HashSet<>();
    Set<Object> categories = new HashSet<>();
    roles.forEach(
        (guest, r) -> {
          categories.add(r.get("category"));
          if (!guest.equals(impostor)) {
            words.add(r.get("word"));
          }
        });
    assertThat(words).hasSize(1).doesNotContainNull();
    assertThat(categories).hasSize(1);
    // The snapshot role is the same as the event's.
    for (UUID p : players) {
      Map<String, Object> role = new HashMap<>(roles.get(p));
      role.remove("phaseId");
      role.remove("round");
      assertThat(snapshot(p).get("role")).isEqualTo(role);
    }
  }

  @Test
  void G8_theRoleRevealPhaseStartedCarriesTheNicknamesOfTheFrozenRoster() {
    start(4);
    Map<String, Object> reveal = lastPhase();

    assertThat(reveal).containsEntry("phase", "ROLE_REVEAL");
    @SuppressWarnings("unchecked")
    Map<UUID, String> nicknames = (Map<UUID, String>) reveal.get("nicknames");
    assertThat(nicknames).containsOnlyKeys(players);
    assertThat(nicknames).isEqualTo(snapshot(players.get(1)).get("nicknames"));
    assertThat(nicknames.values()).allMatch(n -> n.matches("P\\d"));
  }

  @Test
  void DI5_seatsArePublicTheImpostorIsNeverFirstAndEachRoundIsReshuffled() {
    start(5, Map.of("rounds", 5));
    Set<List<UUID>> seatOrders = new HashSet<>();
    for (int round = 1; round <= 5; round++) {
      advance(REVEAL);
      assertThat(phase()).isEqualTo("DRAWING");
      @SuppressWarnings("unchecked")
      List<UUID> seats = (List<UUID>) lastPhase().get("seatOrder");
      assertThat(seats).containsExactlyInAnyOrderElementsOf(players);
      assertThat(seats.getFirst()).isNotEqualTo(impostor());
      seatOrders.add(seats);
      while (!phase().equals("VOTE")) {
        advance(TURN);
      }
      endRound();
    }
    assertThat(seatOrders).hasSizeGreaterThan(1);
  }

  @Test
  void DI3_withNobodyLeavingEveryoneIsTheImpostorOnceOverNRounds() {
    start(4, Map.of("rounds", 4));
    Map<UUID, Integer> counts = new HashMap<>();
    for (int round = 1; round <= 4; round++) {
      counts.merge(impostor(), 1, Integer::sum);
      advance(REVEAL);
      while (!phase().equals("VOTE")) {
        advance(TURN);
      }
      endRound();
    }
    assertThat(counts).containsOnlyKeys(players).allSatisfy((g, c) -> assertThat(c).isEqualTo(1));
  }

  @Test
  void DI2_D52_rolesAndWordsComeFromTheInjectedSourceNotTheSessionSeed() {
    // The fixture's session seed is the same in every run; only the injected source differs.
    List<List<Object>> runs = new ArrayList<>();
    for (long secret : new long[] {1, 1, 2, 3, 4}) {
      f = fixture(new SeededRandomSource(secret));
      start(6, Map.of("rounds", 3));
      List<Object> run = new ArrayList<>();
      for (int round = 1; round <= 3; round++) {
        run.add(players.indexOf(impostor()));
        UUID artist = players.stream().filter(p -> !p.equals(impostor())).findFirst().get();
        run.add(roles().get(artist).get("word"));
        advance(REVEAL);
        while (!phase().equals("VOTE")) {
          advance(TURN);
        }
        endRound();
      }
      runs.add(run);
    }
    assertThat(runs.get(0)).isEqualTo(runs.get(1));
    assertThat(new HashSet<>(runs.subList(1, 5))).hasSizeGreaterThan(1);
  }

  // ------------------------------------------------------------------ turns

  @Test
  void DI6_turnsFollowTheSeatsForLapsTimesNWithAFreshPhaseEach() {
    start(4, Map.of("laps", 2));
    advance(REVEAL);
    List<UUID> seats = seats();
    Set<String> phaseIds = new HashSet<>();
    for (int t = 0; t < 8; t++) {
      assertThat(lastPhase())
          .containsEntry("phase", "DRAWING")
          .containsEntry("turn", t)
          .containsEntry("lap", t / 4 + 1)
          .containsEntry("laps", 2)
          .containsEntry("totalTurns", 8)
          .containsEntry("drawerGuestId", seats.get(t % 4))
          .containsEntry("round", 1)
          .containsEntry("seatOrder", seats);
      assertThat(phaseIds.add(f.phaseId())).isTrue();
      Map<String, Object> view = snapshot(players.get(1));
      assertThat(view)
          .containsEntry("turn", t)
          .containsEntry("lap", t / 4 + 1)
          .containsEntry("drawerGuestId", seats.get(t % 4));
      advance(TURN);
    }
    assertThat(phase()).isEqualTo("VOTE");
    assertThat(snapshot(players.get(1)))
        .containsEntry("turn", null)
        .containsEntry("lap", null)
        .containsEntry("drawerGuestId", null);
  }

  @Test
  void DI6_DI7_batchesArePublishedWithTheirAuthorAndTheFinalOneEndsTheTurn() {
    start(4);
    advance(REVEAL);
    UUID drawer = drawer();
    UUID stroke = UUID.randomUUID();

    draw(drawer, stroke, 0, 3, false);
    assertThat(phase()).isEqualTo("DRAWING");
    assertThat(turn()).isZero();
    draw(drawer, stroke, 1, 2, true);

    List<Published> updates = f.sink.ofType("IMPOSTOR_DRAWING_UPDATED");
    assertThat(updates).hasSize(2).allMatch(Published::isPublic);
    assertThat(updates.get(0).payload())
        .containsEntry("kind", "STROKE")
        .containsEntry("authorGuestId", drawer)
        .containsEntry("strokeId", stroke)
        .containsEntry("batchNo", 0)
        .containsEntry("tool", "PENCIL")
        .containsEntry("color", colorOf(drawer))
        .containsEntry("size", 2)
        .containsEntry("final", false);
    assertThat(updates.get(1).payload()).containsEntry("final", true);
    // The final batch closes the turn: published before the next turn's PHASE_STARTED.
    List<Published> events = f.sink.events;
    assertThat(events.indexOf(updates.get(1)))
        .isLessThan(events.indexOf(f.sink.ofType("PHASE_STARTED").getLast()));
    assertThat(turn()).isEqualTo(1);
    assertThat(strokes())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s).containsEntry("authorGuestId", drawer).containsEntry("closed", true);
              assertThat((List<?>) s.get("points")).hasSize(5);
            });
  }

  @Test
  void DI6_aStrokeOutOfTurnIsNotDrawerAndChangesNothing() {
    start(4);
    advance(REVEAL);
    UUID other = players.stream().filter(p -> !p.equals(drawer())).findFirst().orElseThrow();
    int before = f.sink.events.size();

    assertThat(rejected(() -> draw(other, UUID.randomUUID(), 0, 3, true)))
        .isEqualTo(ErrorCode.NOT_DRAWER);
    assertThat(f.sink.events).hasSize(before);
    assertThat(strokes()).isEmpty();
    assertThat(turn()).isZero();
  }

  @Test
  void DI6_aSecondStrokeInTheTurnIsAlreadySubmitted() {
    start(4);
    advance(REVEAL);
    UUID drawer = drawer();
    draw(drawer, UUID.randomUUID(), 0, 3, false);

    assertThat(rejected(() -> draw(drawer, UUID.randomUUID(), 0, 3, false)))
        .isEqualTo(ErrorCode.ALREADY_SUBMITTED);
    assertThat(strokes()).hasSize(1);
    assertThat(f.sink.ofType("IMPOSTOR_DRAWING_UPDATED")).hasSize(1);
  }

  @Test
  void DI6_theEraserAndAnyColourButTheSendersAreValidationFailed() {
    start(4);
    advance(REVEAL);
    UUID drawer = drawer();
    UUID other = players.stream().filter(p -> !p.equals(drawer)).findFirst().orElseThrow();
    String own = colorOf(drawer);

    assertThat(
            rejected(
                () ->
                    draw(drawer, f.phaseId(), batch(UUID.randomUUID(), 0, "ERASER", own, 3, true))))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(
            rejected(
                () ->
                    draw(
                        drawer,
                        f.phaseId(),
                        batch(UUID.randomUUID(), 0, "PENCIL", colorOf(other), 3, true))))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(
            rejected(
                () ->
                    draw(
                        drawer,
                        f.phaseId(),
                        batch(UUID.randomUUID(), 0, "PENCIL", "#123456", 3, true))))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(strokes()).isEmpty();
    assertThat(f.sink.ofType("IMPOSTOR_DRAWING_UPDATED")).isEmpty();

    // The own colour is accepted in any letter case and republished unchanged.
    draw(
        drawer,
        f.phaseId(),
        batch(UUID.randomUUID(), 0, "PENCIL", own.toLowerCase(Locale.ROOT), 3, true));
    assertThat(strokes()).hasSize(1);
  }

  @Test
  void DI6_batchOrderAndStyleAreTheCanvasRules() {
    start(4);
    advance(REVEAL);
    UUID drawer = drawer();
    UUID stroke = UUID.randomUUID();
    assertThat(rejected(() -> draw(drawer, stroke, 1, 3, false)))
        .isEqualTo(ErrorCode.INVALID_ACTION);
    draw(drawer, stroke, 0, 3, false);
    assertThat(rejected(() -> draw(drawer, stroke, 2, 3, false)))
        .isEqualTo(ErrorCode.INVALID_ACTION);
    assertThat(rejected(() -> draw(drawer, stroke, 1, 201, false)))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(strokes())
        .singleElement()
        .satisfies(s -> assertThat((List<?>) s.get("points")).hasSize(3));
  }

  @Test
  void DI6_aBatchWithAnEarlierTurnsPhaseIdIsStale() {
    start(4);
    advance(REVEAL);
    UUID first = drawer();
    String firstPhase = f.phaseId();
    draw(first, UUID.randomUUID(), 0, 2, true); // turn 0 over
    UUID second = drawer();

    // Even the drawer of the new turn cannot use the old phase id.
    assertThat(
            rejected(
                () ->
                    draw(
                        second,
                        firstPhase,
                        batch(UUID.randomUUID(), 0, "PENCIL", colorOf(second), 2, true))))
        .isEqualTo(ErrorCode.STALE_ACTION);
    assertThat(strokes()).hasSize(1);
  }

  @Test
  void C6_DI7_aBatchAtTheDeadlineIsStaleAndTheOpenStrokeIsClosedAsItStood() {
    start(4);
    advance(REVEAL);
    UUID drawer = drawer();
    UUID stroke = UUID.randomUUID();
    String turnPhase = f.phaseId();
    draw(drawer, stroke, 0, 4, false);

    // The deadline has passed but no timer fired yet: the command itself drains it first.
    f.clock.advance(TURN);
    assertThat(
            rejected(
                () ->
                    draw(drawer, turnPhase, batch(stroke, 1, "PENCIL", colorOf(drawer), 2, true))))
        .isEqualTo(ErrorCode.STALE_ACTION);
    assertThat(turn()).isEqualTo(1);
    assertThat(strokes())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s).containsEntry("closed", true).containsEntry("authorGuestId", drawer);
              assertThat((List<?>) s.get("points")).hasSize(4);
            });
    // No event for the closing: the batches published are the drawer's own.
    assertThat(f.sink.ofType("IMPOSTOR_DRAWING_UPDATED")).hasSize(1);
  }

  @Test
  void C6_aBatchJustBeforeTheDeadlineIsAccepted() {
    start(4);
    advance(REVEAL);
    UUID drawer = drawer();
    advance(TURN.minusMillis(1));
    draw(drawer, UUID.randomUUID(), 0, 2, true);
    assertThat(strokes()).hasSize(1);
  }

  @Test
  void DI7_aTurnWithNoStrokeTimesOutEmpty() {
    start(4);
    advance(REVEAL);
    advance(TURN.minusSeconds(1));
    assertThat(turn()).isZero();
    advance(Duration.ofSeconds(1));
    assertThat(turn()).isEqualTo(1);
    assertThat(strokes()).isEmpty();
  }

  @Test
  void DI7_theDrawerLeavingMidTurnKeepsTheirStrokeAndTheirLaterTurnsAreSkipped() {
    start(5, Map.of("laps", 2));
    advance(REVEAL);
    UUID impostor = impostor();
    toTurnOf(d -> !d.equals(impostor));
    UUID drawer = drawer();
    int leftAt = turn();
    int seat = seats().indexOf(drawer);
    draw(drawer, UUID.randomUUID(), 0, 3, false);

    leave(drawer);

    assertThat(phase()).isEqualTo("DRAWING");
    assertThat(turn()).isEqualTo(leftAt + 1);
    assertThat(strokes())
        .singleElement()
        .satisfies(
            s ->
                assertThat(s).containsEntry("closed", true).containsEntry("authorGuestId", drawer));
    List<Integer> later = new ArrayList<>();
    while (phase().equals("DRAWING")) {
      assertThat(drawer()).isNotEqualTo(drawer);
      later.add(turn());
      advance(TURN);
    }
    // Turns after the one they left, minus their own second-lap turn if it was still ahead.
    int expected = (10 - leftAt - 1) - (seat + 5 > leftAt ? 1 : 0);
    assertThat(later).doesNotContain(seat + 5).hasSize(expected);
    assertThat(lastPhase()).containsEntry("phase", "VOTE");
  }

  @Test
  void DI7_aDisconnectedDrawerKeepsTheTurnUntilItsDeadline() {
    start(4);
    advance(REVEAL);
    UUID drawer = drawer();
    draw(drawer, UUID.randomUUID(), 0, 2, false);
    f.room.setConnected(drawer, false);
    f.sessions.onConnection(CODE, drawer, false);

    advance(TURN.minusSeconds(1));
    assertThat(turn()).isZero();
    assertThat(drawer()).isEqualTo(drawer);

    // Back within the grace and still their turn: the stroke can be finished.
    f.room.setConnected(drawer, true);
    f.sessions.onConnection(CODE, drawer, true);
    assertThat(turn()).isZero();
    advance(Duration.ofSeconds(1));
    assertThat(turn()).isEqualTo(1);
    assertThat(strokes())
        .singleElement()
        .satisfies(s -> assertThat(s).containsEntry("closed", true));
  }

  // ------------------------------------------------------------------ the impostor leaving

  @ParameterizedTest
  @ValueSource(strings = {"ROLE_REVEAL", "DRAWING", "VOTE"})
  void DI13_theImpostorLeavingEndsTheRoundAndTheNextRoundStarts(String when) {
    start(5, Map.of("rounds", 3));
    UUID impostor = impostor();
    if (!when.equals("ROLE_REVEAL")) {
      advance(REVEAL);
      draw(drawer(), UUID.randomUUID(), 0, 2, false);
    }
    if (when.equals("VOTE")) {
      while (!phase().equals("VOTE")) {
        advance(TURN);
      }
    }
    assertThat(phase()).isEqualTo(when);

    leave(impostor);

    // The round ends at once with the reveal; the next round starts when it has been shown.
    assertThat(lastPhase()).containsEntry("phase", "ROUND_END").containsEntry("round", 1);
    assertThat(f.sink.ofType("IMPOSTOR_ROUND_ENDED").getLast().payload())
        .containsEntry("outcome", "IMPOSTOR_LEFT")
        .containsEntry("impostorGuestId", impostor);
    advance(ROUND_END);
    assertThat(lastPhase()).containsEntry("phase", "ROLE_REVEAL").containsEntry("round", 2);
    assertThat(roles()).hasSize(4).doesNotContainKey(impostor);
    assertThat(seats()).hasSize(4).doesNotContain(impostor);
    assertThat(strokes()).isEmpty();
    assertThat(f.sink.ofType("GAME_FINISHED")).isEmpty();
  }

  @Test
  void DI13_theImpostorLeavingInTheLastRoundFinishesTheGame() {
    start(5, Map.of("rounds", 3));
    for (int round = 1; round < 3; round++) {
      advance(REVEAL);
      while (!phase().equals("VOTE")) {
        advance(TURN);
      }
      endRound();
    }
    assertThat(lastPhase()).containsEntry("round", 3);
    advance(REVEAL);

    leave(impostor());
    assertThat(f.sink.ofType("GAME_FINISHED")).isEmpty(); // the reveal is shown first
    advance(ROUND_END);

    assertThat(f.sink.ofType("GAME_FINISHED")).hasSize(1);
  }

  @Test
  void anArtistLeavingTheRoleRevealIsNotSeatedInTheTurns() {
    start(5, Map.of("laps", 1));
    UUID impostor = impostor();
    UUID artist = players.stream().filter(p -> !p.equals(impostor)).findFirst().orElseThrow();
    leave(artist);
    assertThat(phase()).isEqualTo("ROLE_REVEAL");

    advance(REVEAL);
    List<UUID> drawers = new ArrayList<>();
    while (phase().equals("DRAWING")) {
      drawers.add(drawer());
      advance(TURN);
    }
    assertThat(drawers).hasSize(4).doesNotContain(artist);
  }

  // ------------------------------------------------------------------ a game nobody votes in

  @Test
  void DI10_DI12_aGameWhereNobodyVotesIsEscapesAndTheImpostorsScoreTwoEach() {
    start(4, Map.of("rounds", 3, "laps", 1));
    for (int round = 1; round <= 3; round++) {
      assertThat(lastPhase()).containsEntry("phase", "ROLE_REVEAL").containsEntry("round", round);
      advance(REVEAL);
      while (!phase().equals("VOTE")) {
        advance(TURN);
      }
      assertThat(lastPhase().get("deadlineAt")).isNotNull();
      UUID someone = players.getFirst();
      assertThat(
              rejected(
                  () ->
                      draw(
                          someone,
                          f.phaseId(),
                          batch(UUID.randomUUID(), 0, "PENCIL", colorOf(someone), 2, true))))
          .isEqualTo(ErrorCode.INVALID_PHASE);
      endRound();
    }
    assertThat(f.sink.ofType("GAME_FINISHED")).hasSize(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> standings =
        (List<Map<String, Object>>)
            f.sink.ofType("GAME_FINISHED").getLast().payload().get("standings");
    assertThat(standings).hasSize(4);
    int asImpostor = 0;
    int escapes = 0;
    int score = 0;
    for (Map<String, Object> s : standings) {
      @SuppressWarnings("unchecked")
      Map<String, Object> detail = (Map<String, Object>) s.get("detail");
      assertThat(detail)
          .containsOnlyKeys("roundsAsImpostor", "escapes", "correctGuesses", "impostorsFound");
      asImpostor += (Integer) detail.get("roundsAsImpostor");
      escapes += (Integer) detail.get("escapes");
      score += (Integer) s.get("score");
    }
    assertThat(asImpostor).isEqualTo(3);
    assertThat(escapes).isEqualTo(3);
    assertThat(score).isEqualTo(6);
  }

  // ------------------------------------------------------------------ snapshot

  @Test
  void theSnapshotHasEveryFieldOfTheContractWithEmptyVotingParts() {
    start(4);
    advance(REVEAL);
    UUID drawer = drawer();
    draw(drawer, UUID.randomUUID(), 0, 3, false);
    Map<String, Object> view = snapshot(players.get(2));

    assertThat(view)
        .containsKeys(
            "round",
            "totalRounds",
            "laps",
            "seatOrder",
            "colors",
            "lap",
            "turn",
            "totalTurns",
            "drawerGuestId",
            "role",
            "strokes",
            "canvasBudget",
            "votedGuestIds",
            "myVote",
            "recentChat",
            "voteClosed",
            "roundEnd")
        .containsEntry("votedGuestIds", List.of())
        .containsEntry("myVote", null)
        .containsEntry("recentChat", List.of())
        .containsEntry("voteClosed", null)
        .containsEntry("roundEnd", null)
        .containsEntry("drawerGuestId", drawer)
        .containsEntry("totalTurns", 8);
    @SuppressWarnings("unchecked")
    Map<UUID, String> colors = (Map<UUID, String>) view.get("colors");
    assertThat(colors).containsOnlyKeys(players);
    assertThat(new HashSet<>(colors.values())).hasSize(4);
    assertThat(colors.values()).allMatch(c -> c.matches("#[0-9A-F]{6}"));
    assertThat(view.get("canvasBudget"))
        .isEqualTo(Map.of("points", 3, "maxPoints", 12_000, "strokes", 1, "maxStrokes", 300));
    assertThat(strokes())
        .singleElement()
        .satisfies(
            s ->
                assertThat(s)
                    .containsEntry("closed", false)
                    .containsEntry("authorGuestId", drawer));
  }
}
