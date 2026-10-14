package com.dinoparty.drawing;

import com.dinoparty.common.DomainException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One validated {@code DRAW_STROKE} batch (api-contracts §3.9). Points are kept as a flat {@code
 * x0,y0,x1,y1,…} array; the batch is never mutated after parsing.
 */
public record StrokeBatch(
    UUID strokeId,
    int batchNo,
    Tool tool,
    String color,
    int size,
    int[] coords,
    boolean finalBatch) {

  private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
  private static final Pattern UUID_TEXT =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  public int pointCount() {
    return coords.length / 2;
  }

  /** The points as {@code [x,y]} pairs, the wire format of events and snapshots. */
  public List<int[]> points() {
    List<int[]> pairs = new ArrayList<>(pointCount());
    for (int i = 0; i < coords.length; i += 2) {
      pairs.add(new int[] {coords[i], coords[i + 1]});
    }
    return pairs;
  }

  /**
   * Validates the schema of a raw payload; every failure is {@code VALIDATION_FAILED} with an
   * authored message that never echoes client data. Ordering/budget rules are the buffer's.
   */
  public static StrokeBatch parse(Map<String, Object> payload, CanvasLimits limits) {
    UUID strokeId = uuid(payload.get("strokeId"));
    Integer batchNo = integer(payload.get("batchNo"));
    if (batchNo == null || batchNo < 0) {
      throw DomainException.invalid("payload.batchNo", "must be a whole number of at least 0");
    }
    Tool tool = tool(payload.get("tool"));
    if (!(payload.get("color") instanceof String color) || !COLOR.matcher(color).matches()) {
      throw DomainException.invalid("payload.color", "must be a #RRGGBB colour");
    }
    Integer size = integer(payload.get("size"));
    if (size == null || size < 1 || size > 4) {
      throw DomainException.invalid("payload.size", "must be a whole number from 1 to 4");
    }
    if (!(payload.get("final") instanceof Boolean finalBatch)) {
      throw DomainException.invalid("payload.final", "must be true or false");
    }
    int[] coords = coords(payload.get("points"), limits);
    if (coords.length == 0 && (batchNo == 0 || !finalBatch)) {
      throw DomainException.invalid(
          "payload.points", "may be empty only in a closing batch after the first");
    }
    // Stored and republished exactly as received, so every client replays the same stroke.
    return new StrokeBatch(strokeId, batchNo, tool, color, size, coords, finalBatch);
  }

  private static UUID uuid(Object value) {
    if (value instanceof String text && UUID_TEXT.matcher(text).matches()) {
      return UUID.fromString(text);
    }
    throw DomainException.invalid("payload.strokeId", "must be a UUID");
  }

  private static Tool tool(Object value) {
    if ("PENCIL".equals(value)) {
      return Tool.PENCIL;
    }
    if ("ERASER".equals(value)) {
      return Tool.ERASER;
    }
    throw DomainException.invalid("payload.tool", "must be PENCIL or ERASER");
  }

  private static int[] coords(Object value, CanvasLimits limits) {
    if (!(value instanceof List<?> list)) {
      throw DomainException.invalid("payload.points", "must be a list of [x,y] pairs");
    }
    if (list.size() > limits.maxPointsPerBatch()) {
      throw DomainException.invalid(
          "payload.points", "must have at most " + limits.maxPointsPerBatch() + " points");
    }
    int[] coords = new int[list.size() * 2];
    for (int i = 0; i < list.size(); i++) {
      if (!(list.get(i) instanceof List<?> pair) || pair.size() != 2) {
        throw DomainException.invalid("payload.points", "must be a list of [x,y] pairs");
      }
      Integer x = integer(pair.get(0));
      Integer y = integer(pair.get(1));
      if (x == null || y == null || !inRange(x) || !inRange(y)) {
        throw DomainException.invalid(
            "payload.points",
            "coordinates must be whole numbers from 0 to " + CanvasLimits.MAX_COORDINATE);
      }
      coords[2 * i] = x;
      coords[2 * i + 1] = y;
    }
    return coords;
  }

  private static boolean inRange(int v) {
    return v >= 0 && v <= CanvasLimits.MAX_COORDINATE;
  }

  /** JSON integers arrive as Integer (or Long when huge); fractions and strings are refused. */
  private static Integer integer(Object value) {
    return value instanceof Integer i ? i : null;
  }
}
