package com.dinoparty.games.impostor;

import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.RandomSource;
import com.dinoparty.drawing.CanvasBuffer;
import com.dinoparty.drawing.CanvasLimits;
import com.dinoparty.drawing.StrokeBatch;
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
import com.dinoparty.games.impostor.ImpostorWords.WithDecoys;
import com.dinoparty.games.impostor.SketchySaurusState.Phase;
import com.dinoparty.words.WordCatalogTypes.CategorizedWord;
import com.dinoparty.words.WordPoolService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Sketchy Saurus ({@code sketchy-saurus}): every round one player, the impostor, does not know the
 * secret word; everyone draws at the same time on their own hidden canvas, then votes over a
 * gallery of all the drawings. Rules: docs/game-rules.md [SS-1]..[SS-3] and the shared
 * [DI-1]..[DI-14]; wire format: api-contracts.md §3.12; plan: docs/dino-impostor-plan.md.
 *
 * <p>The sketching ([SS-2]) is this class; everything after it (chat, votes, the guess, the reveal,
 * scoring, an impostor who leaves) is {@link ImpostorVoting}, shared with Dino Impostor, which this
 * class feeds with the {@link ImpostorRules} of its game.
 *
 * <p>Every rejection is thrown before any state changes. A draft never leaves this class except in
 * its author's own {@link #view}; the word, the decoy and the impostor only leave it in each
 * player's own private {@code IMPOSTOR_ROLE} and their own view ({@link ImpostorRound}, {@link
 * SketchySaurusViews}).
 */
@Component
public class SketchySaurusModule implements GameModule {

  public static final String KEY = "sketchy-saurus";

  static final String ROLE_REVEAL = Phase.ROLE_REVEAL.name();
  static final String SKETCH = Phase.SKETCH.name();

  static final String PHASE_END = "phase-end";

  static final String DRAW_STROKE = "SKETCHY_DRAW_STROKE";
  static final String DRAW_UNDO = "SKETCHY_DRAW_UNDO";
  static final String DRAW_CLEAR = "SKETCHY_DRAW_CLEAR";
  static final String SUBMIT = "SKETCHY_SUBMIT";
  static final String PROGRESS = "SKETCHY_PROGRESS";
  static final String GALLERY_OPENED = "SKETCHY_GALLERY_OPENED";

  static final Duration ROLE_REVEAL_TIME = Duration.ofSeconds(5);

  /**
   * The budget of one drawing (D64). 1 000 live points and 60 strokes: a full drawing serializes to
   * about 121 bytes per stroke plus 14 per point, so ten of them plus a full chat stay under the
   * snapshot bound of 256 KiB (R36). The batch and stroke limits are the shared [DG-14] ones.
   */
  static final CanvasLimits DRAFT_LIMITS = new CanvasLimits(200, 1_000, 60, 1_000);

  private static final Set<String> SKETCH_PHASES = Set.of(SKETCH);

  private static final GameDefinition DEFINITION =
      new GameDefinition(
          KEY,
          4,
          10,
          3,
          true,
          Stream.concat(
                  ImpostorFamily.COMMANDS.stream(),
                  Stream.of(
                      new CommandSpec(DRAW_STROKE, 8 * 1024, 30, 40, SKETCH_PHASES),
                      new CommandSpec(DRAW_UNDO, 128, 5, 5, SKETCH_PHASES),
                      new CommandSpec(DRAW_CLEAR, 128, 2, 3, SKETCH_PHASES),
                      new CommandSpec(SUBMIT, 128, 2, 3, SKETCH_PHASES)))
              .toList(),
          Stream.concat(ImpostorFamily.PUBLIC_EVENTS.stream(), Stream.of(PROGRESS, GALLERY_OPENED))
              .collect(Collectors.toUnmodifiableSet()),
          ImpostorFamily.PRIVATE_EVENTS,
          new GameMetadata(
              "Sketchy Saurus",
              "Everyone sketches, one is bluffing",
              "Everybody draws the secret word at the same time on a hidden page, except one dino"
                  + " who has to fake it. Compare the whole gallery, then vote out the bluffer.",
              List.of(
                  "Everyone sees the category; all but the impostor also see the secret word.",
                  "Sketch your own picture at the same time. Nobody sees it until the gallery.",
                  "Look over every drawing, talk it over and vote for the dino you think bluffed.",
                  "A caught impostor who knew their role gets one guess at the word."),
              KEY),
          SketchySaurusSettings.SCHEMA);

  private final WordPoolService words;

  /**
   * Draws the words, the decoys and the impostors. The server's cryptographically strong source,
   * not the session's seeded {@code ctx.random()}: the session seed is stored with the session, and
   * the words and roles must not be reconstructible from it (D52, D60, D63).
   */
  private final RandomSource secretRandom;

  public SketchySaurusModule(WordPoolService words, RandomSource secretRandom) {
    this.words = words;
    this.secretRandom = secretRandom;
  }

  @Override
  public GameDefinition definition() {
    return DEFINITION;
  }

  // ------------------------------------------------------------------ engine calls

  /**
   * [DI-2]/[SS-3]: freezes one distinct word per round, and with {@code impostorKnows} off a decoy
   * for each, when the host starts, or rejects the start with {@code WORD_POOL_TOO_SMALL}. The
   * roster is not frozen yet, so automatic rounds draw for the most rounds they can reach.
   */
  @Override
  public GameSetup prepare(Map<String, Object> settings, UUID host) {
    SketchySaurusSettings s = SketchySaurusSettings.from(settings);
    ImpostorWords pool = ImpostorWords.load(words, s.difficulty());
    if (s.impostorKnows()) {
      return new ImpostorSetup(pool.draw(s.wordsToDraw(), secretRandom));
    }
    WithDecoys drawn = pool.drawWithDecoys(s.wordsToDraw(), secretRandom);
    return new ImpostorSetup(drawn.words(), drawn.decoys());
  }

  @Override
  public Transition init(GameContext ctx) {
    if (!(ctx.setup() instanceof ImpostorSetup setup)) {
      throw new IllegalStateException("Sketchy Saurus started without prepared words");
    }
    SketchySaurusSettings settings = SketchySaurusSettings.from(ctx.settings());
    int rounds = settings.rounds(ctx.order().size());
    if (setup.words().size() < rounds
        || (!settings.impostorKnows() && setup.decoys().size() < rounds)) {
      throw new IllegalStateException("Fewer prepared words than rounds");
    }
    Map<UUID, String> nicknames = new java.util.LinkedHashMap<>();
    ctx.roster().forEach(p -> nicknames.put(p.guestId(), p.nickname()));
    // [SS-3]: a caught impostor guesses if they knew their role or unawareGuess is on.
    ImpostorRules rules =
        new ImpostorRules(
            ImpostorFamily.GALLERY_PHASE,
            settings.voteSeconds(),
            settings.guessSeconds(),
            settings::impostorMayGuess);
    SketchySaurusState s =
        new SketchySaurusState(
            settings, ctx.order(), setup.words(), setup.decoys(), nicknames, rules, rounds);
    return startRound(s, ctx);
  }

  @Override
  public Transition handle(GameState state, PlayerAction action, GameContext ctx) {
    SketchySaurusState s = (SketchySaurusState) state;
    return switch (action.type()) {
      case DRAW_STROKE -> drawStroke(s, action);
      case DRAW_UNDO -> undo(s, action);
      case DRAW_CLEAR -> clear(s, action);
      case SUBMIT -> submit(s, action, ctx);
      case ImpostorFamily.CHAT -> s.voting.chat(s, action, ctx);
      case ImpostorFamily.VOTE -> track(s, s.voting.vote(s, action, ctx));
      case ImpostorFamily.GUESS -> track(s, s.voting.guess(s, action, ctx));
      default -> throw new ActionRejected(ErrorCode.INVALID_ACTION, "Unknown action.");
    };
  }

  @Override
  public Transition onDeadline(GameState state, DeadlineFired deadline, GameContext ctx) {
    SketchySaurusState s = (SketchySaurusState) state;
    if (ImpostorFamily.VOTE_MINIMUM_DEADLINE.equals(deadline.id())) {
      return track(s, s.voting.onVoteMinimum(s, ctx));
    }
    if (!PHASE_END.equals(deadline.id())) {
      return Transition.stay(s);
    }
    return switch (s.phase) {
      case ROLE_REVEAL -> startSketch(s, ctx);
      case SKETCH -> openGallery(s, ctx, List.of()); // [SS-2] drafts become the drawings
      case GALLERY, GUESS -> track(s, s.voting.onPhaseEnd(s, ctx));
      case ROUND_END -> nextRoundOrFinish(s, ctx);
    };
  }

  @Override
  public Transition onPlayerStatus(GameState state, PlayerStatusChange change, GameContext ctx) {
    SketchySaurusState s = (SketchySaurusState) state;
    // [SS-2]/[DI-13]: DISCONNECTED changes nothing; the draft is kept and restored from the view.
    if (change.status() != PlayerStatus.LEFT || !s.left.add(change.guestId())) {
      return Transition.stay(s);
    }
    UUID guest = change.guestId();
    // [DI-13]: the impostor ends the round; anyone else loses their votes and may close the vote.
    Transition left = s.voting.onLeft(s, guest, ctx);
    if (left.phase().isPresent()) {
      return track(s, left);
    }
    if (s.phase != Phase.SKETCH) {
      return left;
    }
    // [SS-2]: no drawing for a player who left, and the early end is checked again.
    s.drafts.remove(guest);
    s.submitted.remove(guest);
    if (s.order.stream().filter(g -> !s.isLeft(g)).count() < DEFINITION.minActivePlayers()) {
      return Transition.stay(s); // the engine ends the game ([C-1]); there is nothing to show
    }
    if (s.allSubmitted()) {
      return openGallery(s, ctx, List.of(progress(s)));
    }
    return Transition.stay(s).withEvents(progress(s));
  }

  @Override
  public Map<String, Object> view(GameState state, UUID viewer, GameContext ctx) {
    return SketchySaurusViews.snapshot((SketchySaurusState) state, viewer);
  }

  /** Standings detail of §3.11. */
  @Override
  public GameOutcome outcome(GameState state, FinishReason reason, GameContext ctx) {
    SketchySaurusState s = (SketchySaurusState) state;
    return new GameOutcome(s.scoreboard.detail(s.order));
  }

  // ------------------------------------------------------------------ commands

  /** [SS-2] in the order of api-contracts §3.12; the draft is unchanged by every rejection. */
  private Transition drawStroke(SketchySaurusState s, PlayerAction action) {
    CanvasBuffer draft = openDraft(s, action);
    StrokeBatch batch = StrokeBatch.parse(action.payload(), draft.limits());
    draft.append(batch); // validates order, style and budgets before it mutates; not published
    return Transition.stay(s);
  }

  private Transition undo(SketchySaurusState s, PlayerAction action) {
    openDraft(s, action).undo();
    return Transition.stay(s);
  }

  private Transition clear(SketchySaurusState s, PlayerAction action) {
    openDraft(s, action).clear();
    return Transition.stay(s);
  }

  /** [SS-2]: the draft as it is becomes final; the last submission ends the phase. */
  private Transition submit(SketchySaurusState s, PlayerAction action, GameContext ctx) {
    openDraft(s, action);
    s.submitted.add(action.guestId());
    if (s.allSubmitted()) {
      return openGallery(s, ctx, List.of(progress(s)));
    }
    return Transition.stay(s).withEvents(progress(s));
  }

  /** The sender's own draft while it can still change: {@code SKETCH}, not submitted. */
  private static CanvasBuffer openDraft(SketchySaurusState s, PlayerAction action) {
    CanvasBuffer draft = s.phase == Phase.SKETCH ? s.drafts.get(action.guestId()) : null;
    if (draft == null) {
      throw new ActionRejected(ErrorCode.INVALID_PHASE, "That action is not allowed now.");
    }
    if (s.submitted.contains(action.guestId())) {
      throw new ActionRejected(ErrorCode.ALREADY_SUBMITTED, "You have already submitted.");
    }
    return draft;
  }

  // ------------------------------------------------------------------ transitions

  /**
   * [DI-3]/[DI-4]/[SS-3]: picks the impostor and tells every seated player their role in this one
   * transition, after its {@code PHASE_STARTED}. With {@code impostorKnows} off the impostor's role
   * carries the round's decoy and has the shape of an artist's.
   */
  private Transition startRound(SketchySaurusState s, GameContext ctx) {
    List<UUID> seated = s.order.stream().filter(g -> !s.isLeft(g)).toList();
    UUID impostor = ImpostorRotation.pick(seated, s.impostorCounts, secretRandom);
    s.impostorCounts.merge(impostor, 1, Integer::sum);
    s.round++;
    CategorizedWord word = s.words.get(s.round - 1);
    CategorizedWord decoy = s.settings.impostorKnows() ? null : s.decoys.get(s.round - 1);
    s.current = new ImpostorRound(s.round, impostor, word, decoy);
    s.seated = seated;
    s.voting =
        new ImpostorVoting(
            s.rules,
            s.current,
            seated,
            s.totalRounds,
            ctx.clock().instant(),
            decoy == null ? null : decoy.text(),
            s.scoreboard);
    s.drafts.clear();
    s.submitted.clear();
    s.gallery = null;
    s.phase = Phase.ROLE_REVEAL;
    Instant deadline = ctx.clock().instant().plus(ROLE_REVEAL_TIME);
    return Transition.stay(s)
        .withPhase(new PhaseChange(ROLE_REVEAL, deadline, SketchySaurusViews.roleRevealData(s)))
        .withEvents(s.current.roleEvents(seated, ctx.nextPhaseId()).toArray(OutboundEvent[]::new));
  }

  /** [SS-2]: everyone seated gets an empty private draft and the sketching time starts. */
  private Transition startSketch(SketchySaurusState s, GameContext ctx) {
    s.drafts.clear();
    s.submitted.clear();
    for (UUID guest : s.seated) {
      if (!s.isLeft(guest)) {
        s.drafts.put(guest, new CanvasBuffer(DRAFT_LIMITS));
      }
    }
    s.sketchPhaseId = ctx.nextPhaseId();
    s.phase = Phase.SKETCH;
    Instant deadline = ctx.clock().instant().plusSeconds(s.settings.sketchSeconds());
    return Transition.stay(s)
        .withPhase(new PhaseChange(SKETCH, deadline, SketchySaurusViews.roundData(s)))
        .withEvents(OutboundEvent.publicEvent(PROGRESS, SketchySaurusViews.progress(s)));
  }

  /**
   * [SS-2]/[SS-1]: ends the sketching, by the deadline or because everyone submitted, and opens the
   * gallery on the shared voting code. The drawings are the drafts as they stand. {@code closing}
   * are the events of the sketching that ends (the last progress).
   */
  private Transition openGallery(
      SketchySaurusState s, GameContext ctx, List<OutboundEvent> closing) {
    s.gallery = SketchySaurusViews.buildGallery(s);
    s.drafts.clear();
    s.submitted.clear();
    Transition open = s.voting.open(s, ctx, SketchySaurusViews.roundData(s));
    return track(
        s,
        open.withClosing(closing.toArray(OutboundEvent[]::new))
            .withEvents(
                OutboundEvent.publicEvent(GALLERY_OPENED, SketchySaurusViews.galleryOpened(s))));
  }

  private OutboundEvent progress(SketchySaurusState s) {
    return OutboundEvent.publicEvent(PROGRESS, SketchySaurusViews.progress(s));
  }

  /** Keeps the state's phase in step with the phase a shared-voting transition starts. */
  private static Transition track(SketchySaurusState s, Transition t) {
    t.phase()
        .ifPresent(
            change -> {
              s.phase = Phase.valueOf(change.name());
              if (s.phase != Phase.SKETCH) {
                s.drafts.clear(); // an impostor who left ended the round: nothing is shown
              }
            });
    return t;
  }

  private Transition nextRoundOrFinish(SketchySaurusState s, GameContext ctx) {
    if (s.round >= s.totalRounds) {
      return Transition.stay(s).finish();
    }
    return startRound(s, ctx);
  }
}
