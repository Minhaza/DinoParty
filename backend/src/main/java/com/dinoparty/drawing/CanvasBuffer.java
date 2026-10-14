package com.dinoparty.drawing;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The live strokes of one canvas with its budgets ([DG-14]). Every check runs against the whole
 * batch before anything is mutated, so a rejected batch leaves the buffer exactly as it was and
 * nothing is ever truncated. Not thread safe: it lives in game state, used inside the room mailbox.
 */
public final class CanvasBuffer {

  private static final class Stroke {
    final UUID id;
    final Tool tool;
    final String color;
    final int size;
    int[] coords = new int[16];
    int length;
    int nextBatch;
    boolean closed;

    Stroke(StrokeBatch first) {
      this.id = first.strokeId();
      this.tool = first.tool();
      this.color = first.color();
      this.size = first.size();
    }

    int points() {
      return length / 2;
    }

    void append(int[] more) {
      if (length + more.length > coords.length) {
        coords = Arrays.copyOf(coords, Math.max(coords.length * 2, length + more.length));
      }
      System.arraycopy(more, 0, coords, length, more.length);
      length += more.length;
    }

    boolean sameStyle(StrokeBatch batch) {
      return tool == batch.tool() && color.equals(batch.color()) && size == batch.size();
    }
  }

  private final CanvasLimits limits;

  /** Drawing order; only the last stroke can be open. */
  private final LinkedHashMap<UUID, Stroke> strokes = new LinkedHashMap<>();

  private int livePoints;

  public CanvasBuffer(CanvasLimits limits) {
    this.limits = limits;
  }

  public CanvasLimits limits() {
    return limits;
  }

  /**
   * Appends a batch. A new stroke id starts at batch 0 and implicitly closes the open stroke; a
   * known id must be the open stroke, continue with the next batch number and repeat its style.
   *
   * @throws DomainException {@code INVALID_ACTION} for ordering/style violations, {@code
   *     PAYLOAD_TOO_LARGE} when a budget would be exceeded; the buffer is unchanged in both cases
   */
  public void append(StrokeBatch batch) {
    int added = batch.pointCount();
    Stroke existing = strokes.get(batch.strokeId());
    if (existing != null) {
      if (existing.closed) {
        throw invalid("That stroke is already finished.");
      }
      if (batch.batchNo() != existing.nextBatch) {
        throw invalid("That stroke batch is out of order.");
      }
      if (!existing.sameStyle(batch)) {
        throw invalid("A stroke cannot change its tool, colour or size.");
      }
      if (existing.points() + added > limits.maxPointsPerStroke()
          || livePoints + added > limits.maxPoints()) {
        throw full();
      }
      existing.append(batch.coords());
      existing.nextBatch++;
      existing.closed = batch.finalBatch();
      livePoints += added;
      return;
    }
    if (batch.batchNo() != 0) {
      throw invalid("That stroke batch is out of order.");
    }
    if (strokes.size() + 1 > limits.maxStrokes()
        || added > limits.maxPointsPerStroke()
        || livePoints + added > limits.maxPoints()) {
      throw full();
    }
    Map.Entry<UUID, Stroke> last = strokes.lastEntry();
    if (last != null) {
      last.getValue().closed = true;
    }
    Stroke stroke = new Stroke(batch);
    stroke.append(batch.coords());
    stroke.nextBatch = 1;
    stroke.closed = batch.finalBatch();
    strokes.put(stroke.id, stroke);
    livePoints += added;
  }

  /** Removes the last stroke (open or closed); empty when there was nothing to undo. */
  public Optional<UUID> undo() {
    Map.Entry<UUID, Stroke> last = strokes.pollLastEntry();
    if (last == null) {
      return Optional.empty();
    }
    livePoints -= last.getValue().points();
    return Optional.of(last.getKey());
  }

  /** Removes every stroke; false when the canvas was already empty. */
  public boolean clear() {
    if (strokes.isEmpty()) {
      return false;
    }
    strokes.clear();
    livePoints = 0;
    return true;
  }

  public int livePoints() {
    return livePoints;
  }

  public int strokeCount() {
    return strokes.size();
  }

  /** Snapshot form: every live stroke in drawing order with its {@code closed} flag. */
  public List<Map<String, Object>> snapshot() {
    List<Map<String, Object>> out = new ArrayList<>(strokes.size());
    for (Stroke s : strokes.values()) {
      List<int[]> points = new ArrayList<>(s.points());
      for (int i = 0; i < s.length; i += 2) {
        points.add(new int[] {s.coords[i], s.coords[i + 1]});
      }
      Map<String, Object> stroke = new LinkedHashMap<>();
      stroke.put("strokeId", s.id);
      stroke.put("tool", s.tool.name());
      stroke.put("color", s.color);
      stroke.put("size", s.size);
      stroke.put("points", points);
      stroke.put("closed", s.closed);
      out.add(stroke);
    }
    return out;
  }

  /** Usage for the snapshot's {@code canvasBudget}. */
  public Map<String, Object> budget() {
    Map<String, Object> budget = new LinkedHashMap<>();
    budget.put("points", livePoints);
    budget.put("maxPoints", limits.maxPoints());
    budget.put("strokes", strokes.size());
    budget.put("maxStrokes", limits.maxStrokes());
    return budget;
  }

  private static DomainException invalid(String message) {
    return new DomainException(ErrorCode.INVALID_ACTION, message);
  }

  private static DomainException full() {
    return new DomainException(ErrorCode.PAYLOAD_TOO_LARGE, "The canvas is full.");
  }
}
