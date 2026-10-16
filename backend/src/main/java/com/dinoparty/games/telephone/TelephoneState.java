package com.dinoparty.games.telephone;

import com.dinoparty.drawing.CanvasBuffer;
import com.dinoparty.drawing.CanvasLimits;
import com.dinoparty.engine.GameState;
import com.dinoparty.engine.SubmissionRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable state of one Telephone session. Only touched inside engine calls (room mailbox). It holds
 * the content of every chain, so it has no public accessors, is never serialized, and its {@link
 * #toString()} prints none of it (design §8.1).
 */
final class TelephoneState implements GameState {

  /** What a step asks for; also the phase name ([TP-4]). */
  enum Kind {
    PROMPT,
    DRAW,
    DESCRIBE;

    /** Step 0 is the prompt, odd steps draw, even steps after 0 describe. */
    static Kind of(int step) {
      return step == 0 ? PROMPT : step % 2 == 1 ? DRAW : DESCRIBE;
    }
  }

  /** How a cell got its entry ([TP-5]). */
  enum Fill {
    NONE,
    TIMEOUT,
    LEFT_PLAYER,
    AUTO_PROMPT
  }

  /**
   * One accepted entry. {@code strokes} is null for text entries and a frozen stroke list (maybe
   * empty) for drawings.
   */
  record Entry(
      UUID author,
      String text,
      List<Map<String, Object>> strokes,
      Fill fill,
      boolean placeholder,
      boolean timedOut) {

    boolean isDrawing() {
      return strokes != null;
    }
  }

  /** A holder's work in progress for the current step ([TP-9]); server-held, never echoed. */
  static final class Draft {
    String text = "";
    final CanvasBuffer canvas;

    Draft(boolean drawing) {
      this.canvas = drawing ? new CanvasBuffer(CanvasLimits.DEFAULT) : null;
    }

    boolean isEmpty() {
      return canvas != null ? canvas.strokeCount() == 0 : text.isEmpty();
    }
  }

  // ---- session (fixed at start)
  final TelephoneSettings settings;
  final List<UUID> order;

  /** Roster nicknames at start, so entries of players who left stay attributed ([TP-6]). */
  final Map<UUID, String> nicknames;

  final Map<UUID, Integer> index = new HashMap<>();
  final int totalSteps;
  final List<String> prompts;
  final Set<String> usedPrompts = new HashSet<>();

  /** {@code chains.get(c)} holds entries 0..k of chain c, in order. */
  final List<List<Entry>> chains = new ArrayList<>();

  final Set<UUID> left = new HashSet<>();

  /** Roster members whose connection is currently down (within the grace). */
  final Set<UUID> disconnected = new HashSet<>();

  /** Entries created by the running engine call; handed to the engine as submission records. */
  final List<SubmissionRecord> unrecorded = new ArrayList<>();

  // ---- current step
  int step = -1;
  Kind kind;

  /** The engine's id of the running step phase (the {@code nextPhaseId} it was started with). */
  UUID phaseId;

  Instant stepStartedAt;
  Instant deadline;
  final Map<UUID, Draft> drafts = new HashMap<>();
  final Set<UUID> submitted = new LinkedHashSet<>();
  final List<UUID> timedOutGuests = new ArrayList<>();
  final List<UUID> leftGuests = new ArrayList<>();

  // ---- reveal ([TP-6]); positions are linear in reveal order: chain * totalSteps + entry
  boolean revealing;
  int cursor;
  int highWater;
  Instant autoAdvanceAt;

  /** True once the game finished (the last reveal step, or the engine ended it). */
  boolean over;

  TelephoneState(
      TelephoneSettings settings,
      List<UUID> order,
      Map<UUID, String> nicknames,
      int totalSteps,
      List<String> prompts) {
    this.settings = settings;
    this.order = List.copyOf(order);
    this.nicknames = Map.copyOf(nicknames);
    this.totalSteps = totalSteps;
    this.prompts = prompts;
    for (int i = 0; i < order.size(); i++) {
      index.put(order.get(i), i);
      chains.add(new ArrayList<>());
    }
  }

  int n() {
    return order.size();
  }

  boolean isLeft(UUID guest) {
    return left.contains(guest);
  }

  /** The chain the guest holds in the current step. */
  int chainOf(UUID guest) {
    return ChainAllocator.chainHeldBy(index.get(guest), step, n());
  }

  /** The holder of the chain in the current step. */
  UUID holderOf(int chain) {
    return order.get(ChainAllocator.holder(chain, step, n()));
  }

  /** Holders that still count for the early end: everybody not LEFT ([TP-4]). */
  List<UUID> eligible() {
    return order.stream().filter(g -> !left.contains(g)).toList();
  }

  boolean allSubmitted() {
    return submitted.containsAll(eligible());
  }

  int lastPosition() {
    return n() * totalSteps - 1;
  }

  Entry entryAt(int position) {
    return chains.get(position / totalSteps).get(position % totalSteps);
  }

  @Override
  public String toString() {
    return "TelephoneState[step=" + step + "/" + totalSteps + ", reveal=" + revealing + "]";
  }
}
