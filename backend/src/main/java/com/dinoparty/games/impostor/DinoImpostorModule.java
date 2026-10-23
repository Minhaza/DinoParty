package com.dinoparty.games.impostor;

import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.RandomSource;
import com.dinoparty.drawing.CanvasBuffer;
import com.dinoparty.drawing.StrokeBatch;
import com.dinoparty.drawing.Tool;
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
import com.dinoparty.engine.PlayerStatus;
import com.dinoparty.engine.PlayerStatusChange;
import com.dinoparty.engine.Transition;
import com.dinoparty.games.impostor.DinoImpostorState.Phase;
import com.dinoparty.games.impostor.DinoImpostorState.TurnStroke;
import com.dinoparty.words.WordCatalogTypes.CategorizedWord;
import com.dinoparty.words.WordPoolService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Dino Impostor ({@code dino-impostor}): every round one player, the impostor, does not know the
 * secret word; everyone adds one stroke at a time to a shared canvas, then votes. Rules:
 * docs/game-rules.md [DI-1]..[DI-14]; wire format: api-contracts.md §3.11; plan:
 * docs/dino-impostor-plan.md.
 *
 * <p>The drawing turns are this class ([DI-5]..[DI-7]); everything after them (chat, votes, the
 * guess, the reveal, scoring, an impostor who leaves) is {@link ImpostorVoting}, shared with
 * Sketchy Saurus, which this class feeds with the {@link ImpostorRules} of its game.
 *
 * <p>Every rejection is thrown before any state changes. The word and the impostor only leave this
 * class in each player's own private {@code IMPOSTOR_ROLE} and their own {@link #view} ({@link
 * ImpostorRound}, {@link DinoImpostorViews}).
 */
@Component
public class DinoImpostorModule implements GameModule {

  public static final String KEY = "dino-impostor";

  static final String ROLE_REVEAL = Phase.ROLE_REVEAL.name();
  static final String DRAWING = Phase.DRAWING.name();
  static final String VOTE = Phase.VOTE.name();

  static final String PHASE_END = "phase-end";

  static final String DRAW_STROKE = "IMPOSTOR_DRAW_STROKE";
  static final String DRAWING_UPDATED = "IMPOSTOR_DRAWING_UPDATED";

  static final Duration ROLE_REVEAL_TIME = Duration.ofSeconds(5);

  /**
   * Session colours, one per roster member (D56), in session order. Ten colours that stay apart for
   * the common colour-vision deficiencies: the Okabe-Ito palette (M. Okabe and K. Ito, "Color
   * Universal Design", 2008) without its yellow, which is too faint on the white canvas, plus
   * indigo, wine and olive from Paul Tol's colour-blind safe "muted" scheme
   * (personal.sron.nl/~pault). Colour is never the only cue: the client adds initials and a legend.
   */
  static final List<String> PALETTE =
      List.of(
          "#000000", "#E69F00", "#56B4E9", "#009E73", "#0072B2", "#D55E00", "#CC79A7", "#332288",
          "#882255", "#999933");

  private static final GameDefinition DEFINITION =
      new GameDefinition(
          KEY,
          4,
          10,
          3,
          true,
          Stream.concat(
                  ImpostorFamily.COMMANDS.stream(),
                  Stream.of(new CommandSpec(DRAW_STROKE, 8 * 1024, 30, 40, Set.of(DRAWING))))
              .toList(),
          Stream.concat(ImpostorFamily.PUBLIC_EVENTS.stream(), Stream.of(DRAWING_UPDATED))
              .collect(Collectors.toUnmodifiableSet()),
          ImpostorFamily.PRIVATE_EVENTS,
          new GameMetadata(
              "Dino Impostor",
              "Bluff one stroke at a time",
              "One dino in the herd doesn't know the secret word. Take turns adding a single"
                  + " stroke to a shared doodle, then vote out the dino who was only pretending.",
              List.of(
                  "Everyone sees the category; all but the impostor also see the secret word.",
                  "Take turns adding one stroke each to the shared drawing, in your own colour.",
                  "Talk it over and vote for the dino you think was bluffing.",
                  "A caught impostor gets one guess at the word to steal the round."),
              KEY),
          DinoImpostorSettings.SCHEMA);

  private final WordPoolService words;

  /**
   * Draws the words and the impostors. The server's cryptographically strong source, not the
   * session's seeded {@code ctx.random()}: the session seed is stored with the session, and the
   * words and roles must not be reconstructible from it (D52, D60).
   */
  private final RandomSource secretRandom;

  public DinoImpostorModule(WordPoolService words, RandomSource secretRandom) {
    this.words = words;
    this.secretRandom = secretRandom;
  }

  @Override
  public GameDefinition definition() {
    return DEFINITION;
  }

  // ------------------------------------------------------------------ engine calls

  /**
   * [DI-2]: freezes one distinct word per round when the host starts, or rejects the start with
   * {@code WORD_POOL_TOO_SMALL}. The roster is not frozen yet, so automatic rounds draw for the
   * most rounds they can reach.
   */
  @Override
  public GameSetup prepare(Map<String, Object> settings, UUID host) {
    DinoImpostorSettings s = DinoImpostorSettings.from(settings);
    return new ImpostorSetup(
        ImpostorWords.load(words, s.difficulty()).draw(s.wordsToDraw(), secretRandom));
  }

  @Override
  public Transition init(GameContext ctx) {
    if (!(ctx.setup() instanceof ImpostorSetup setup)) {
      throw new IllegalStateException("Dino Impostor started without prepared words");
    }
    DinoImpostorSettings settings = DinoImpostorSettings.from(ctx.settings());
    int rounds = settings.rounds(ctx.order().size());
    if (setup.words().size() < rounds) {
      throw new IllegalStateException("Fewer prepared words than rounds");
    }
    Map<UUID, String> colors = new LinkedHashMap<>();
    for (int i = 0; i < ctx.order().size(); i++) {
      colors.put(ctx.order().get(i), PALETTE.get(i));
    }
    Map<UUID, String> nicknames = new LinkedHashMap<>();
    ctx.roster().forEach(p -> nicknames.put(p.guestId(), p.nickname()));
    // [DI-11]: a caught impostor always gets a guess here; Sketchy Saurus decides by [SS-3].
    ImpostorRules rules =
        new ImpostorRules(
            ImpostorFamily.VOTE_PHASE, settings.voteSeconds(), settings.guessSeconds(), () -> true);
    DinoImpostorState s =
        new DinoImpostorState(
            settings, ctx.order(), colors, setup.words(), nicknames, rules, rounds);
    return startRound(s, ctx);
  }

  @Override
  public Transition handle(GameState state, PlayerAction action, GameContext ctx) {
    DinoImpostorState s = (DinoImpostorState) state;
    return switch (action.type()) {
      case DRAW_STROKE -> drawStroke(s, action, ctx);
      case ImpostorFamily.CHAT -> s.voting.chat(s, action, ctx);
      case ImpostorFamily.VOTE -> track(s, s.voting.vote(s, action, ctx));
      case ImpostorFamily.GUESS -> track(s, s.voting.guess(s, action, ctx));
      default -> throw new ActionRejected(ErrorCode.INVALID_ACTION, "Unknown action.");
    };
  }

  @Override
  public Transition onDeadline(GameState state, DeadlineFired deadline, GameContext ctx) {
    DinoImpostorState s = (DinoImpostorState) state;
    if (ImpostorFamily.VOTE_MINIMUM_DEADLINE.equals(deadline.id())) {
      return track(s, s.voting.onVoteMinimum(s, ctx));
    }
    if (!PHASE_END.equals(deadline.id())) {
      return Transition.stay(s);
    }
    return switch (s.phase) {
      case ROLE_REVEAL, DRAWING -> nextTurn(s, ctx); // [DI-7] the deadline ends the turn
      case VOTE, GUESS -> track(s, s.voting.onPhaseEnd(s, ctx));
      case ROUND_END -> nextRoundOrFinish(s, ctx);
    };
  }

  @Override
  public Transition onPlayerStatus(GameState state, PlayerStatusChange change, GameContext ctx) {
    DinoImpostorState s = (DinoImpostorState) state;
    // [DI-7]/[DI-13]: DISCONNECTED changes nothing; a disconnected drawer keeps the turn.
    if (change.status() != PlayerStatus.LEFT || !s.left.add(change.guestId())) {
      return Transition.stay(s);
    }
    UUID guest = change.guestId();
    if ((s.phase == Phase.ROLE_REVEAL || s.phase == Phase.DRAWING) && s.current.isImpostor(guest)) {
      closeOpenStroke(s);
    }
    // [DI-13]: the impostor ends the round; anyone else loses their votes and may close the vote.
    Transition left = s.voting.onLeft(s, guest, ctx);
    if (left.phase().isPresent()) {
      return track(s, left);
    }
    if (s.phase == Phase.DRAWING && guest.equals(s.drawer)) {
      return nextTurn(s, ctx); // their stroke so far stays; the next turn starts at once
    }
    return left; // later turns of their seat are skipped
  }

  @Override
  public Map<String, Object> view(GameState state, UUID viewer, GameContext ctx) {
    return DinoImpostorViews.snapshot((DinoImpostorState) state, viewer);
  }

  /** Standings detail of §3.11. */
  @Override
  public GameOutcome outcome(GameState state, FinishReason reason, GameContext ctx) {
    DinoImpostorState s = (DinoImpostorState) state;
    return new GameOutcome(s.scoreboard.detail(s.order));
  }

  // ------------------------------------------------------------------ commands

  /** [DI-6] in the order of api-contracts §3.11; the canvas is unchanged by every rejection. */
  private Transition drawStroke(DinoImpostorState s, PlayerAction action, GameContext ctx) {
    if (s.phase != Phase.DRAWING) {
      throw new ActionRejected(ErrorCode.INVALID_PHASE, "That action is not allowed now.");
    }
    UUID drawer = action.guestId();
    if (!drawer.equals(s.drawer)) {
      throw new ActionRejected(ErrorCode.NOT_DRAWER, "It is not your turn to draw.");
    }
    StrokeBatch batch = StrokeBatch.parse(action.payload(), s.canvas.limits());
    if (batch.tool() != Tool.PENCIL) {
      throw invalid("payload.tool", "must be PENCIL");
    }
    if (!batch.color().equalsIgnoreCase(s.colors.get(drawer))) {
      throw invalid("payload.color", "must be your own colour");
    }
    if (s.stroke != null && (s.stroke.closed || !s.stroke.id.equals(batch.strokeId()))) {
      throw new ActionRejected(ErrorCode.ALREADY_SUBMITTED, "You already drew your stroke.");
    }
    s.canvas.append(batch); // validates order, style and budgets before it mutates
    if (s.stroke == null) {
      s.stroke = new TurnStroke(batch.strokeId(), batch.color(), batch.size());
      s.strokeAuthors.put(batch.strokeId(), drawer);
    } else {
      s.stroke.nextBatch++;
    }
    OutboundEvent event =
        OutboundEvent.publicEvent(DRAWING_UPDATED, DinoImpostorViews.strokeEvent(batch, drawer));
    if (batch.finalBatch()) {
      s.stroke.closed = true;
      return nextTurn(s, ctx).withClosing(event); // [DI-7] the final batch ends the turn
    }
    return Transition.stay(s).withEvents(event);
  }

  // ------------------------------------------------------------------ transitions

  /**
   * [DI-3]..[DI-5]: picks the impostor, seats everyone and tells every seated player their role in
   * this one transition, after its {@code PHASE_STARTED}.
   */
  private Transition startRound(DinoImpostorState s, GameContext ctx) {
    List<UUID> seated = s.order.stream().filter(g -> !s.isLeft(g)).toList();
    UUID impostor = ImpostorRotation.pick(seated, s.impostorCounts, secretRandom);
    s.impostorCounts.merge(impostor, 1, Integer::sum);
    s.round++;
    CategorizedWord word = s.words.get(s.round - 1);
    s.current = new ImpostorRound(s.round, impostor, word);
    s.seats = ImpostorRotation.seatOrder(seated, impostor, ctx.random());
    s.voting =
        new ImpostorVoting(
            s.rules, s.current, s.seats, s.totalRounds, ctx.clock().instant(), null, s.scoreboard);
    s.totalTurns = s.settings.laps() * s.seats.size();
    s.turn = -1;
    s.drawer = null;
    s.stroke = null;
    s.canvas = new CanvasBuffer(s.canvas.limits());
    s.strokeAuthors.clear();
    s.phase = Phase.ROLE_REVEAL;
    Instant deadline = ctx.clock().instant().plus(ROLE_REVEAL_TIME);
    return Transition.stay(s)
        .withPhase(new PhaseChange(ROLE_REVEAL, deadline, DinoImpostorViews.roleRevealData(s)))
        .withEvents(s.current.roleEvents(s.seats, ctx.nextPhaseId()).toArray(OutboundEvent[]::new));
  }

  /**
   * [DI-6]/[DI-7]: closes the turn that ends and starts the next one whose drawer has not left, or
   * the vote after the last turn.
   */
  private Transition nextTurn(DinoImpostorState s, GameContext ctx) {
    closeOpenStroke(s);
    s.stroke = null;
    int t = s.turn + 1;
    while (t < s.totalTurns && s.isLeft(s.seatOf(t))) {
      t++;
    }
    Instant now = ctx.clock().instant();
    if (t >= s.totalTurns) {
      s.turn = s.totalTurns;
      s.drawer = null;
      return track(s, s.voting.open(s, ctx, DinoImpostorViews.roundData(s)));
    }
    s.turn = t;
    s.drawer = s.seatOf(t);
    s.phase = Phase.DRAWING;
    return Transition.stay(s)
        .withPhase(
            new PhaseChange(
                DRAWING, now.plusSeconds(s.settings.turnSeconds()), DinoImpostorViews.turnData(s)));
  }

  /** Keeps the state's phase in step with the phase a shared-voting transition starts. */
  private static Transition track(DinoImpostorState s, Transition t) {
    t.phase().ifPresent(change -> s.phase = Phase.valueOf(change.name()));
    return t;
  }

  private Transition nextRoundOrFinish(DinoImpostorState s, GameContext ctx) {
    if (s.round >= s.totalRounds) {
      return Transition.stay(s).finish();
    }
    return startRound(s, ctx);
  }

  /**
   * [DI-7]: an open stroke is closed as it stands by an empty final batch, which always fits the
   * budget. No event: clients close it on the next {@code PHASE_STARTED} (api-contracts §3.11).
   */
  private static void closeOpenStroke(DinoImpostorState s) {
    TurnStroke open = s.stroke;
    if (open == null || open.closed) {
      return;
    }
    s.canvas.append(
        new StrokeBatch(
            open.id, open.nextBatch, Tool.PENCIL, open.color, open.size, new int[0], true));
    open.closed = true;
  }

  private static ActionRejected invalid(String field, String message) {
    return new ActionRejected(ErrorCode.VALIDATION_FAILED, field + " " + message);
  }
}
