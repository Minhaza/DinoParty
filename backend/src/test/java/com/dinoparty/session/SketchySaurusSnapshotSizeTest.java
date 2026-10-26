package com.dinoparty.session;

import static com.dinoparty.session.SessionFixture.CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.games.impostor.SketchySaurusModule;
import com.dinoparty.words.TestWords;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * R36 / D64: ten drawings at the per-drawing budget (1 000 live points, 60 strokes) with the widest
 * coordinates and a full chat serialize, as one snapshot and as the {@code SKETCHY_GALLERY_OPENED}
 * event, to less than the engine's 256 KiB. The budget is lowered, never the bound.
 */
class SketchySaurusSnapshotSizeTest {

  private static final int LIMIT = 262_144;

  private final SessionFixture f =
      new SessionFixture(new SketchySaurusModule(TestWords.service(), new SeededRandomSource(9)));
  private final SketchyDriver s = new SketchyDriver(f);
  private final ImpostorDriver d = s.d;

  /** One closed stroke of {@code points} points, every coordinate five digits wide. */
  private static Map<String, Object> stroke(int points) {
    List<List<Integer>> pts = new ArrayList<>();
    for (int i = 0; i < points; i++) {
      pts.add(List.of(10_000 - i % 10, 10_000));
    }
    Map<String, Object> p = new HashMap<>();
    p.put("strokeId", UUID.randomUUID().toString());
    p.put("batchNo", 0);
    p.put("tool", "PENCIL");
    p.put("color", "#FFFFFF");
    p.put("size", 4);
    p.put("points", pts);
    p.put("final", true);
    return p;
  }

  private static int bytes(Object value) {
    return JsonMapper.builder()
        .build()
        .writeValueAsString(value)
        .getBytes(StandardCharsets.UTF_8)
        .length;
  }

  /** 60 strokes, 1 000 points: the largest drawing the budget allows. */
  private void drawFull(UUID guest) {
    for (int i = 0; i < 60; i++) {
      d.send(guest, "SKETCHY_DRAW_STROKE", stroke(i < 40 ? 17 : 16));
    }
  }

  @Test
  void aGalleryOfTenFullDrawingsWithAFullChatStaysUnderTheSnapshotBound() {
    d.start(10, Map.of("rounds", 3));
    s.toSketch();
    for (UUID p : d.players) {
      drawFull(p);
    }
    Map<String, Object> draft = (Map<String, Object>) s.snapshot(d.players.getFirst()).get("draft");
    assertThat(draft.get("canvasBudget"))
        .isEqualTo(Map.of("points", 1000, "maxPoints", 1000, "strokes", 60, "maxStrokes", 60));
    int draftBytes = bytes(s.snapshot(d.players.getFirst()));
    s.everyoneSubmits(0);
    assertThat(d.phase()).isEqualTo("GALLERY");

    int eventBytes = bytes(d.lastEvent("SKETCHY_GALLERY_OPENED"));
    assertThat(eventBytes).as("SKETCHY_GALLERY_OPENED bytes").isLessThan(LIMIT);
    int emptyChat = bytes(s.snapshot(d.players.get(3)));
    assertThat(emptyChat).as("GALLERY snapshot bytes without chat").isLessThan(LIMIT);

    // 30 chat lines of 120 four-byte letters, the widest the rate limit and the cap allow.
    String longLine = "𐐀".repeat(120);
    for (int i = 0; i < 30; i++) {
      d.chat(d.players.get(i % 10), longLine);
      if (i % 10 == 9) {
        d.advance(Duration.ofSeconds(10));
      }
    }
    Map<String, Object> gallery = s.snapshot(d.players.get(3));
    assertThat((List<?>) gallery.get("recentChat")).hasSize(30);
    assertThat((List<?>) gallery.get("gallery")).hasSize(10);
    int galleryBytes = bytes(gallery);
    assertThat(galleryBytes).as("GALLERY snapshot bytes").isLessThan(LIMIT);

    // ROUND_END adds the vote result and the reveal on top of the same gallery and chat.
    d.advance(Duration.ofSeconds(15));
    for (UUID p : d.players) {
      d.vote(p, d.players.get((d.players.indexOf(p) + 1) % 10));
    }
    Map<String, Object> ended = s.snapshot(d.players.get(3));
    assertThat(ended.get("roundEnd")).isNotNull();
    int endBytes = bytes(ended);
    assertThat(endBytes).as("ROUND_END snapshot bytes").isLessThan(LIMIT);

    System.out.printf(
        "SKETCHY_SIZE draft=%d galleryEvent=%d gallerySnapshotNoChat=%d gallerySnapshot=%d"
            + " roundEndSnapshot=%d limit=%d%n",
        draftBytes, eventBytes, emptyChat, galleryBytes, endBytes, LIMIT);
    assertThat(CODE).isNotNull();
  }
}
