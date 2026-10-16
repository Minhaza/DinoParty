package com.dinoparty.games.telephone;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.Timestamps;
import com.dinoparty.drawing.StrokeBatch;
import com.dinoparty.engine.ActionRejected;
import com.dinoparty.engine.CommandSpec;
import com.dinoparty.engine.DeadlineFired;
import com.dinoparty.engine.DeadlineRequest;
import com.dinoparty.engine.FinishReason;
import com.dinoparty.engine.GameContext;
import com.dinoparty.engine.GameDefinition;
import com.dinoparty.engine.GameModule;
import com.dinoparty.engine.GameOutcome;
import com.dinoparty.engine.GameSetup;
import com.dinoparty.engine.GameState;
import com.dinoparty.engine.OutboundEvent;
import com.dinoparty.engine.PhaseChange;
import com.dinoparty.engine.PlayerAction;
import com.dinoparty.engine.PlayerStatus;
import com.dinoparty.engine.PlayerStatusChange;
import com.dinoparty.engine.RoundRecord;
import com.dinoparty.engine.SubmissionRecord;
import com.dinoparty.engine.Transition;
import com.dinoparty.games.telephone.TelephoneState.Draft;
import com.dinoparty.games.telephone.TelephoneState.Entry;
import com.dinoparty.games.telephone.TelephoneState.Fill;
import com.dinoparty.games.telephone.TelephoneState.Kind;
import com.dinoparty.words.DifficultyFilter;
import com.dinoparty.words.WordPoolService;
import com.dinoparty.words.WordSelection;
import com.dinoparty.words.WordSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Dino Telephone ({@code telephone}): a prompt step followed by alternating drawing and describing
 * steps inside the shared engine. Rules: docs/game-rules.md [TP-1]..[TP-14]; wire format:
 * api-contracts.md §3.10; state machine: docs/telephone-design.md §4.
 *
 * <p>After the last step the albums are revealed one entry at a time ([TP-6]): the current host
 * moves a shared cursor with {@code REVEAL_NEXT}/{@code REVEAL_PREV}, an idle auto-advance keeps
 * the reveal moving, and NEXT on the last entry finishes the game. Every entry is handed to the
 * engine as a {@link SubmissionRecord} the moment it exists (D44), so albums outlive the session.
 *
 * <p>Every rejection is thrown before any state changes. Entry content only leaves this class in
 * the private {@code TELEPHONE_TASK} of the next holder and in that holder's {@link #view}, and
 * from the reveal on in {@code ALBUM_REVEALED} and reveal snapshots, never past the furthest entry
 * revealed ([TP-8], built by {@link TelephoneViews}).
 */
@Component
public class TelephoneModule implements GameModule {

  public static final String KEY = "telephone";

  static final String PHASE_END = "phase-end";
  static final String REVEAL_AUTO = "reveal-auto";
  static final String REVEAL = "REVEAL";

  /** Idle auto-advance of the reveal; shorter while the host is away ([TP-6], D42). */
  static final Duration AUTO_ADVANCE = Duration.ofSeconds(60);

  static final Duration AUTO_ADVANCE_HOST_AWAY = Duration.ofSeconds(15);

  static final String SUBMIT_TEXT = "TELEPHONE_SUBMIT_TEXT";
  static final String TEXT_DRAFT = "TELEPHONE_TEXT_DRAFT";
  static final String DRAW_STROKE = "TELEPHONE_DRAW_STROKE";
  static final String DRAW_UNDO = "TELEPHONE_DRAW_UNDO";
  static final String DRAW_CLEAR = "TELEPHONE_DRAW_CLEAR";
  static final String SUBMIT_DRAWING = "TELEPHONE_SUBMIT_DRAWING";
  static final String REVEAL_NEXT = "REVEAL_NEXT";
  static final String REVEAL_PREV = "REVEAL_PREV";

  private static final Set<String> TEXT_PHASES = Set.of(Kind.PROMPT.name(), Kind.DESCRIBE.name());
  private static final Set<String> DRAW_PHASES = Set.of(Kind.DRAW.name());
  private static final Set<String> REVEAL_PHASES = Set.of(REVEAL);

  private static final GameDefinition DEFINITION =
      new GameDefinition(
          KEY,
          4,
          8,
          3,
          false,
          List.of(
              new CommandSpec(SUBMIT_TEXT, 1024, 2, 3, TEXT_PHASES),
              new CommandSpec(TEXT_DRAFT, 1024, 2, 3, TEXT_PHASES),
              new CommandSpec(DRAW_STROKE, 8 * 1024, 30, 40, DRAW_PHASES),
              new CommandSpec(DRAW_UNDO, 128, 5, 5, DRAW_PHASES),
              new CommandSpec(DRAW_CLEAR, 128, 2, 3, DRAW_PHASES),
              new CommandSpec(SUBMIT_DRAWING, 128, 2, 3, DRAW_PHASES),
              new CommandSpec(REVEAL_NEXT, 256, 5, 5, REVEAL_PHASES),
              new CommandSpec(REVEAL_PREV, 256, 5, 5, REVEAL_PHASES)),
          Set.of("TELEPHONE_PROGRESS", "TELEPHONE_STEP_ENDED", "ALBUM_REVEALED"),
          Set.of("TELEPHONE_TASK"));

  private final WordPoolService words;

  public TelephoneModule(WordPoolService words) {
    this.words = words;
  }

  @Override
  public GameDefinition definition() {
    return DEFINITION;
  }

  // ------------------------------------------------------------------ engine calls

  /**
   * Freezes the auto-prompt pool when the host starts ([TP-3], D46): built-in words of difficulty
   * EASY and MEDIUM, so a later catalog change never reaches a running game.
   */
  @Override
  public GameSetup prepare(Map<String, Object> settings, UUID host) {
    Set<String> prompts = new LinkedHashSet<>();
    for (DifficultyFilter difficulty : List.of(DifficultyFilter.EASY, DifficultyFilter.MEDIUM)) {
      WordSelection selection =
          new WordSelection(WordSource.BUILT_IN, List.of(), difficulty, List.of());
      prompts.addAll(words.build(selection, host, 0).words());
    }
    if (prompts.isEmpty()) {
      throw new DomainException(
          ErrorCode.WORD_POOL_TOO_SMALL, "There are no prompts available for this game.");
    }
    return new TelephoneSetup(List.copyOf(prompts));
  }

  @Override
  public Transition init(GameContext ctx) {
    if (!(ctx.setup() instanceof TelephoneSetup setup)) {
      throw new IllegalStateException("Telephone started without a prepared prompt pool");
    }
    TelephoneSettings settings = TelephoneSettings.from(ctx.settings());
    int n = ctx.order().size();
    Map<UUID, String> nicknames = new HashMap<>();
    ctx.roster().forEach(p -> nicknames.put(p.guestId(), p.nickname()));
    TelephoneState s =
        new TelephoneState(settings, ctx.order(), nicknames, settings.entries(n), setup.prompts());
    return recorded(s, startStep(s, 0, ctx));
  }

  @Override
  public Transition handle(GameState state, PlayerAction action, GameContext ctx) {
    TelephoneState s = (TelephoneState) state;
    if (s.over || !s.index.containsKey(action.guestId())) {
      throw new ActionRejected(ErrorCode.INVALID_PHASE, "That action is not allowed now.");
    }
    Transition t =
        switch (action.type()) {
          case SUBMIT_TEXT -> submitText(s, action, ctx);
          case TEXT_DRAFT -> textDraft(s, action);
          case DRAW_STROKE -> drawStroke(s, action);
          case DRAW_UNDO -> undo(s, action);
          case DRAW_CLEAR -> clear(s, action);
          case SUBMIT_DRAWING -> submitDrawing(s, action, ctx);
          case REVEAL_NEXT -> revealMove(s, action, ctx, true);
          case REVEAL_PREV -> revealMove(s, action, ctx, false);
          default -> throw new ActionRejected(ErrorCode.INVALID_ACTION, "Unknown action.");
        };
    return recorded(s, t);
  }

  @Override
  public Transition onDeadline(GameState state, DeadlineFired deadline, GameContext ctx) {
    TelephoneState s = (TelephoneState) state;
    if (s.over) {
      return Transition.stay(s);
    }
    if (s.revealing) {
      return REVEAL_AUTO.equals(deadline.id())
          ? revealForward(s, ctx, true) // T4/T5 by the idle auto-advance
          : Transition.stay(s);
    }
    if (!PHASE_END.equals(deadline.id())) {
      return Transition.stay(s);
    }
    return recorded(s, endStep(s, "TIME", ctx)); // T2/T3 by time
  }

  @Override
  public Transition onPlayerStatus(GameState state, PlayerStatusChange change, GameContext ctx) {
    TelephoneState s = (TelephoneState) state;
    UUID changed = change.guestId();
    if (!s.index.containsKey(changed)) {
      return Transition.stay(s);
    }
    if (change.status() == PlayerStatus.DISCONNECTED) {
      s.disconnected.add(changed);
    } else {
      s.disconnected.remove(changed);
    }
    if (s.revealing) {
      if (change.status() == PlayerStatus.LEFT) {
        s.left.add(changed);
      }
      return hostAway(s, ctx);
    }
    if (change.status() != PlayerStatus.LEFT || !s.left.add(changed)) {
      return Transition.stay(s); // DISCONNECTED/ACTIVE change nothing; the snapshot restores
    }
    if (s.over) {
      return Transition.stay(s);
    }
    if (s.eligible().size() < DEFINITION.minActivePlayers()) {
      return Transition.stay(s); // the engine ends the game (T6, [C-1]); nothing to fill
    }
    UUID guest = change.guestId();
    int chain = s.chainOf(guest);
    if (s.chains.get(chain).size() == s.step) { // unsubmitted: fill now ([TP-5], [TP-10])
      fillCell(s, chain, ctx);
    }
    if (s.allSubmitted()) {
      return recorded(s, endStep(s, "ALL_SUBMITTED", ctx));
    }
    return recorded(s, Transition.stay(s).withEvents(TelephoneViews.progress(s)));
  }

  @Override
  public Map<String, Object> view(GameState state, UUID viewer, GameContext ctx) {
    return TelephoneViews.snapshot((TelephoneState) state, viewer);
  }

  @Override
  public GameOutcome outcome(GameState state, FinishReason reason, GameContext ctx) {
    return GameOutcome.empty(); // unscored: no standings ([TP-11])
  }

  // ------------------------------------------------------------------ commands

  private Transition submitText(TelephoneState s, PlayerAction action, GameContext ctx) {
    requireKind(s, Kind.PROMPT, Kind.DESCRIBE);
    requireOpen(s, action);
    String text = text(action);
    if (text.isEmpty()) {
      throw invalid("payload.text", "must be 1 to " + TelephoneText.MAX_LENGTH + " characters");
    }
    UUID guest = action.guestId();
    s.drafts.remove(guest);
    addEntry(s, s.chainOf(guest), new Entry(guest, text, null, Fill.NONE, false, false), ctx);
    return afterSubmit(s, guest, ctx);
  }

  private Transition textDraft(TelephoneState s, PlayerAction action) {
    requireKind(s, Kind.PROMPT, Kind.DESCRIBE);
    requireOpen(s, action);
    s.drafts.get(action.guestId()).text = text(action);
    return Transition.stay(s);
  }

  private Transition drawStroke(TelephoneState s, PlayerAction action) {
    Draft draft = openDrawDraft(s, action);
    StrokeBatch batch = StrokeBatch.parse(action.payload(), draft.canvas.limits());
    draft.canvas.append(batch); // validates everything before it mutates; no broadcast ([TP-9])
    return Transition.stay(s);
  }

  private Transition undo(TelephoneState s, PlayerAction action) {
    openDrawDraft(s, action).canvas.undo();
    return Transition.stay(s);
  }

  private Transition clear(TelephoneState s, PlayerAction action) {
    openDrawDraft(s, action).canvas.clear();
    return Transition.stay(s);
  }

  private Transition submitDrawing(TelephoneState s, PlayerAction action, GameContext ctx) {
    Draft draft = openDrawDraft(s, action);
    UUID guest = action.guestId();
    s.drafts.remove(guest);
    addEntry(
        s,
        s.chainOf(guest),
        new Entry(guest, null, draft.canvas.snapshot(), Fill.NONE, false, false),
        ctx);
    return afterSubmit(s, guest, ctx);
  }

  /** A submission is in: publish progress and end the step when it was the last one ([TP-4]). */
  private Transition afterSubmit(TelephoneState s, UUID guest, GameContext ctx) {
    s.submitted.add(guest);
    if (s.allSubmitted()) {
      return endStep(s, "ALL_SUBMITTED", ctx); // T2/T3 in the completing command
    }
    return Transition.stay(s).withEvents(TelephoneViews.progress(s));
  }

  // ------------------------------------------------------------------ transitions

  /** T1/T2: starts step {@code step}; left holders' cells are filled before anybody is asked. */
  private Transition startStep(TelephoneState s, int step, GameContext ctx) {
    Instant now = ctx.clock().instant();
    s.step = step;
    s.kind = Kind.of(step);
    s.phaseId = ctx.nextPhaseId();
    s.stepStartedAt = now;
    s.deadline = now.plusSeconds(s.settings.seconds(s.kind));
    s.drafts.clear();
    s.submitted.clear();
    s.timedOutGuests.clear();
    s.leftGuests.clear();
    List<UUID> holders = new ArrayList<>();
    for (int chain = 0; chain < s.n(); chain++) {
      UUID holder = s.holderOf(chain);
      if (s.isLeft(holder)) {
        fillCell(s, chain, ctx);
      } else {
        s.drafts.put(holder, new Draft(s.kind == Kind.DRAW));
        holders.add(holder);
      }
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("step", step);
    data.put("totalSteps", s.totalSteps);
    data.put("kind", s.kind.name());
    Transition t =
        Transition.stay(s)
            .withPhase(new PhaseChange(s.kind.name(), s.deadline, data))
            .withEvents(TelephoneViews.progress(s));
    for (UUID holder : s.order) {
      if (holders.contains(holder)) {
        t = t.withEvents(TelephoneViews.task(s, holder, s.phaseId));
      }
    }
    return t;
  }

  /**
   * Closes the running step: fills every missing cell ([TP-5]), records the round, publishes {@code
   * TELEPHONE_STEP_ENDED} and starts the next step, or finishes after the last one.
   */
  private Transition endStep(TelephoneState s, String reason, GameContext ctx) {
    for (int chain = 0; chain < s.n(); chain++) {
      if (s.chains.get(chain).size() == s.step) {
        fillCell(s, chain, ctx);
      }
    }
    Instant now = ctx.clock().instant();
    Map<String, Object> ended = new LinkedHashMap<>();
    ended.put("step", s.step);
    ended.put("kind", s.kind.name());
    ended.put("reason", reason);
    ended.put("timedOutGuestIds", List.copyOf(s.timedOutGuests));
    ended.put("leftGuestIds", List.copyOf(s.leftGuests));

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("step", s.step);
    meta.put("kind", s.kind.name());
    meta.put("submittedCount", s.submitted.size());
    meta.put("timedOutCount", s.timedOutGuests.size());
    meta.put("leftCount", s.leftGuests.size());
    RoundRecord record = new RoundRecord(s.step + 1, s.stepStartedAt, now, reason, Map.of(), meta);
    OutboundEvent stepEnded = OutboundEvent.publicEvent("TELEPHONE_STEP_ENDED", ended);

    if (s.step + 1 < s.totalSteps) {
      return startStep(s, s.step + 1, ctx).withClosing(stepEnded).withRounds(record);
    }
    return startReveal(s, ctx).withClosing(stepEnded).withRounds(record); // T3
  }

  // ------------------------------------------------------------------ reveal ([TP-6])

  /** T3: one REVEAL phase for every album; entry (0,0) is shown at once. */
  private Transition startReveal(TelephoneState s, GameContext ctx) {
    Instant now = ctx.clock().instant();
    s.revealing = true;
    s.step = s.totalSteps;
    s.kind = null;
    s.phaseId = ctx.nextPhaseId();
    s.deadline = null;
    s.drafts.clear();
    s.submitted.clear();
    s.cursor = 0;
    s.highWater = 0;
    s.autoAdvanceAt = now.plus(autoDelay(s, ctx));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("totalSteps", s.totalSteps);
    data.put("chains", s.n());
    data.put("cursor", TelephoneViews.position(s, 0));
    data.put("autoAdvanceAt", Timestamps.format(s.autoAdvanceAt));
    return Transition.stay(s)
        .withPhase(new PhaseChange(REVEAL, null, data))
        .withEvents(TelephoneViews.albumRevealed(s, true, false))
        .withDeadlines(new DeadlineRequest(REVEAL_AUTO, s.autoAdvanceAt));
  }

  /**
   * T4/T5 from the host. The payload is the cursor the host saw: a mismatch (double click, a race
   * with the auto-advance) is stale, so no entry is ever skipped.
   */
  private Transition revealMove(
      TelephoneState s, PlayerAction action, GameContext ctx, boolean forward) {
    if (!s.revealing) {
      throw new ActionRejected(ErrorCode.INVALID_PHASE, "That action is not allowed now.");
    }
    if (!action.guestId().equals(ctx.host())) {
      throw new ActionRejected(ErrorCode.NOT_HOST, "Only the host can move the reveal.");
    }
    int chain = index(action, "chainIndex");
    int entry = index(action, "entryIndex");
    if (chain * s.totalSteps + entry != s.cursor || entry >= s.totalSteps) {
      throw new ActionRejected(ErrorCode.STALE_ACTION, "The reveal has already moved on.");
    }
    if (forward) {
      return revealForward(s, ctx, false);
    }
    if (s.cursor == 0) {
      return Transition.stay(s); // acknowledged no-op at the first entry
    }
    s.cursor--;
    return revealMoved(s, ctx, false, false);
  }

  /** NEXT: shows the following entry (first time: with content) or finishes after the last. */
  private Transition revealForward(TelephoneState s, GameContext ctx, boolean auto) {
    if (s.cursor == s.lastPosition()) {
      s.revealing = false;
      s.over = true;
      return Transition.stay(s).finish(); // T5
    }
    s.cursor++;
    boolean newly = s.cursor > s.highWater;
    s.highWater = Math.max(s.highWater, s.cursor);
    return revealMoved(s, ctx, newly, auto);
  }

  private Transition revealMoved(TelephoneState s, GameContext ctx, boolean newly, boolean auto) {
    s.autoAdvanceAt = ctx.clock().instant().plus(autoDelay(s, ctx));
    return Transition.stay(s)
        .withEvents(TelephoneViews.albumRevealed(s, newly, auto))
        .withDeadlines(new DeadlineRequest(REVEAL_AUTO, s.autoAdvanceAt));
  }

  /** While the host is away the pending auto-advance comes at most 15 s from now ([TP-6]). */
  private Transition hostAway(TelephoneState s, GameContext ctx) {
    if (!isHostAway(s, ctx)) {
      return Transition.stay(s);
    }
    Instant soon = ctx.clock().instant().plus(AUTO_ADVANCE_HOST_AWAY);
    if (!soon.isBefore(s.autoAdvanceAt)) {
      return Transition.stay(s);
    }
    s.autoAdvanceAt = soon;
    return Transition.stay(s).withDeadlines(new DeadlineRequest(REVEAL_AUTO, soon));
  }

  private static Duration autoDelay(TelephoneState s, GameContext ctx) {
    return isHostAway(s, ctx) ? AUTO_ADVANCE_HOST_AWAY : AUTO_ADVANCE;
  }

  private static boolean isHostAway(TelephoneState s, GameContext ctx) {
    UUID host = ctx.host();
    return host == null || s.disconnected.contains(host) || s.isLeft(host);
  }

  /**
   * Gives a cell without an entry its entry ([TP-5]): the holder's draft as it stands, else a
   * placeholder (or a pool prompt in the prompt step). Called for a holder who is LEFT or whose
   * time ran out.
   */
  private void fillCell(TelephoneState s, int chain, GameContext ctx) {
    UUID holder = s.holderOf(chain);
    boolean left = s.isLeft(holder);
    Fill fill = left ? Fill.LEFT_PLAYER : Fill.TIMEOUT;
    boolean timedOut = !left;
    Draft draft = s.drafts.remove(holder);
    boolean hasDraft = draft != null && !draft.isEmpty();
    Entry entry;
    if (s.kind == Kind.DRAW) {
      List<Map<String, Object>> strokes = hasDraft ? draft.canvas.snapshot() : List.of();
      entry = new Entry(holder, null, strokes, fill, !hasDraft, timedOut);
    } else if (hasDraft) {
      entry = new Entry(holder, draft.text, null, fill, false, timedOut);
    } else if (s.kind == Kind.PROMPT) {
      String prompt = PromptPool.draw(s.prompts, s.usedPrompts, ctx.random());
      entry = new Entry(holder, prompt, null, Fill.AUTO_PROMPT, false, false);
    } else {
      entry = new Entry(holder, TelephoneText.NO_DESCRIPTION, null, fill, true, timedOut);
    }
    addEntry(s, chain, entry, ctx);
    (left ? s.leftGuests : s.timedOutGuests).add(holder);
  }

  // ------------------------------------------------------------------ helpers

  /** Adds the entry of the chain's next cell and queues it for persistence (D44). */
  private static void addEntry(TelephoneState s, int chain, Entry entry, GameContext ctx) {
    List<Entry> cells = s.chains.get(chain);
    cells.add(entry);
    s.unrecorded.add(
        new SubmissionRecord(
            chain,
            cells.size() - 1,
            entry.author(),
            entry.isDrawing() ? SubmissionRecord.Kind.DRAWING : SubmissionRecord.Kind.TEXT,
            entry.text(),
            entry.strokes(),
            entry.fill().name(),
            entry.placeholder(),
            entry.timedOut(),
            s.totalSteps,
            ctx.clock().instant()));
  }

  /** Hands the entries created during this call to the engine with the transition. */
  private static Transition recorded(TelephoneState s, Transition t) {
    if (s.unrecorded.isEmpty()) {
      return t;
    }
    SubmissionRecord[] records = s.unrecorded.toArray(SubmissionRecord[]::new);
    s.unrecorded.clear();
    return t.withSubmissions(records);
  }

  private static int index(PlayerAction action, String field) {
    if (!(action.payload().get(field) instanceof Integer value) || value < 0) {
      throw invalid("payload." + field, "must be a non-negative integer");
    }
    return value;
  }

  /** [TP-7]: any input after the holder's submission is rejected, state unchanged. */
  private static void requireOpen(TelephoneState s, PlayerAction action) {
    if (s.submitted.contains(action.guestId())) {
      throw new ActionRejected(ErrorCode.ALREADY_SUBMITTED, "You have already submitted.");
    }
  }

  private static Draft openDrawDraft(TelephoneState s, PlayerAction action) {
    requireKind(s, Kind.DRAW);
    requireOpen(s, action);
    return s.drafts.get(action.guestId());
  }

  private static void requireKind(TelephoneState s, Kind... kinds) {
    for (Kind kind : kinds) {
      if (s.kind == kind) {
        return;
      }
    }
    throw new ActionRejected(ErrorCode.INVALID_PHASE, "That action is not allowed now.");
  }

  /** The sanitized {@code payload.text}, possibly empty, never longer than 140 code points. */
  private static String text(PlayerAction action) {
    if (!(action.payload().get("text") instanceof String raw)) {
      throw invalid("payload.text", "must be text");
    }
    String text = TelephoneText.sanitize(raw);
    if (TelephoneText.length(text) > TelephoneText.MAX_LENGTH) {
      throw invalid("payload.text", "must be 1 to " + TelephoneText.MAX_LENGTH + " characters");
    }
    return text;
  }

  private static ActionRejected invalid(String field, String message) {
    return new ActionRejected(ErrorCode.VALIDATION_FAILED, field + " " + message);
  }
}
