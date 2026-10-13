package com.dinoparty.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Result of one module call, applied atomically by the engine: phase identity and deadlines first,
 * then scores and round records, then events (closing events, PHASE_STARTED, events).
 *
 * @param next the state to keep (may be the same instance)
 * @param closing events of the phase that is ending; published before PHASE_STARTED
 * @param events events published after PHASE_STARTED (for example private secrets of the new phase)
 */
public record Transition(
    GameState next,
    Optional<PhaseChange> phase,
    List<OutboundEvent> closing,
    List<OutboundEvent> events,
    List<DeadlineRequest> deadlines,
    List<ScoreDelta> scores,
    List<RoundRecord> rounds,
    boolean finished) {

  public Transition {
    closing = List.copyOf(closing);
    events = List.copyOf(events);
    deadlines = List.copyOf(deadlines);
    scores = List.copyOf(scores);
    rounds = List.copyOf(rounds);
  }

  public static Transition stay(GameState state) {
    return new Transition(
        state, Optional.empty(), List.of(), List.of(), List.of(), List.of(), List.of(), false);
  }

  public Transition withPhase(PhaseChange change) {
    return new Transition(
        next, Optional.of(change), closing, events, deadlines, scores, rounds, finished);
  }

  public Transition withClosing(OutboundEvent... more) {
    return new Transition(
        next, phase, concat(closing, more), events, deadlines, scores, rounds, finished);
  }

  public Transition withEvents(OutboundEvent... more) {
    return new Transition(
        next, phase, closing, concat(events, more), deadlines, scores, rounds, finished);
  }

  public Transition withDeadlines(DeadlineRequest... more) {
    return new Transition(
        next, phase, closing, events, concat(deadlines, more), scores, rounds, finished);
  }

  public Transition withScores(ScoreDelta... more) {
    return new Transition(
        next, phase, closing, events, deadlines, concat(scores, more), rounds, finished);
  }

  public Transition withRounds(RoundRecord... more) {
    return new Transition(
        next, phase, closing, events, deadlines, scores, concat(rounds, more), finished);
  }

  public Transition finish() {
    return new Transition(next, phase, closing, events, deadlines, scores, rounds, true);
  }

  @SafeVarargs
  private static <T> List<T> concat(List<T> base, T... more) {
    List<T> all = new ArrayList<>(base);
    all.addAll(List.of(more));
    return all;
  }
}
