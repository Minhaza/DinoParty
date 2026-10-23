package com.dinoparty.games.impostor;

import com.dinoparty.drawing.StrokeBatch;
import com.dinoparty.games.impostor.DinoImpostorState.Phase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything Dino Impostor shows to clients (api-contracts §3.11). The one place that decides who
 * sees the word and the roles ([DI-4], R35): the role only reaches the viewer it belongs to, and
 * nothing public names the impostor or carries the word.
 */
final class DinoImpostorViews {

  private DinoImpostorViews() {}

  /** Public {@code PHASE_STARTED} data of every phase: {@code round}, {@code totalRounds}. */
  static Map<String, Object> roundData(DinoImpostorState s) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("round", s.round);
    data.put("totalRounds", s.totalRounds);
    return data;
  }

  /** Public {@code PHASE_STARTED} data of a {@code DRAWING} turn. */
  static Map<String, Object> turnData(DinoImpostorState s) {
    Map<String, Object> data = roundData(s);
    data.put("lap", s.lapOf(s.turn));
    data.put("laps", s.settings.laps());
    data.put("turn", s.turn);
    data.put("totalTurns", s.totalTurns);
    data.put("drawerGuestId", s.drawer);
    data.put("seatOrder", s.seats);
    return data;
  }

  /** {@code IMPOSTOR_DRAWING_UPDATED}: the accepted batch unchanged plus its author ([DI-6]). */
  static Map<String, Object> strokeEvent(StrokeBatch batch, UUID author) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("kind", "STROKE");
    payload.put("authorGuestId", author);
    payload.put("strokeId", batch.strokeId());
    payload.put("batchNo", batch.batchNo());
    payload.put("tool", batch.tool().name());
    payload.put("color", batch.color());
    payload.put("size", batch.size());
    payload.put("points", batch.points());
    payload.put("final", batch.finalBatch());
    return payload;
  }

  /** The shared canvas in drawing order, each stroke with its author ({@code ImpostorStroke}). */
  static List<Map<String, Object>> strokes(DinoImpostorState s) {
    List<Map<String, Object>> strokes = s.canvas.snapshot();
    for (Map<String, Object> stroke : strokes) {
      stroke.put("authorGuestId", s.strokeAuthors.get((UUID) stroke.get("strokeId")));
    }
    return strokes;
  }

  /**
   * {@code DinoImpostorSnapshot} for {@code viewer}. The fields of voting, chat and the reveal
   * (P9-03) are present with their empty values.
   */
  static Map<String, Object> snapshot(DinoImpostorState s, UUID viewer) {
    boolean drawing = s.phase == Phase.DRAWING;
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("round", s.round);
    view.put("totalRounds", s.totalRounds);
    view.put("laps", s.settings.laps());
    view.put("seatOrder", s.seats);
    view.put("colors", orderedColors(s));
    view.put("lap", drawing ? s.lapOf(s.turn) : null);
    view.put("turn", drawing ? s.turn : null);
    view.put("totalTurns", s.totalTurns);
    view.put("drawerGuestId", drawing ? s.drawer : null);
    // [DI-4]: only a player seated this round has a role, and only their own.
    boolean seated = s.current != null && s.seats.contains(viewer);
    view.put("role", seated ? s.current.role(viewer) : null);
    view.put("strokes", strokes(s));
    view.put("canvasBudget", s.canvas.budget());
    // P9-03: votes, chat and the reveal fill these.
    view.put("votedGuestIds", List.of());
    view.put("myVote", null);
    view.put("recentChat", List.of());
    view.put("voteClosed", null);
    view.put("roundEnd", null);
    return view;
  }

  private static Map<UUID, String> orderedColors(DinoImpostorState s) {
    Map<UUID, String> colors = new LinkedHashMap<>();
    for (UUID g : s.order) {
      colors.put(g, s.colors.get(g));
    }
    return colors;
  }
}
