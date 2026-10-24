package com.dinoparty.games.impostor;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.engine.DeadlineFired;
import com.dinoparty.engine.GameContext;
import com.dinoparty.engine.GameSetup;
import com.dinoparty.engine.PlayerAction;
import com.dinoparty.engine.PlayerInfo;
import com.dinoparty.engine.Transition;
import com.dinoparty.words.TestWords;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The module called directly, without the engine in front of it: the checks that the engine's phase
 * and staleness guards make unreachable in a real session ({@code ALREADY_SUBMITTED} for a second
 * guess and for a stroke after the turn's stroke closed, a phase the game never enters) but that
 * the module still enforces (api-contracts §3.11).
 */
class DinoImpostorModuleTest {

  private static final class ManualClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T12:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final ManualClock clock = new ManualClock();
  private final List<UUID> order = new ArrayList<>();
  private DinoImpostorModule module;
  private DinoImpostorState state;
  private GameContext ctx;

  @BeforeEach
  void init() {
    module = new DinoImpostorModule(TestWords.service(), new SeededRandomSource(9));
    Map<String, Object> settings = Map.of("rounds", 3, "laps", 1);
    for (int i = 0; i < 5; i++) {
      order.add(UUID.randomUUID());
    }
    GameSetup setup = module.prepare(settings, order.getFirst());
    List<PlayerInfo> roster =
        order.stream().map(g -> new PlayerInfo(g, "P-" + g, "dino-rex", "#336699")).toList();
    ctx =
        new GameContext(
            UUID.randomUUID(),
            clock,
            new SeededRandomSource(4),
            roster,
            order,
            settings,
            setup,
            UUID.randomUUID(),
            order.getFirst());
    state = (DinoImpostorState) module.init(ctx).next();
  }

  private Transition phaseEnd() {
    Transition t =
        module.onDeadline(state, new DeadlineFired("phase-end", UUID.randomUUID(), 0), ctx);
    state = (DinoImpostorState) t.next();
    return t;
  }

  private Transition handle(UUID guest, String type, Map<String, Object> payload) {
    Transition t = module.handle(state, new PlayerAction(guest, type, payload), ctx);
    state = (DinoImpostorState) t.next();
    return t;
  }

  private static ErrorCode rejected(Runnable action) {
    try {
      action.run();
    } catch (DomainException e) {
      return e.code();
    }
    throw new AssertionError("expected a rejection");
  }

  private UUID impostor() {
    return state.current.impostor();
  }

  private void toVote() {
    for (int guard = 0; guard < 30 && state.phase != DinoImpostorState.Phase.VOTE; guard++) {
      clock.advance(Duration.ofSeconds(25));
      phaseEnd();
    }
    assertThat(state.phase).isEqualTo(DinoImpostorState.Phase.VOTE);
  }

  private Map<String, Object> batch(UUID stroke, int no, String color, boolean last) {
    Map<String, Object> p = new HashMap<>();
    p.put("strokeId", stroke.toString());
    p.put("batchNo", no);
    p.put("tool", "PENCIL");
    p.put("color", color);
    p.put("size", 2);
    p.put("points", List.of(List.of(10, 10), List.of(20, 20)));
    p.put("final", last);
    return p;
  }

  @Test
  void G4_aStrokeAfterTheTurnsStrokeClosedIsAlreadySubmitted() {
    clock.advance(Duration.ofSeconds(5));
    phaseEnd(); // the first drawing turn
    UUID drawer = state.drawer;
    UUID stroke = UUID.randomUUID();
    String color = state.colors.get(drawer);
    handle(drawer, "IMPOSTOR_DRAW_STROKE", batch(stroke, 0, color, false));
    state.stroke.closed = true;

    assertThat(
            rejected(() -> handle(drawer, "IMPOSTOR_DRAW_STROKE", batch(stroke, 1, color, true))))
        .isEqualTo(ErrorCode.ALREADY_SUBMITTED);
    assertThat(state.canvas.budget()).containsEntry("points", 2);
  }

  @Test
  void DI8_DI9_chatAndVotesBeforeTheVotingPhaseAreInvalidPhase() {
    UUID someone = order.get(1);

    assertThat(rejected(() -> handle(someone, "IMPOSTOR_CHAT", Map.of("text", "hi"))))
        .isEqualTo(ErrorCode.INVALID_PHASE);
    assertThat(
            rejected(
                () ->
                    handle(
                        someone,
                        "IMPOSTOR_VOTE",
                        Map.of("targetGuestId", order.get(2).toString()))))
        .isEqualTo(ErrorCode.INVALID_PHASE);
  }

  @Test
  void DI11_aGuessBeforeTheGuessPhaseIsInvalidPhaseForTheImpostorAndForbiddenForAnArtist() {
    toVote();
    UUID artist = order.stream().filter(g -> !g.equals(impostor())).findFirst().orElseThrow();

    assertThat(rejected(() -> handle(impostor(), "IMPOSTOR_GUESS", Map.of("text", "x"))))
        .isEqualTo(ErrorCode.INVALID_PHASE);
    assertThat(rejected(() -> handle(artist, "IMPOSTOR_GUESS", Map.of("text", "x"))))
        .isEqualTo(ErrorCode.FORBIDDEN);
  }

  @Test
  void DI11_aSecondGuessIsAlreadySubmitted() {
    toVote();
    clock.advance(Duration.ofSeconds(15));
    UUID impostor = impostor();
    UUID other = order.stream().filter(g -> !g.equals(impostor)).findFirst().orElseThrow();
    for (UUID voter : order) {
      UUID target = voter.equals(impostor) ? other : impostor;
      handle(voter, "IMPOSTOR_VOTE", Map.of("targetGuestId", target.toString()));
    }
    assertThat(state.phase).isEqualTo(DinoImpostorState.Phase.GUESS);

    handle(impostor, "IMPOSTOR_GUESS", Map.of("text", "wrong"));
    assertThat(state.phase).isEqualTo(DinoImpostorState.Phase.ROUND_END);

    assertThat(rejected(() -> handle(impostor, "IMPOSTOR_GUESS", Map.of("text", "again"))))
        .isEqualTo(ErrorCode.ALREADY_SUBMITTED);
    assertThat(state.voting.roundEnd()).containsEntry("guess", "wrong");
  }
}
