package com.dinoparty.games.drawguess;

import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.RandomSource;
import com.dinoparty.common.Timestamps;
import com.dinoparty.drawing.StrokeBatch;
import com.dinoparty.engine.ActionRejected;
import com.dinoparty.engine.Audience;
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
import com.dinoparty.engine.ScoreDelta;
import com.dinoparty.engine.Transition;
import com.dinoparty.games.drawguess.DrawGuessState.ChatLine;
import com.dinoparty.games.drawguess.DrawGuessState.Phase;
import com.dinoparty.games.drawguess.DrawGuessState.Stats;
import com.dinoparty.words.WordPoolService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Draw & Guess ({@code draw-guess}): rounds of word choice, drawing and round summary inside the
 * shared engine. Rules: docs/game-rules.md [DG-1]..[DG-18]; wire format: api-contracts.md §3.9;
 * state machine T1..T10: docs/draw-guess-design.md §3.
 *
 * <p>Every rejection is an {@link ActionRejected} (or a {@code DomainException} from the drawing
 * library) thrown before any state changes. Secrets (choices, word) only leave this class in
 * private events to the drawer, in the drawer's {@link #view}, and publicly in {@code ROUND_ENDED}.
 */
@Component
public class DrawGuessModule implements GameModule {

  public static final String KEY = "draw-guess";

  static final String WORD_CHOICE = "WORD_CHOICE";
  static final String DRAWING = "DRAWING";
  static final String ROUND_END = "ROUND_END";

  static final String PHASE_END = "phase-end";
  static final String HINT_1 = "hint-1";
  static final String HINT_2 = "hint-2";

  static final int WORD_CHOICES = 3;
  static final Duration ROUND_END_PAUSE = Duration.ofSeconds(6);

  private static final GameDefinition DEFINITION =
      new GameDefinition(
          KEY,
          2,
          10,
          2,
          true,
          List.of(
              new CommandSpec("CHOOSE_WORD", 256, 2, 3, Set.of(WORD_CHOICE)),
              new CommandSpec("DRAW_STROKE", 8 * 1024, 30, 40, Set.of(DRAWING)),
              new CommandSpec("DRAW_UNDO", 128, 5, 5, Set.of(DRAWING)),
              new CommandSpec("DRAW_CLEAR", 128, 2, 3, Set.of(DRAWING)),
              new CommandSpec("SUBMIT_GUESS", 1024, 2, 5, Set.of(DRAWING))),
          Set.of("DRAWING_UPDATED", "HINT_UPDATED", "GUESS_ACCEPTED", "GUESS_CHAT", "ROUND_ENDED"),
          Set.of("WORD_CHOICES", "DRAWER_WORD", "GUESS_FEEDBACK", "GUESSER_CHAT"));

  private final WordPoolService words;

  /**
   * Draws the word choices. The server's cryptographically strong source, not the session's seeded
   * {@code ctx.random()}: the session seed is stored with the session, and the offered words must
   * not be reconstructible from it ([DG-2]: unchosen words are never persisted or revealed).
   */
  private final RandomSource wordRandom;

  public DrawGuessModule(WordPoolService words, RandomSource wordRandom) {
    this.words = words;
    this.wordRandom = wordRandom;
  }

  @Override
  public GameDefinition definition() {
    return DEFINITION;
  }

  // ------------------------------------------------------------------ engine calls

  /**
   * Resolves and freezes the word pool when the host starts ([DG-15]): rejected with {@code
   * VALIDATION_FAILED} or {@code WORD_POOL_TOO_SMALL} unless it has at least one draw of words.
   */
  @Override
  public GameSetup prepare(Map<String, Object> settings, UUID host) {
    DrawGuessSettings s = DrawGuessSettings.from(settings);
    return new DrawGuessSetup(words.build(s.words(), host, WORD_CHOICES));
  }

  @Override
  public Transition init(GameContext ctx) {
    if (!(ctx.setup() instanceof DrawGuessSetup setup)) {
      throw new IllegalStateException("Draw & Guess started without a prepared word pool");
    }
    DrawGuessState s =
        new DrawGuessState(DrawGuessSettings.from(ctx.settings()), ctx.order(), setup.pool());
    return nextTurnOrFinish(s, ctx);
  }

  @Override
  public Transition handle(GameState state, PlayerAction action, GameContext ctx) {
    DrawGuessState s = (DrawGuessState) state;
    return switch (action.type()) {
      case "CHOOSE_WORD" -> chooseWord(s, action, ctx);
      case "DRAW_STROKE" -> drawStroke(s, action);
      case "DRAW_UNDO" -> undo(s, action);
      case "DRAW_CLEAR" -> clear(s, action);
      case "SUBMIT_GUESS" -> guess(s, action, ctx);
      default -> throw new ActionRejected(ErrorCode.INVALID_ACTION, "Unknown action.");
    };
  }

  @Override
  public Transition onDeadline(GameState state, DeadlineFired deadline, GameContext ctx) {
    DrawGuessState s = (DrawGuessState) state;
    return switch (deadline.id()) {
      case PHASE_END ->
          switch (s.phase) {
            // [DG-2] the server picks one of the three when the drawer did not choose (T3).
            case WORD_CHOICE -> startDrawing(s, ctx.random().nextInt(WORD_CHOICES), true, ctx);
            case DRAWING -> endRound(s, ctx, "TIME", List.of(), List.of()); // T4
            case ROUND_END -> nextTurnOrFinish(s, ctx); // T8 / T9
          };
      case HINT_1, HINT_2 -> revealHint(s, ctx);
      default -> Transition.stay(s);
    };
  }

  @Override
  public Transition onPlayerStatus(GameState state, PlayerStatusChange change, GameContext ctx) {
    DrawGuessState s = (DrawGuessState) state;
    if (change.status() != PlayerStatus.LEFT || !s.left.add(change.guestId())) {
      return Transition.stay(s); // DISCONNECTED/ACTIVE change nothing; the snapshot restores
    }
    UUID guest = change.guestId();
    boolean roundLive = s.phase == Phase.WORD_CHOICE || s.phase == Phase.DRAWING;
    if (roundLive && guest.equals(s.drawer)) {
      return endRound(s, ctx, "DRAWER_LEFT", List.of(), List.of()); // T6, [DG-10]
    }
    if (s.phase == Phase.DRAWING && s.eligible.contains(guest) && !s.solvers.contains(guest)) {
      // D25: an unsolved guesser who left is no longer eligible; re-evaluate [DG-9] (b).
      s.eligible.remove(guest);
      if (allGuessed(s)) {
        return endRound(s, ctx, "ALL_GUESSED", List.of(), List.of());
      }
    }
    return Transition.stay(s);
  }

  @Override
  public Map<String, Object> view(GameState state, UUID viewer, GameContext ctx) {
    DrawGuessState s = (DrawGuessState) state;
    boolean isDrawer = viewer.equals(s.drawer);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("round", s.round);
    view.put("totalRounds", s.totalRounds);
    view.put("drawerGuestId", s.drawer);
    view.put("hint", s.phase == Phase.WORD_CHOICE ? null : s.hint);
    // [DG-18]: the word only for the drawer while drawing, for everyone once the round ended.
    boolean wordVisible = s.phase == Phase.ROUND_END || (s.phase == Phase.DRAWING && isDrawer);
    view.put("word", wordVisible ? s.word : null);
    view.put(
        "wordChoices",
        s.phase == Phase.WORD_CHOICE && isDrawer ? List.copyOf(s.wordChoices) : null);
    view.put("strokes", s.canvas.snapshot());
    view.put("guessedGuestIds", List.copyOf(s.solvers));
    List<Map<String, Object>> chat = new ArrayList<>();
    for (ChatLine line : s.chat) {
      if (line.audience() == null || line.audience().contains(viewer)) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("guestId", line.guestId());
        entry.put("text", line.text());
        entry.put("scope", line.scope());
        entry.put("ts", line.ts());
        chat.add(entry);
      }
    }
    view.put("recentChat", chat);
    view.put("canvasBudget", s.canvas.budget());
    if (s.phase == Phase.ROUND_END) {
      Map<String, Object> roundEnd = new LinkedHashMap<>();
      roundEnd.put("reason", s.endReason);
      roundEnd.put("scoreDeltas", roundDeltas(s, ctx));
      view.put("roundEnd", roundEnd);
    } else {
      view.put("roundEnd", null);
    }
    return view;
  }

  @Override
  public GameOutcome outcome(GameState state, FinishReason reason, GameContext ctx) {
    DrawGuessState s = (DrawGuessState) state;
    Map<UUID, Map<String, Object>> detail = new LinkedHashMap<>();
    for (UUID g : ctx.order()) {
      Stats st = s.stats.get(g);
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("correctGuesses", st.correctGuesses);
      d.put("guesserPoints", st.guesserPoints);
      d.put("drawerPoints", st.drawerPoints);
      d.put("roundsDrawn", st.roundsDrawn);
      detail.put(g, d);
    }
    return new GameOutcome(detail);
  }

  // ------------------------------------------------------------------ commands

  private Transition chooseWord(DrawGuessState s, PlayerAction action, GameContext ctx) {
    requirePhase(s, Phase.WORD_CHOICE);
    requireDrawer(s, action);
    if (!(action.payload().get("wordIndex") instanceof Integer index)
        || index < 0
        || index >= WORD_CHOICES) {
      throw invalid("payload.wordIndex", "must be 0, 1 or 2");
    }
    return startDrawing(s, index, false, ctx); // T2
  }

  private Transition drawStroke(DrawGuessState s, PlayerAction action) {
    requirePhase(s, Phase.DRAWING);
    requireDrawer(s, action);
    StrokeBatch batch = StrokeBatch.parse(action.payload(), s.canvas.limits());
    s.canvas.append(batch); // validates everything before it mutates
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("kind", "STROKE");
    payload.put("strokeId", batch.strokeId());
    payload.put("batchNo", batch.batchNo());
    payload.put("tool", batch.tool().name());
    payload.put("color", batch.color());
    payload.put("size", batch.size());
    payload.put("points", batch.points());
    payload.put("final", batch.finalBatch());
    return Transition.stay(s).withEvents(OutboundEvent.publicEvent("DRAWING_UPDATED", payload));
  }

  private Transition undo(DrawGuessState s, PlayerAction action) {
    requirePhase(s, Phase.DRAWING);
    requireDrawer(s, action);
    Optional<UUID> removed = s.canvas.undo();
    if (removed.isEmpty()) {
      return Transition.stay(s); // acknowledged no-op on an empty canvas
    }
    return Transition.stay(s)
        .withEvents(
            OutboundEvent.publicEvent(
                "DRAWING_UPDATED", Map.of("kind", "UNDO", "strokeId", removed.get())));
  }

  private Transition clear(DrawGuessState s, PlayerAction action) {
    requirePhase(s, Phase.DRAWING);
    requireDrawer(s, action);
    if (!s.canvas.clear()) {
      return Transition.stay(s);
    }
    return Transition.stay(s)
        .withEvents(OutboundEvent.publicEvent("DRAWING_UPDATED", Map.of("kind", "CLEAR")));
  }

  /** [DG-4]..[DG-7], design §4.1, in that order. */
  private Transition guess(DrawGuessState s, PlayerAction action, GameContext ctx) {
    requirePhase(s, Phase.DRAWING);
    UUID guest = action.guestId();
    if (guest.equals(s.drawer)) {
      throw new ActionRejected(ErrorCode.IS_DRAWER, "The drawer cannot guess.");
    }
    if (!(action.payload().get("text") instanceof String raw)) {
      throw invalid("payload.text", "must be text");
    }
    String text = GuessMatcher.sanitize(raw);
    int length = GuessMatcher.length(text);
    if (length < 1 || length > GuessMatcher.MAX_LENGTH) {
      throw invalid("payload.text", "must be 1 to " + GuessMatcher.MAX_LENGTH + " characters");
    }
    String normalized = GuessMatcher.normalize(text);
    if (normalized.isEmpty()) {
      throw invalid("payload.text", "must contain a letter or digit");
    }
    Instant now = ctx.clock().instant();
    String ts = Timestamps.format(now);

    if (s.solvers.contains(guest)) {
      // [DG-7] restricted chat among solvers and the drawer; never evaluated as a guess.
      Set<UUID> audience = new LinkedHashSet<>();
      audience.add(s.drawer);
      audience.addAll(s.solvers);
      audience.removeIf(s::isLeft);
      s.addChat(new ChatLine(guest, text, "GUESSERS", ts, Set.copyOf(audience)));
      return Transition.stay(s)
          .withEvents(
              new OutboundEvent(
                  "GUESSER_CHAT",
                  new Audience.Private(audience),
                  Map.of("guestId", guest, "text", text)));
    }

    if (GuessMatcher.isCorrect(normalized, s.normalizedWord)) {
      int order = s.solvers.size();
      long remMs = Duration.between(now, s.drawingDeadline).toMillis();
      int points = Scoring.guesserPoints(remMs, s.settings.drawSeconds(), order);
      s.solvers.add(guest);
      s.award(guest, points);
      Stats st = s.stats.get(guest);
      st.correctGuesses++;
      st.guesserPoints += points;
      Map<String, Object> accepted = new LinkedHashMap<>();
      accepted.put("guestId", guest);
      accepted.put("scoreDelta", points);
      accepted.put("totalScore", s.totals.get(guest));
      accepted.put("order", order);
      OutboundEvent event = OutboundEvent.publicEvent("GUESS_ACCEPTED", accepted);
      ScoreDelta delta = new ScoreDelta(guest, points, "GUESS");
      if (allGuessed(s)) {
        return endRound(s, ctx, "ALL_GUESSED", List.of(event), List.of(delta)); // T5
      }
      return Transition.stay(s).withEvents(event).withScores(delta);
    }

    if (GuessMatcher.isClose(normalized, s.normalizedWord)) {
      return Transition.stay(s).withEvents(feedback(guest, "CLOSE")); // [DG-5] private only
    }
    if (s.seenWrongGuesses.contains(normalized)) {
      return Transition.stay(s).withEvents(feedback(guest, "DUPLICATE")); // [DG-6]
    }
    if (s.seenWrongGuesses.size() < DrawGuessState.MAX_SEEN_WRONG) {
      s.seenWrongGuesses.add(normalized);
    }
    s.addChat(new ChatLine(guest, text, "PUBLIC", ts, null));
    return Transition.stay(s)
        .withEvents(
            OutboundEvent.publicEvent("GUESS_CHAT", Map.of("guestId", guest, "text", text)));
  }

  // ------------------------------------------------------------------ transitions

  /** T1/T8: the next turn whose player has not left, or finish (T9) when none is left. */
  private Transition nextTurnOrFinish(DrawGuessState s, GameContext ctx) {
    int next = nextTurnIndex(s, s.turnIndex);
    if (next < 0) {
      return Transition.stay(s).finish();
    }
    s.resetRound();
    s.turnIndex = next;
    s.round++;
    s.drawer = s.turns.get(next);
    s.phase = Phase.WORD_CHOICE;
    Instant now = ctx.clock().instant();
    s.roundStartedAt = now;
    s.totalRounds = totalRounds(s);
    s.wordChoices = s.pool.draw(s.usedWords, wordRandom, WORD_CHOICES);
    Instant deadline = now.plusSeconds(s.settings.wordChoiceSeconds());
    Map<String, Object> secret = new LinkedHashMap<>();
    secret.put("phaseId", ctx.nextPhaseId());
    secret.put("choices", s.wordChoices);
    secret.put("deadlineAt", Timestamps.format(deadline));
    return Transition.stay(s)
        .withPhase(new PhaseChange(WORD_CHOICE, deadline, roundData(s)))
        .withEvents(OutboundEvent.privateEvent(s.drawer, "WORD_CHOICES", secret));
  }

  /** T2/T3: the chosen (or auto-picked) word replaces the choices; the canvas starts empty. */
  private Transition startDrawing(DrawGuessState s, int index, boolean auto, GameContext ctx) {
    s.word = s.wordChoices.get(index);
    s.wordChoices = null;
    s.usedWords.add(s.word);
    s.normalizedWord = GuessMatcher.normalize(s.word);
    s.autoPicked = auto;
    s.hint = HintMasker.initial(s.word);
    s.phase = Phase.DRAWING;
    s.eligible.clear();
    for (UUID g : s.totals.keySet()) {
      if (!g.equals(s.drawer) && !s.isLeft(g)) {
        s.eligible.add(g);
      }
    }
    Instant now = ctx.clock().instant();
    long drawMs = s.settings.drawSeconds() * 1000L;
    s.drawingDeadline = now.plusMillis(drawMs);
    s.totalRounds = totalRounds(s);
    Map<String, Object> data = roundData(s);
    data.put("hint", s.hint);
    Transition t =
        Transition.stay(s)
            .withPhase(new PhaseChange(DRAWING, s.drawingDeadline, data))
            .withEvents(
                OutboundEvent.privateEvent(
                    s.drawer, "DRAWER_WORD", Map.of("phaseId", ctx.nextPhaseId(), "word", s.word)));
    if (s.settings.hintReveal() && HintMasker.maxRevealed(s.word) > 0) {
      t =
          t.withDeadlines(
              new DeadlineRequest(HINT_1, now.plusMillis(drawMs / 2)),
              new DeadlineRequest(HINT_2, now.plusMillis(drawMs * 3 / 4)));
    }
    return t;
  }

  private Transition revealHint(DrawGuessState s, GameContext ctx) {
    if (s.phase != Phase.DRAWING || !s.settings.hintReveal()) {
      return Transition.stay(s);
    }
    String next = HintMasker.reveal(s.word, s.hint, ctx.random());
    if (next.equals(s.hint)) {
      return Transition.stay(s);
    }
    s.hint = next;
    return Transition.stay(s)
        .withEvents(OutboundEvent.publicEvent("HINT_UPDATED", Map.of("hint", s.hint)));
  }

  /**
   * T4/T5/T6: awards the drawer ([DG-13]; 0 when the drawer left, D24), reveals the word, records
   * the round and starts the 6 s summary. {@code before}/{@code scores} carry the guess that ended
   * the round, so its {@code GUESS_ACCEPTED} precedes {@code ROUND_ENDED}.
   */
  private Transition endRound(
      DrawGuessState s,
      GameContext ctx,
      String reason,
      List<OutboundEvent> before,
      List<ScoreDelta> scores) {
    Instant now = ctx.clock().instant();
    List<ScoreDelta> deltas = new ArrayList<>(scores);
    boolean drawerLeft = "DRAWER_LEFT".equals(reason);
    if (!drawerLeft) {
      int drawerPoints = Scoring.drawerPoints(s.solvers.size(), s.eligible.size());
      Stats st = s.stats.get(s.drawer);
      st.roundsDrawn++;
      if (drawerPoints > 0) {
        s.award(s.drawer, drawerPoints);
        st.drawerPoints += drawerPoints;
        deltas.add(new ScoreDelta(s.drawer, drawerPoints, "DRAW"));
      }
    }
    s.phase = Phase.ROUND_END;
    s.endReason = reason;
    s.wordChoices = null; // unchosen words are never revealed ([DG-2])
    s.totalRounds = totalRounds(s);

    Map<UUID, Integer> roundDeltas = roundDeltas(s, ctx);
    Map<String, Object> ended = new LinkedHashMap<>();
    ended.put("round", s.round);
    ended.put("drawerGuestId", s.drawer);
    ended.put("word", s.word);
    ended.put("reason", reason);
    ended.put("scoreDeltas", roundDeltas);
    ended.put("scores", new LinkedHashMap<>(s.totals));

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("drawerGuestId", s.drawer);
    meta.put("word", s.word);
    meta.put("autoPicked", s.autoPicked);
    meta.put("correctGuessers", List.copyOf(s.solvers));
    RoundRecord record = new RoundRecord(s.round, s.roundStartedAt, now, reason, roundDeltas, meta);

    List<OutboundEvent> closing = new ArrayList<>(before);
    closing.add(OutboundEvent.publicEvent("ROUND_ENDED", ended));
    return Transition.stay(s)
        .withClosing(closing.toArray(OutboundEvent[]::new))
        .withPhase(new PhaseChange(ROUND_END, now.plus(ROUND_END_PAUSE), roundData(s)))
        .withScores(deltas.toArray(ScoreDelta[]::new))
        .withRounds(record);
  }

  // ------------------------------------------------------------------ helpers

  /** [DG-9] (b): no unsolved eligible guesser left and at least one solver. */
  private static boolean allGuessed(DrawGuessState s) {
    return !s.solvers.isEmpty() && s.solvers.containsAll(s.eligible);
  }

  private static int nextTurnIndex(DrawGuessState s, int from) {
    for (int i = from + 1; i < s.turns.size(); i++) {
      if (!s.isLeft(s.turns.get(i))) {
        return i;
      }
    }
    return -1;
  }

  /** [DG-1]: rounds played so far plus remaining turns whose player has not left (D29). */
  private static int totalRounds(DrawGuessState s) {
    int remaining = 0;
    for (int i = s.turnIndex + 1; i < s.turns.size(); i++) {
      if (!s.isLeft(s.turns.get(i))) {
        remaining++;
      }
    }
    return s.round + remaining;
  }

  private static Map<String, Object> roundData(DrawGuessState s) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("round", s.round);
    data.put("totalRounds", s.totalRounds);
    data.put("drawerGuestId", s.drawer);
    return data;
  }

  /** This round's points of every roster member, in play order. */
  private static Map<UUID, Integer> roundDeltas(DrawGuessState s, GameContext ctx) {
    Map<UUID, Integer> deltas = new LinkedHashMap<>();
    for (UUID g : ctx.order()) {
      deltas.put(g, s.roundDeltas.getOrDefault(g, 0));
    }
    return deltas;
  }

  private static OutboundEvent feedback(UUID guest, String result) {
    return OutboundEvent.privateEvent(guest, "GUESS_FEEDBACK", Map.of("result", result));
  }

  private static void requirePhase(DrawGuessState s, Phase phase) {
    if (s.phase != phase) {
      throw new ActionRejected(ErrorCode.INVALID_PHASE, "That action is not allowed now.");
    }
  }

  private static void requireDrawer(DrawGuessState s, PlayerAction action) {
    if (!action.guestId().equals(s.drawer)) {
      throw new ActionRejected(ErrorCode.NOT_DRAWER, "Only the drawer can do that.");
    }
  }

  private static ActionRejected invalid(String field, String message) {
    return new ActionRejected(ErrorCode.VALIDATION_FAILED, field + " " + message);
  }
}
