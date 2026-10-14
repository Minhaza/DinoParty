package com.dinoparty.games.drawguess;

import com.dinoparty.drawing.CanvasBuffer;
import com.dinoparty.drawing.CanvasLimits;
import com.dinoparty.engine.GameState;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable state of one Draw & Guess session. Only touched inside engine calls (room mailbox). It
 * holds secrets (word, choices), so it has no public accessors, is never serialized, and its {@link
 * #toString()} deliberately prints no word (design §13 H1).
 */
final class DrawGuessState implements GameState {

  enum Phase {
    WORD_CHOICE,
    DRAWING,
    ROUND_END
  }

  /** One chat line; {@code audience} is null for public lines. */
  record ChatLine(UUID guestId, String text, String scope, String ts, Set<UUID> audience) {}

  /** Per-player counters for the standings detail. */
  static final class Stats {
    int correctGuesses;
    int guesserPoints;
    int drawerPoints;
    int roundsDrawn;
  }

  static final int MAX_CHAT = 30;

  /** Bound on remembered wrong guesses per round; beyond it duplicates are simply rebroadcast. */
  static final int MAX_SEEN_WRONG = 1_000;

  // ---- session
  final DrawGuessSettings settings;
  final List<UUID> turns;
  final Set<String> usedWords = new HashSet<>();
  final Set<UUID> left = new HashSet<>();
  final Map<UUID, Integer> totals = new LinkedHashMap<>();
  final Map<UUID, Stats> stats = new LinkedHashMap<>();

  // ---- current round
  Phase phase;
  int turnIndex = -1;
  int round;
  int totalRounds;
  UUID drawer;
  Instant roundStartedAt;
  List<String> wordChoices;
  String word;
  String normalizedWord;
  boolean autoPicked;
  String hint;
  Instant drawingDeadline;
  final Set<UUID> eligible = new LinkedHashSet<>();
  final List<UUID> solvers = new ArrayList<>();
  final Set<String> seenWrongGuesses = new HashSet<>();
  final ArrayDeque<ChatLine> chat = new ArrayDeque<>();
  final CanvasBuffer canvas = new CanvasBuffer(CanvasLimits.DEFAULT);
  final Map<UUID, Integer> roundDeltas = new HashMap<>();
  String endReason;

  DrawGuessState(DrawGuessSettings settings, List<UUID> order) {
    this.settings = settings;
    List<UUID> list = new ArrayList<>(order.size() * settings.cycles());
    for (int c = 0; c < settings.cycles(); c++) {
      list.addAll(order);
    }
    this.turns = List.copyOf(list);
    for (UUID g : order) {
      totals.put(g, 0);
      stats.put(g, new Stats());
    }
  }

  /** Clears everything that belongs to one round; the canvas is discarded here ([DG-14]). */
  void resetRound() {
    wordChoices = null;
    word = null;
    normalizedWord = null;
    autoPicked = false;
    hint = null;
    drawingDeadline = null;
    eligible.clear();
    solvers.clear();
    seenWrongGuesses.clear();
    chat.clear();
    canvas.clear();
    roundDeltas.clear();
    endReason = null;
  }

  void addChat(ChatLine line) {
    chat.addLast(line);
    while (chat.size() > MAX_CHAT) {
      chat.removeFirst();
    }
  }

  /** Records a delta for the module's mirror of the ledger, the round summary and the stats. */
  void award(UUID guest, int points) {
    totals.merge(guest, points, Math::addExact);
    roundDeltas.merge(guest, points, Math::addExact);
  }

  boolean isLeft(UUID guest) {
    return left.contains(guest);
  }

  @Override
  public String toString() {
    return "DrawGuessState[phase=" + phase + ", round=" + round + "/" + totalRounds + "]";
  }
}
