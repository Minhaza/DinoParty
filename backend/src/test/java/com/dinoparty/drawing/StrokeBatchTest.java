package com.dinoparty.drawing;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Schema validation of DRAW_STROKE payloads (api-contracts §3.9). */
class StrokeBatchTest {

  private static Map<String, Object> payload() {
    Map<String, Object> p = new HashMap<>();
    p.put("strokeId", UUID.randomUUID().toString());
    p.put("batchNo", 0);
    p.put("tool", "PENCIL");
    p.put("color", "#aabbCC");
    p.put("size", 4);
    p.put("points", List.of(List.of(0, 0), List.of(10_000, 10_000)));
    p.put("final", false);
    return p;
  }

  private static DomainException rejected(Map<String, Object> p) {
    try {
      StrokeBatch.parse(p, CanvasLimits.DEFAULT);
    } catch (DomainException e) {
      return e;
    }
    throw new AssertionError("expected a rejection");
  }

  @Test
  void aValidBatchIsParsedUnchanged() {
    Map<String, Object> p = payload();
    StrokeBatch b = StrokeBatch.parse(p, CanvasLimits.DEFAULT);

    assertThat(b.strokeId().toString()).isEqualTo(p.get("strokeId"));
    assertThat(b.color()).isEqualTo("#aabbCC");
    assertThat(b.size()).isEqualTo(4);
    assertThat(b.pointCount()).isEqualTo(2);
    assertThat(b.points()).containsExactly(new int[] {0, 0}, new int[] {10_000, 10_000});
  }

  @Test
  void everyFieldIsValidated() {
    List<Map<String, Object>> bad = new ArrayList<>();
    for (Object[] change :
        new Object[][] {
          {"strokeId", "not-a-uuid"},
          {"strokeId", null},
          {"batchNo", -1},
          {"batchNo", 1.5},
          {"tool", "BRUSH"},
          {"color", "red"},
          {"color", "#12345"},
          {"size", 0},
          {"size", 5},
          {"final", "yes"},
          {"points", "nope"},
          {"points", List.of(List.of(1))},
          {"points", List.of(List.of(-1, 0))},
          {"points", List.of(List.of(0, 10_001))},
          {"points", List.of(List.of(0.5, 1))},
          {"points", List.of()}, // batch 0 needs a point
        }) {
      Map<String, Object> p = payload();
      p.put((String) change[0], change[1]);
      bad.add(p);
    }
    Map<String, Object> notClosing = payload();
    notClosing.put("batchNo", 1);
    notClosing.put("points", List.of());
    bad.add(notClosing);
    Map<String, Object> tooMany = payload();
    List<List<Integer>> points = new ArrayList<>();
    for (int i = 0; i < 201; i++) {
      points.add(List.of(i, i));
    }
    tooMany.put("points", points);
    bad.add(tooMany);

    for (Map<String, Object> p : bad) {
      DomainException e = rejected(p);
      assertThat(e.code()).as(p.toString()).isEqualTo(ErrorCode.VALIDATION_FAILED);
      assertThat(e.getMessage()).doesNotContain("not-a-uuid").doesNotContain("BRUSH");
    }
  }

  @Test
  void twoHundredPointsAndAnEmptyClosingBatchAreAllowed() {
    Map<String, Object> p = payload();
    List<List<Integer>> points = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      points.add(List.of(i, i));
    }
    p.put("points", points);
    assertThat(StrokeBatch.parse(p, CanvasLimits.DEFAULT).pointCount()).isEqualTo(200);

    Map<String, Object> closing = payload();
    closing.put("batchNo", 3);
    closing.put("points", List.of());
    closing.put("final", true);
    assertThat(StrokeBatch.parse(closing, CanvasLimits.DEFAULT).pointCount()).isZero();
  }
}
