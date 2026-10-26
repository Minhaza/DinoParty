package com.dinoparty.games.impostor;

import com.dinoparty.drawing.CanvasBuffer;
import com.dinoparty.engine.GameState;
import com.dinoparty.words.WordCatalogTypes.CategorizedWord;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable state of one Sketchy Saurus session. Only touched inside engine calls (room mailbox). It
 * holds the words, the decoys, the impostor of every round and every private draft, so it has no
 * public accessors, is never serialized, and its {@link #toString()} prints none of it.
 */
final class SketchySaurusState implements GameState {

  enum Phase {
    ROLE_REVEAL,
    SKETCH,
    GALLERY,
    GUESS,
    ROUND_END
  }

  final SketchySaurusSettings settings;

  /** The session order (engine shuffle); the order of the gallery. */
  final List<UUID> order;

  /** One word per round, frozen at start ([DI-2]). */
  final List<CategorizedWord> words;

  /** The decoy of each round, same index as {@link #words}; empty when the impostor knows. */
  final List<CategorizedWord> decoys;

  final int totalRounds;

  /** Nicknames of the whole frozen roster, kept for members who left (G2). */
  final Map<UUID, String> nicknames;

  /** What differs from the other impostor game in the shared voting flow. */
  final ImpostorRules rules;

  /** Totals and results counters of the session ([DI-12], api-contracts §3.11 "Results"). */
  final ImpostorScoreboard scoreboard;

  /** Impostor rounds so far per player ([DI-3]). */
  final Map<UUID, Integer> impostorCounts = new HashMap<>();

  final Set<UUID> left = new HashSet<>();

  /** 1-based; 0 before the first round. */
  int round;

  Phase phase;
  ImpostorRound current;

  /** Voting, guess and round end of {@link #current}; replaced when the next round starts. */
  ImpostorVoting voting;

  /** The players seated this round: not {@code LEFT} when it started, in session order. */
  List<UUID> seated = List.of();

  /** Id of the {@code SKETCH} phase, for {@code SKETCHY_PROGRESS}. */
  UUID sketchPhaseId;

  /**
   * Each player's private drawing during {@code SKETCH} ([SS-2]); only players not {@code LEFT}.
   * Dropped when the phase ends, the gallery holds what is left.
   */
  final Map<UUID, CanvasBuffer> drafts = new LinkedHashMap<>();

  final Set<UUID> submitted = new HashSet<>();

  /** One entry per drawing, in session order, from {@code GALLERY} on; {@code null} before. */
  List<Map<String, Object>> gallery;

  SketchySaurusState(
      SketchySaurusSettings settings,
      List<UUID> order,
      List<CategorizedWord> words,
      List<CategorizedWord> decoys,
      Map<UUID, String> nicknames,
      ImpostorRules rules,
      int totalRounds) {
    this.settings = settings;
    this.order = List.copyOf(order);
    this.words = List.copyOf(words);
    this.decoys = List.copyOf(decoys);
    this.nicknames = Map.copyOf(nicknames);
    this.rules = rules;
    this.scoreboard = new ImpostorScoreboard(order);
    this.totalRounds = totalRounds;
  }

  boolean isLeft(UUID guest) {
    return left.contains(guest);
  }

  /** [SS-2]: every player still seated has submitted. */
  boolean allSubmitted() {
    return !drafts.isEmpty() && submitted.containsAll(drafts.keySet());
  }

  @Override
  public String toString() {
    return "SketchySaurusState[round=" + round + ", phase=" + phase + "]";
  }
}
