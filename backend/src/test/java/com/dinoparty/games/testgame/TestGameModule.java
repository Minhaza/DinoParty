package com.dinoparty.games.testgame;

import com.dinoparty.common.ErrorCode;
import com.dinoparty.engine.ActionRejected;
import com.dinoparty.engine.CommandSpec;
import com.dinoparty.engine.DeadlineFired;
import com.dinoparty.engine.FinishReason;
import com.dinoparty.engine.GameContext;
import com.dinoparty.engine.GameDefinition;
import com.dinoparty.engine.GameModule;
import com.dinoparty.engine.GameOutcome;
import com.dinoparty.engine.GameState;
import com.dinoparty.engine.OutboundEvent;
import com.dinoparty.engine.PhaseChange;
import com.dinoparty.engine.PlayerAction;
import com.dinoparty.engine.PlayerStatusChange;
import com.dinoparty.engine.RoundRecord;
import com.dinoparty.engine.ScoreDelta;
import com.dinoparty.engine.Transition;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * TEST-ONLY game used to prove the engine; it lives in test sources, is not a product feature and
 * is never offered to players.
 *
 * <p>A fixed number of rounds. In round r the player at position (r-1) of the play order is the
 * "turn" player and privately learns a secret. Anyone may score points with {@code TEST_SCORE};
 * only the turn player may end the round early with {@code TEST_NEXT}. When the round ends (by time
 * or by {@code TEST_NEXT}) the secret becomes public in {@code TEST_ROUND_ENDED}.
 */
@Component
public class TestGameModule implements GameModule {

  public static final String KEY = "test-game";

  /** Mutable state; only touched inside engine calls. */
  public static final class TestState implements GameState {
    int round;
    UUID turn;
    String secret;
    Instant roundStartedAt;
  }

  private final Duration roundLength;
  private final int rounds;

  public TestGameModule(
      @Value("${test-game.round-millis:60000}") long roundMillis,
      @Value("${test-game.rounds:2}") int rounds) {
    this.roundLength = Duration.ofMillis(roundMillis);
    this.rounds = rounds;
  }

  @Override
  public GameDefinition definition() {
    return new GameDefinition(
        KEY,
        2,
        6,
        2,
        true,
        List.of(
            new CommandSpec("TEST_SCORE", 256, 20, 40, Set.of("ROUND")),
            new CommandSpec("TEST_NEXT", 256, 5, 5, Set.of("ROUND"))),
        Set.of("TEST_SCORED", "TEST_ROUND_ENDED"),
        Set.of("TEST_SECRET"));
  }

  @Override
  public Transition init(GameContext ctx) {
    TestState state = new TestState();
    state.round = 0;
    return startRound(state, ctx);
  }

  @Override
  public Transition handle(GameState gameState, PlayerAction action, GameContext ctx) {
    TestState state = (TestState) gameState;
    return switch (action.type()) {
      case "TEST_SCORE" -> {
        Object points = action.payload().get("points");
        if (!(points instanceof Integer p) || p < 1 || p > 5) {
          throw new ActionRejected(ErrorCode.INVALID_ACTION, "points must be 1 to 5");
        }
        yield Transition.stay(state)
            .withScores(new ScoreDelta(action.guestId(), p, "TEST"))
            .withEvents(
                OutboundEvent.publicEvent(
                    "TEST_SCORED", Map.of("guestId", action.guestId(), "points", p)));
      }
      case "TEST_NEXT" -> {
        if (!action.guestId().equals(state.turn)) {
          throw new ActionRejected(ErrorCode.FORBIDDEN, "Only the turn player can do that.");
        }
        yield endRound(state, ctx, "NEXT");
      }
      default -> throw new ActionRejected(ErrorCode.INVALID_ACTION, "Unknown action.");
    };
  }

  @Override
  public Transition onDeadline(GameState gameState, DeadlineFired deadline, GameContext ctx) {
    TestState state = (TestState) gameState;
    return "phase-end".equals(deadline.id())
        ? endRound(state, ctx, "TIME")
        : Transition.stay(state);
  }

  @Override
  public Transition onPlayerStatus(
      GameState gameState, PlayerStatusChange change, GameContext ctx) {
    return Transition.stay(gameState);
  }

  @Override
  public Map<String, Object> view(GameState gameState, UUID viewer, GameContext ctx) {
    TestState state = (TestState) gameState;
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("round", state.round);
    view.put("turnGuestId", state.turn);
    if (viewer.equals(state.turn)) {
      view.put("secret", state.secret);
    }
    return view;
  }

  @Override
  public GameOutcome outcome(GameState gameState, FinishReason reason, GameContext ctx) {
    TestState state = (TestState) gameState;
    Map<UUID, Map<String, Object>> detail = new LinkedHashMap<>();
    ctx.order().forEach(g -> detail.put(g, Map.of("rounds", state.round)));
    return new GameOutcome(detail);
  }

  private Transition endRound(TestState state, GameContext ctx, String reason) {
    Instant now = ctx.clock().instant();
    OutboundEvent ended =
        OutboundEvent.publicEvent(
            "TEST_ROUND_ENDED",
            Map.of("round", state.round, "secret", state.secret, "reason", reason));
    RoundRecord record =
        new RoundRecord(
            state.round,
            state.roundStartedAt,
            now,
            reason,
            Map.of(),
            Map.of("secret", state.secret));
    if (state.round >= rounds) {
      return Transition.stay(state).withClosing(ended).withRounds(record).finish();
    }
    return startRound(state, ctx).withClosing(ended).withRounds(record);
  }

  private Transition startRound(TestState state, GameContext ctx) {
    state.round++;
    state.turn = ctx.order().get((state.round - 1) % ctx.order().size());
    state.secret = "secret-" + ctx.random().nextInt(1_000_000);
    state.roundStartedAt = ctx.clock().instant();
    return Transition.stay(state)
        .withPhase(
            new PhaseChange(
                "ROUND",
                state.roundStartedAt.plus(roundLength),
                Map.of("round", state.round, "turnGuestId", state.turn)))
        .withEvents(
            OutboundEvent.privateEvent(
                state.turn, "TEST_SECRET", Map.of("round", state.round, "secret", state.secret)));
  }
}
