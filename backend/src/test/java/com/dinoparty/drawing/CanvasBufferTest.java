package com.dinoparty.drawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** [DG-14] canvas budgets, ordering and undo/clear; every limit at its boundary. */
class CanvasBufferTest {

  /** Small limits so boundaries are cheap to reach. */
  private static final CanvasLimits SMALL = new CanvasLimits(5, 8, 3, 12);

  private final CanvasBuffer canvas = new CanvasBuffer(SMALL);

  static StrokeBatch batch(UUID id, int no, int points, boolean last) {
    int[] coords = new int[points * 2];
    for (int i = 0; i < coords.length; i++) {
      coords[i] = i;
    }
    return new StrokeBatch(id, no, Tool.PENCIL, "#112233", 2, coords, last);
  }

  private static ErrorCode code(Runnable r) {
    try {
      r.run();
    } catch (DomainException e) {
      return e.code();
    }
    throw new AssertionError("expected a rejection");
  }

  private void assertRejectedUnchanged(StrokeBatch b, ErrorCode expected) {
    List<Map<String, Object>> before = canvas.snapshot();
    int points = canvas.livePoints();
    int strokes = canvas.strokeCount();
    assertThat(code(() -> canvas.append(b))).isEqualTo(expected);
    assertThat(canvas.livePoints()).isEqualTo(points);
    assertThat(canvas.strokeCount()).isEqualTo(strokes);
    assertThat(canvas.snapshot()).usingRecursiveComparison().isEqualTo(before);
  }

  @Test
  void DG14_aStrokeIsBuiltFromOrderedBatchesAndClosedByFinal() {
    UUID id = UUID.randomUUID();
    canvas.append(batch(id, 0, 2, false));
    canvas.append(batch(id, 1, 3, false));
    canvas.append(batch(id, 2, 0, true));

    assertThat(canvas.livePoints()).isEqualTo(5);
    Map<String, Object> stroke = canvas.snapshot().getFirst();
    assertThat(stroke.get("strokeId")).isEqualTo(id);
    assertThat((List<?>) stroke.get("points")).hasSize(5);
    assertThat(stroke.get("closed")).isEqualTo(true);
  }

  @Test
  void DG14_outOfOrderGappedOrRestyledBatchesAreInvalidAndChangeNothing() {
    UUID id = UUID.randomUUID();
    canvas.append(batch(id, 0, 1, false));

    assertRejectedUnchanged(batch(id, 2, 1, false), ErrorCode.INVALID_ACTION); // gap
    assertRejectedUnchanged(batch(id, 0, 1, false), ErrorCode.INVALID_ACTION); // repeat
    assertRejectedUnchanged(
        new StrokeBatch(id, 1, Tool.ERASER, "#112233", 2, new int[] {1, 1}, false),
        ErrorCode.INVALID_ACTION);
    assertRejectedUnchanged(
        new StrokeBatch(id, 1, Tool.PENCIL, "#000000", 2, new int[] {1, 1}, false),
        ErrorCode.INVALID_ACTION);
    assertRejectedUnchanged(
        new StrokeBatch(id, 1, Tool.PENCIL, "#112233", 3, new int[] {1, 1}, false),
        ErrorCode.INVALID_ACTION);
    assertRejectedUnchanged(batch(UUID.randomUUID(), 1, 1, false), ErrorCode.INVALID_ACTION);
  }

  @Test
  void DG14_aFinishedStrokeAcceptsNoMoreBatches() {
    UUID id = UUID.randomUUID();
    canvas.append(batch(id, 0, 1, true));

    assertRejectedUnchanged(batch(id, 1, 1, false), ErrorCode.INVALID_ACTION);
  }

  @Test
  void DG14_aNewStrokeIdImplicitlyClosesTheOpenStroke() {
    UUID first = UUID.randomUUID();
    canvas.append(batch(first, 0, 1, false));
    canvas.append(batch(UUID.randomUUID(), 0, 1, false));

    assertThat(canvas.snapshot().get(0).get("closed")).isEqualTo(true);
    assertThat(canvas.snapshot().get(1).get("closed")).isEqualTo(false);
    assertRejectedUnchanged(batch(first, 1, 1, false), ErrorCode.INVALID_ACTION);
  }

  @Test
  void DG14_pointsPerStrokeLimitAtTheBoundary() {
    UUID id = UUID.randomUUID();
    canvas.append(batch(id, 0, 5, false));
    canvas.append(batch(id, 1, 3, false)); // exactly 8 = limit

    assertRejectedUnchanged(batch(id, 2, 1, false), ErrorCode.PAYLOAD_TOO_LARGE);
    canvas.append(batch(id, 2, 0, true)); // a closing batch without points always fits
    assertThat(canvas.snapshot().getFirst().get("closed")).isEqualTo(true);
  }

  @Test
  void DG14_livePointsLimitRejectsTheWholeBatchNeverTruncates() {
    canvas.append(batch(UUID.randomUUID(), 0, 5, true));
    canvas.append(batch(UUID.randomUUID(), 0, 5, true));
    UUID third = UUID.randomUUID();
    canvas.append(batch(third, 0, 2, false)); // 12 = limit

    assertRejectedUnchanged(batch(third, 1, 1, false), ErrorCode.PAYLOAD_TOO_LARGE);
    assertThat(canvas.livePoints()).isEqualTo(12);
  }

  @Test
  void DG14_strokeCountLimitAtTheBoundary() {
    for (int i = 0; i < 3; i++) {
      canvas.append(batch(UUID.randomUUID(), 0, 1, true));
    }
    assertRejectedUnchanged(batch(UUID.randomUUID(), 0, 1, true), ErrorCode.PAYLOAD_TOO_LARGE);
  }

  @Test
  void DG14_undoAndClearRefundTheBudgetExactly() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    canvas.append(batch(a, 0, 5, true));
    canvas.append(batch(b, 0, 4, false));

    assertThat(canvas.undo()).contains(b); // open strokes can be undone too
    assertThat(canvas.livePoints()).isEqualTo(5);
    assertThat(canvas.strokeCount()).isEqualTo(1);
    // A batch for the undone stroke is now out of order (its batch 0 is gone).
    assertRejectedUnchanged(batch(b, 1, 1, false), ErrorCode.INVALID_ACTION);

    assertThat(canvas.clear()).isTrue();
    assertThat(canvas.livePoints()).isZero();
    assertThat(canvas.strokeCount()).isZero();
    assertThat(canvas.budget())
        .containsEntry("points", 0)
        .containsEntry("maxPoints", 12)
        .containsEntry("strokes", 0)
        .containsEntry("maxStrokes", 3);
  }

  @Test
  void DG14_undoAndClearOnAnEmptyCanvasAreNoOps() {
    assertThat(canvas.undo()).isEmpty();
    assertThat(canvas.clear()).isFalse();
  }

  @Test
  void theDefaultLimitsAreTheContractValues() {
    assertThat(CanvasLimits.DEFAULT).isEqualTo(new CanvasLimits(200, 2_000, 300, 12_000));
  }

  @Test
  void theDefaultBudgetCanBeFilledExactly() {
    CanvasBuffer full = new CanvasBuffer(CanvasLimits.DEFAULT);
    List<UUID> ids = new ArrayList<>();
    for (int s = 0; s < 300; s++) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      full.append(batch(id, 0, 40, true)); // 300 × 40 = 12 000
    }
    assertThat(full.livePoints()).isEqualTo(12_000);
    assertThatThrownBy(() -> full.append(batch(UUID.randomUUID(), 0, 1, true)))
        .isInstanceOf(DomainException.class);
    assertThat(full.undo()).contains(ids.getLast());
  }
}
