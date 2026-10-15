package com.dinoparty.games.telephone;

import com.dinoparty.drawing.CanvasBuffer;
import com.dinoparty.drawing.CanvasLimits;
import com.dinoparty.engine.GameState;
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
  final Map<UUID, Integer> index = new HashMap<>();
  final int totalSteps;
  final List<String> prompts;
  final Set<String> usedPrompts = new HashSet<>();

  /** {@code chains.get(c)} holds entries 0..k of chain c, in order. */
  final List<List<Entry>> chains = new ArrayList<>();

  final Set<UUID> left = new HashSet<>();

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

  /** True once the last step ended; there is no reveal yet (P6-03). */
  boolean over;

  TelephoneState(
      TelephoneSettings settings, List<UUID> order, int totalSteps, List<String> prompts) {
    this.settings = settings;
    this.order = List.copyOf(order);
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

  @Override
  public String toString() {
    return "TelephoneState[step=" + step + "/" + totalSteps + "]";
  }
}
