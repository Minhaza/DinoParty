package com.dinoparty.session;

import static com.dinoparty.session.SessionFixture.CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.games.impostor.DinoImpostorModule;
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

/** [DG-14] limits on the shared canvas: the largest snapshot stays under the engine's 256 KiB. */
class DinoImpostorSnapshotSizeTest {

  private static final int LIMIT = 262_144;

  @Test
  void aFullCanvasWithTenPlayersSerializesUnderTheSnapshotBound() {
    SessionFixture f =
        new SessionFixture(new DinoImpostorModule(TestWords.service(), new SeededRandomSource(9)));
    f.room.settings = Map.of("rounds", 3, "laps", 3, "turnSeconds", 60);
    List<UUID> players = f.players(10);
    f.startAndPlay();
    f.clock.advance(Duration.ofSeconds(5));
    f.scheduler.fireDue(f.clock, false);

    // 30 turns; each drawer sends one stroke of 400 points (2 batches of 200), maximal
    // coordinates: 12 000 live points, the budget of a canvas.
    @SuppressWarnings("unchecked")
    Map<UUID, String> colors =
        (Map<UUID, String>) f.sessions.gameSnapshot(CODE, players.getFirst()).get("colors");
    for (int turn = 0; turn < 30; turn++) {
      UUID drawer = (UUID) f.sink.ofType("PHASE_STARTED").getLast().payload().get("drawerGuestId");
      UUID stroke = UUID.randomUUID();
      f.sessions.submit(
          CODE,
          drawer,
          "IMPOSTOR_DRAW_STROKE",
          f.phaseId(),
          batch(stroke, 0, colors.get(drawer), false));
      f.sessions.submit(
          CODE,
          drawer,
          "IMPOSTOR_DRAW_STROKE",
          f.phaseId(),
          batch(stroke, 1, colors.get(drawer), true));
    }

    Map<String, Object> snapshot = f.sessions.gameSnapshot(CODE, players.get(3));
    @SuppressWarnings("unchecked")
    Map<String, Object> budget = (Map<String, Object>) snapshot.get("canvasBudget");
    assertThat(budget).containsEntry("points", 12_000).containsEntry("strokes", 30);

    int bytes =
        JsonMapper.builder()
            .build()
            .writeValueAsString(snapshot)
            .getBytes(StandardCharsets.UTF_8)
            .length;
    assertThat(bytes).as("serialized snapshot bytes").isLessThan(LIMIT);

    // P9-03: the same canvas in ROUND_END with a full chat (30 lines of 120 four-byte
    // letters), every vote and the reveal.
    assertThat(f.sink.ofType("PHASE_STARTED").getLast().payload()).containsEntry("phase", "VOTE");
    String longLine = "𐐀".repeat(120);
    for (int i = 0; i < 30; i++) {
      f.sessions.submit(
          CODE, players.get(i % 10), "IMPOSTOR_CHAT", f.phaseId(), Map.of("text", longLine));
      if (i % 10 == 9) {
        f.clock.advance(Duration.ofSeconds(10));
      }
    }
    f.clock.advance(Duration.ofSeconds(15));
    for (UUID p : players) {
      UUID other = players.get((players.indexOf(p) + 1) % 10);
      f.sessions.submit(
          CODE, p, "IMPOSTOR_VOTE", f.phaseId(), Map.of("targetGuestId", other.toString()));
    }
    Map<String, Object> ended = f.sessions.gameSnapshot(CODE, players.get(3));
    assertThat(ended.get("roundEnd")).isNotNull();
    assertThat((List<?>) ended.get("recentChat")).hasSize(30);
    int endBytes =
        JsonMapper.builder()
            .build()
            .writeValueAsString(ended)
            .getBytes(StandardCharsets.UTF_8)
            .length;
    assertThat(endBytes).as("ROUND_END snapshot bytes").isLessThan(LIMIT);
  }

  private static Map<String, Object> batch(UUID id, int no, String color, boolean last) {
    List<List<Integer>> pts = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      pts.add(List.of(10_000 - i, 10_000));
    }
    Map<String, Object> p = new HashMap<>();
    p.put("strokeId", id.toString());
    p.put("batchNo", no);
    p.put("tool", "PENCIL");
    p.put("color", color);
    p.put("size", 4);
    p.put("points", pts);
    p.put("final", last);
    return p;
  }
}
