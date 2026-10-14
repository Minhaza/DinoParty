package com.dinoparty.drawing;

/**
 * Budgets of one canvas ([DG-14], D28). The live-point budget is what keeps a maximal snapshot
 * under 256 KiB with {@code [[x,y]]} pairs (docs/draw-guess-design.md §8).
 */
public record CanvasLimits(
    int maxPointsPerBatch, int maxPointsPerStroke, int maxStrokes, int maxPoints) {

  /** Integer wire coordinates are 0..MAX_COORDINATE on both axes. */
  public static final int MAX_COORDINATE = 10_000;

  public static final CanvasLimits DEFAULT = new CanvasLimits(200, 2_000, 300, 12_000);
}
