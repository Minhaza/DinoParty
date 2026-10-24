package com.dinoparty.games.impostor;

import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.Timestamps;
import com.dinoparty.engine.ActionRejected;
import com.dinoparty.engine.DeadlineRequest;
import com.dinoparty.engine.GameContext;
import com.dinoparty.engine.GameState;
import com.dinoparty.engine.OutboundEvent;
import com.dinoparty.engine.PhaseChange;
import com.dinoparty.engine.PlayerAction;
import com.dinoparty.engine.RoundRecord;
import com.dinoparty.engine.ScoreDelta;
import com.dinoparty.engine.Transition;
import com.dinoparty.words.GuessNormalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The part of one impostor round after the drawing, shared by both games ([DI-8]..[DI-14]): the
 * voting phase with its chat, the close of the vote, the impostor's guess, the round end with its
 * scores and {@code RoundRecord}, and an impostor who leaves ([DI-13]). One instance per round.
 *
 * <p>A game keeps its own phases before voting and calls {@link #open} when they are over; it
 * routes the commands, the deadlines and the leaves here and keeps its own state's phase in step
 * with the {@link PhaseChange} of the returned transition. What differs is in {@link
 * ImpostorRules}. This is the one place that builds the votes, the reveal and the guess, so the
 * secrets of R35 cannot leak from anywhere else: no vote target is published before the vote
 * closes, the impostor only when caught or at the round end, the word only at the round end.
 */
final class ImpostorVoting {

  /** Where the round is; the game's own phases before {@link #open} are {@code PENDING}. */
  enum Stage {
    PENDING,
    VOTING,
    GUESS,
    ENDED
  }

  private final ImpostorRules rules;
  private final ImpostorRound round;
  private final List<UUID> seated;
  private final int totalRounds;
  private final Instant startedAt;
  private final String decoyWord;
  private final ImpostorScoreboard board;

  private final ImpostorVotes votes = new ImpostorVotes();
  private final ImpostorChat chat = new ImpostorChat();
  private final RateWindow voteRate =
      new RateWindow(ImpostorFamily.VOTES_PER_WINDOW, ImpostorFamily.RATE_WINDOW);
  private final Set<UUID> gone = new HashSet<>();

  private Stage stage = Stage.PENDING;
  private Instant openedAt;
  private UUID votePhaseId;
  private UUID accused;
  private boolean guessed;
  private Map<String, Object> voteClosed;
  private Map<String, Object> roundEnd;

  /**
   * @param seated the players seated this round, in a stable order
   * @param decoyWord the unaware impostor's word in Sketchy Saurus, else {@code null}
   */
  ImpostorVoting(
      ImpostorRules rules,
      ImpostorRound round,
      List<UUID> seated,
      int totalRounds,
      Instant startedAt,
      String decoyWord,
      ImpostorScoreboard board) {
    this.rules = rules;
    this.round = round;
    this.seated = List.copyOf(seated);
    this.totalRounds = totalRounds;
    this.startedAt = startedAt;
    this.decoyWord = decoyWord;
    this.board = board;
  }

  Stage stage() {
    return stage;
  }

  // ------------------------------------------------------------------ the voting phase

  /**
   * Starts the voting phase: its deadline, the 15 s mark and an empty {@code
   * IMPOSTOR_VOTE_PROGRESS}. {@code data} is the game's public {@code PHASE_STARTED} data.
   */
  Transition open(GameState state, GameContext ctx, Map<String, Object> data) {
    Instant now = ctx.clock().instant();
    stage = Stage.VOTING;
    openedAt = now;
    votePhaseId = ctx.nextPhaseId();
    return Transition.stay(state)
        .withPhase(new PhaseChange(rules.votePhase(), now.plusSeconds(rules.voteSeconds()), data))
        .withDeadlines(
            new DeadlineRequest(
                ImpostorFamily.VOTE_MINIMUM_DEADLINE, now.plus(ImpostorFamily.VOTE_MINIMUM)))
        .withEvents(progress());
  }

  /** [DI-8] {@code IMPOSTOR_CHAT}. */
  Transition chat(GameState state, PlayerAction action, GameContext ctx) {
    requireStage(Stage.VOTING);
    Map<String, Object> line =
        chat.post(action.guestId(), action.payload().get("text"), ctx.clock().instant());
    return Transition.stay(state)
        .withEvents(OutboundEvent.publicEvent(ImpostorFamily.CHAT_MESSAGE_EVENT, line));
  }

  /** [DI-9] {@code IMPOSTOR_VOTE}: records, changes or withdraws, then closes early if due. */
  Transition vote(GameState state, PlayerAction action, GameContext ctx) {
    requireStage(Stage.VOTING);
    UUID voter = action.guestId();
    UUID target = parseTarget(action.payload());
    Instant now = ctx.clock().instant();
    voteRate.check(voter, now);
    votes.cast(voter, target, eligible());
    voteRate.record(voter, now);
    OutboundEvent progress = progress();
    if (ready(now)) {
      return closeVote(state, ctx, List.of(progress));
    }
    return Transition.stay(state).withEvents(progress);
  }

  /** The deadline at the 15 s mark: closes the vote if everyone has voted by then. */
  Transition onVoteMinimum(GameState state, GameContext ctx) {
    if (stage == Stage.VOTING && ready(ctx.clock().instant())) {
      return closeVote(state, ctx, List.of());
    }
    return Transition.stay(state);
  }

  /** The phase deadline: the vote closes as it stands; an unanswered guess is wrong. */
  Transition onPhaseEnd(GameState state, GameContext ctx) {
    return switch (stage) {
      case VOTING -> closeVote(state, ctx, List.of());
      case GUESS -> endRound(state, ctx, ImpostorOutcome.CAUGHT, null, List.of());
      case PENDING, ENDED -> Transition.stay(state);
    };
  }

  /** [DI-9]: everyone not {@code LEFT} has voted and the first 15 s have passed. */
  private boolean ready(Instant now) {
    return !now.isBefore(openedAt.plus(ImpostorFamily.VOTE_MINIMUM)) && votes.allIn(eligible());
  }

  private Transition closeVote(GameState state, GameContext ctx, List<OutboundEvent> before) {
    ImpostorVotes.Result result = votes.result(round.impostor());
    accused = result.accused();
    Map<String, Object> closed = new LinkedHashMap<>();
    closed.put("round", round.round());
    closed.put("votes", votes.snapshot());
    closed.put("accusedGuestId", accused);
    closed.put("caught", result.caught());
    closed.put("impostorGuestId", result.caught() ? round.impostor() : null);
    voteClosed = Collections.unmodifiableMap(closed);
    List<OutboundEvent> closing = new ArrayList<>(before);
    closing.add(OutboundEvent.publicEvent(ImpostorFamily.VOTE_CLOSED_EVENT, voteClosed));

    if (!result.caught()) {
      return endRound(state, ctx, ImpostorOutcome.ESCAPED, null, closing);
    }
    if (!rules.impostorMayGuess().getAsBoolean()) {
      return endRound(state, ctx, ImpostorOutcome.CAUGHT, null, closing);
    }
    return openGuess(state, ctx, closing);
  }

  // ------------------------------------------------------------------ the guess

  private Transition openGuess(GameState state, GameContext ctx, List<OutboundEvent> closing) {
    Instant deadline = ctx.clock().instant().plusSeconds(rules.guessSeconds());
    stage = Stage.GUESS;
    Map<String, Object> data = data();
    data.put("impostorGuestId", round.impostor());
    Map<String, Object> open = new LinkedHashMap<>();
    open.put("phaseId", ctx.nextPhaseId());
    open.put("round", round.round());
    open.put("category", round.category());
    open.put("deadlineAt", Timestamps.format(deadline));
    return Transition.stay(state)
        .withClosing(closing.toArray(OutboundEvent[]::new))
        .withPhase(new PhaseChange(ImpostorFamily.GUESS_PHASE, deadline, data))
        .withEvents(
            OutboundEvent.privateEvent(round.impostor(), ImpostorFamily.GUESS_OPEN_EVENT, open));
  }

  /** [DI-11] {@code IMPOSTOR_GUESS}: the caught impostor's one guess ends the phase. */
  Transition guess(GameState state, PlayerAction action, GameContext ctx) {
    if (!round.isImpostor(action.guestId())) {
      throw new ActionRejected(ErrorCode.FORBIDDEN, "Only the caught impostor can guess.");
    }
    if (guessed) {
      throw new ActionRejected(ErrorCode.ALREADY_SUBMITTED, "You already guessed.");
    }
    requireStage(Stage.GUESS);
    if (!(action.payload().get("text") instanceof String raw)) {
      throw invalid("payload.text", "must be text");
    }
    String text = GuessNormalizer.sanitize(raw);
    int length = GuessNormalizer.length(text);
    if (length < 1 || length > ImpostorFamily.GUESS_MAX_LENGTH) {
      throw invalid(
          "payload.text", "must be 1 to " + ImpostorFamily.GUESS_MAX_LENGTH + " characters");
    }
    String normalized = GuessNormalizer.normalize(text);
    if (normalized.isEmpty()) {
      throw invalid("payload.text", "must contain a letter or digit");
    }
    guessed = true;
    boolean correct =
        GuessNormalizer.isCorrect(normalized, GuessNormalizer.normalize(round.word().text()));
    return endRound(
        state, ctx, correct ? ImpostorOutcome.GUESSED : ImpostorOutcome.CAUGHT, text, List.of());
  }

  // ------------------------------------------------------------------ leaving

  /**
   * A player becomes {@code LEFT} ([DI-13]). The impostor ends the round at once; anyone else loses
   * their vote and the votes for them, and the early close is checked again. After the round has
   * ended nothing changes.
   */
  Transition onLeft(GameState state, UUID guest, GameContext ctx) {
    gone.add(guest);
    if (stage == Stage.ENDED) {
      return Transition.stay(state);
    }
    if (round.isImpostor(guest)) {
      votes.drop(guest);
      return endRound(state, ctx, ImpostorOutcome.IMPOSTOR_LEFT, null, List.of());
    }
    List<UUID> votedBefore = votes.voted(seated);
    votes.drop(guest);
    if (stage != Stage.VOTING) {
      return Transition.stay(state);
    }
    boolean changed = !votedBefore.equals(votes.voted(seated));
    List<OutboundEvent> progress = changed ? List.of(progress()) : List.of();
    if (ready(ctx.clock().instant())) {
      return closeVote(state, ctx, progress);
    }
    return Transition.stay(state).withEvents(progress.toArray(OutboundEvent[]::new));
  }

  // ------------------------------------------------------------------ the round end

  private Transition endRound(
      GameState state,
      GameContext ctx,
      ImpostorOutcome outcome,
      String guessText,
      List<OutboundEvent> before) {
    Instant now = ctx.clock().instant();
    stage = Stage.ENDED;
    UUID impostor = round.impostor();
    List<UUID> scoring =
        seated.stream().filter(g -> !g.equals(impostor) && !gone.contains(g)).toList();
    Map<UUID, UUID> finalVotes = votes.snapshot();
    List<ScoreDelta> deltas = ImpostorScoring.deltas(outcome, impostor, scoring, finalVotes);
    board.apply(deltas);
    board.record(impostor, outcome, finalVotes);
    Map<UUID, Integer> perPlayer = ImpostorScoring.perPlayer(seated, deltas);

    Map<String, Object> ended = new LinkedHashMap<>();
    ended.put("round", round.round());
    ended.put("outcome", outcome.name());
    ended.put("impostorGuestId", impostor);
    ended.put("category", round.category());
    ended.put("word", round.word().text());
    ended.put("decoyWord", decoyWord);
    ended.put("votes", finalVotes);
    ended.put("guess", guessText);
    ended.put("scoreDeltas", perPlayer);
    ended.put("scores", board.totals());
    roundEnd = Collections.unmodifiableMap(ended);

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("impostorGuestId", impostor);
    meta.put("word", round.word().text());
    meta.put("category", round.category());
    meta.put("decoyWord", decoyWord);
    meta.put("outcome", outcome.name());
    meta.put("accusedGuestId", voteClosed == null ? null : accused);
    RoundRecord record =
        new RoundRecord(round.round(), startedAt, now, outcome.name(), perPlayer, meta);

    List<OutboundEvent> closing = new ArrayList<>(before);
    closing.add(OutboundEvent.publicEvent(ImpostorFamily.ROUND_ENDED_EVENT, roundEnd));
    return Transition.stay(state)
        .withClosing(closing.toArray(OutboundEvent[]::new))
        .withPhase(
            new PhaseChange(
                ImpostorFamily.ROUND_END_PHASE, now.plus(ImpostorFamily.ROUND_END_TIME), data()))
        .withScores(deltas.toArray(ScoreDelta[]::new))
        .withRounds(record);
  }

  // ------------------------------------------------------------------ snapshot pieces

  /** Who has a vote in; empty until the voting phase has opened. */
  List<UUID> votedGuestIds() {
    return stage == Stage.PENDING ? List.of() : votes.voted(seated);
  }

  /** The viewer's own vote and nobody else's, or {@code null}. */
  UUID myVote(UUID viewer) {
    return stage == Stage.PENDING ? null : votes.voteOf(viewer);
  }

  List<Map<String, Object>> recentChat() {
    return chat.recent();
  }

  /** The published {@code IMPOSTOR_VOTE_CLOSED} payload, once the vote has closed. */
  Map<String, Object> voteClosed() {
    return voteClosed;
  }

  /** The published {@code IMPOSTOR_ROUND_ENDED} payload, once the round has ended. */
  Map<String, Object> roundEnd() {
    return roundEnd;
  }

  // ------------------------------------------------------------------ helpers

  private List<UUID> eligible() {
    return seated.stream().filter(g -> !gone.contains(g)).toList();
  }

  private OutboundEvent progress() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("phaseId", votePhaseId);
    payload.put("votedGuestIds", votes.voted(seated));
    return OutboundEvent.publicEvent(ImpostorFamily.VOTE_PROGRESS_EVENT, payload);
  }

  private Map<String, Object> data() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("round", round.round());
    data.put("totalRounds", totalRounds);
    return data;
  }

  private void requireStage(Stage required) {
    if (stage != required) {
      throw new ActionRejected(ErrorCode.INVALID_PHASE, "That action is not allowed now.");
    }
  }

  /** {@code targetGuestId} must be present: a uuid, or {@code null} to withdraw. */
  private static UUID parseTarget(Map<String, Object> payload) {
    if (!payload.containsKey("targetGuestId")) {
      throw invalid("payload.targetGuestId", "is required");
    }
    Object raw = payload.get("targetGuestId");
    if (raw == null) {
      return null;
    }
    if (raw instanceof String s) {
      try {
        return UUID.fromString(s);
      } catch (IllegalArgumentException e) {
        // fall through to the rejection below
      }
    }
    throw invalid("payload.targetGuestId", "must be a uuid or null");
  }

  private static ActionRejected invalid(String field, String message) {
    return new ActionRejected(ErrorCode.VALIDATION_FAILED, field + " " + message);
  }

  @Override
  public String toString() {
    return "ImpostorVoting[round=" + round.round() + ", stage=" + stage + "]";
  }
}
