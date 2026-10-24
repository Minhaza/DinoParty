package com.dinoparty.games.impostor;

import com.dinoparty.drawing.CanvasBuffer;
import com.dinoparty.drawing.CanvasLimits;
import com.dinoparty.engine.GameState;
import com.dinoparty.words.WordCatalogTypes.CategorizedWord;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable state of one Dino Impostor session. Only touched inside engine calls (room mailbox). It
 * holds the words and the impostor of every round, so it has no public accessors, is never
 * serialized, and its {@link #toString()} prints none of it.
 */
final class DinoImpostorState implements GameState {

  enum Phase {
    ROLE_REVEAL,
    DRAWING,
    VOTE,
    GUESS,
    ROUND_END
  }

  /** The stroke of the current turn ([DI-6]: one per turn), enough to close it as it stands. */
  static final class TurnStroke {
    final UUID id;
    final String color;
    final int size;
    int nextBatch = 1;
    boolean closed;

    TurnStroke(UUID id, String color, int size) {
      this.id = id;
      this.color = color;
      this.size = size;
    }
  }

  final DinoImpostorSettings settings;

  /** The session order (engine shuffle); also decides the session colours. */
  final List<UUID> order;

  /** Session colour per roster member ([DI-6], D56), uppercase {@code #RRGGBB}. */
  final Map<UUID, String> colors;

  /** One word per round, frozen at start ([DI-2]). */
  final List<CategorizedWord> words;

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

  /** This round's seats ([DI-5]); fixed for the round, a {@code LEFT} seat is skipped. */
  List<UUID> seats = List.of();

  /** 0-based turn of the round; -1 before the first turn. */
  int turn = -1;

  int totalTurns;
  UUID drawer;

  /** The shared canvas of this round ([DG-14] limits) and who drew each of its strokes. */
  CanvasBuffer canvas = new CanvasBuffer(CanvasLimits.DEFAULT);

  final Map<UUID, UUID> strokeAuthors = new HashMap<>();

  /** The drawer's stroke in the current turn, or null before they start one. */
  TurnStroke stroke;

  DinoImpostorState(
      DinoImpostorSettings settings,
      List<UUID> order,
      Map<UUID, String> colors,
      List<CategorizedWord> words,
      Map<UUID, String> nicknames,
      ImpostorRules rules,
      int totalRounds) {
    this.settings = settings;
    this.order = List.copyOf(order);
    this.colors = Map.copyOf(colors);
    this.words = List.copyOf(words);
    this.nicknames = Map.copyOf(nicknames);
    this.rules = rules;
    this.scoreboard = new ImpostorScoreboard(order);
    this.totalRounds = totalRounds;
  }

  boolean isLeft(UUID guest) {
    return left.contains(guest);
  }

  /** The player whose turn {@code t} is: seats go round {@code laps} times. */
  UUID seatOf(int t) {
    return seats.get(t % seats.size());
  }

  /** 1-based lap of turn {@code t}. */
  int lapOf(int t) {
    return t / seats.size() + 1;
  }

  @Override
  public String toString() {
    return "DinoImpostorState[round=" + round + ", phase=" + phase + "]";
  }
}
