package com.dinoparty.games.testgame;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.engine.ActionRejected;
import com.dinoparty.engine.CommandSpec;
import com.dinoparty.engine.DeadlineFired;
import com.dinoparty.engine.FinishReason;
import com.dinoparty.engine.GameContext;
import com.dinoparty.engine.GameDefinition;
import com.dinoparty.engine.GameMetadata;
import com.dinoparty.engine.GameModule;
import com.dinoparty.engine.GameOutcome;
import com.dinoparty.engine.GameSetup;
import com.dinoparty.engine.GameState;
import com.dinoparty.engine.OutboundEvent;
import com.dinoparty.engine.PhaseChange;
import com.dinoparty.engine.PlayerAction;
import com.dinoparty.engine.PlayerStatusChange;
import com.dinoparty.engine.RoundRecord;
import com.dinoparty.engine.ScoreDelta;
import com.dinoparty.engine.SettingsSchema;
import com.dinoparty.engine.SubmissionRecord;
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
 * only the turn player may end the round early with {@code TEST_NEXT}. {@code TEST_HOST_ONLY} is
 * accepted from the current host only and {@code TEST_SUBMIT} hands one submission record to the
 * recorder (optionally finishing the game in the same transition). When the round ends (by time or
 * by {@code TEST_NEXT}) the secret becomes public in {@code TEST_ROUND_ENDED}.
 *
 * <p>Start preparation: setting {@code secretPrefix} (default {@code secret}) is frozen into the
 * setup and prefixes every secret; {@code rejectStart: true} rejects the start, {@code crashStart:
 * true} makes {@link #prepare} fail unexpectedly.
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

  /** Frozen at start; every secret of the session uses this prefix. */
  public record TestSetup(String secretPrefix) implements GameSetup {}

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
            new CommandSpec("TEST_NEXT", 256, 5, 5, Set.of("ROUND")),
            new CommandSpec("TEST_HOST_ONLY", 256, 5, 5, Set.of("ROUND")),
            new CommandSpec("TEST_SUBMIT", 256, 5, 5, Set.of("ROUND"))),
        Set.of("TEST_SCORED", "TEST_ROUND_ENDED"),
        Set.of("TEST_SECRET"),
        new GameMetadata(
            "Test Game",
            "Engine test fixture",
            "A test-only game that exercises the engine.",
            List.of("Score points until the last round ends."),
            KEY),
        SettingsSchema.EMPTY);
  }

  @Override
  public GameSetup prepare(Map<String, Object> settings, UUID host) {
    if (Boolean.TRUE.equals(settings.get("rejectStart"))) {
      throw DomainException.invalid("settings.rejectStart", "rejects the start");
    }
    if (Boolean.TRUE.equals(settings.get("crashStart"))) {
      throw new IllegalStateException("prepare failed");
    }
    return new TestSetup(settings.get("secretPrefix") instanceof String prefix ? prefix : "secret");
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
      case "TEST_HOST_ONLY" -> {
        if (!action.guestId().equals(ctx.host())) {
          throw new ActionRejected(ErrorCode.NOT_HOST, "Only the host can do that.");
        }
        yield Transition.stay(state);
      }
      case "TEST_SUBMIT" -> {
        SubmissionRecord record =
            new SubmissionRecord(
                0,
                state.round,
                action.guestId(),
                SubmissionRecord.Kind.TEXT,
                String.valueOf(action.payload().get("text")),
                null,
                "NONE",
                false,
                false,
                rounds,
                ctx.clock().instant());
        Transition t = Transition.stay(state).withSubmissions(record);
        yield Boolean.TRUE.equals(action.payload().get("finish")) ? t.finish() : t;
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
    state.secret = ((TestSetup) ctx.setup()).secretPrefix() + "-" + ctx.random().nextInt(1_000_000);
    state.roundStartedAt = ctx.clock().instant();
    return Transition.stay(state)
        .withPhase(
            new PhaseChange(
                "ROUND",
                state.roundStartedAt.plus(roundLength),
                Map.of("round", state.round, "turnGuestId", state.turn)))
        .withEvents(
            OutboundEvent.privateEvent(
                state.turn,
                "TEST_SECRET",
                Map.of(
                    "round", state.round, "secret", state.secret, "phaseId", ctx.nextPhaseId())));
  }
}
