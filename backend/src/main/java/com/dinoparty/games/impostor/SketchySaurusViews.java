package com.dinoparty.games.impostor;

import com.dinoparty.drawing.CanvasBuffer;
import com.dinoparty.games.impostor.SketchySaurusState.Phase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything Sketchy Saurus shows to clients (api-contracts §3.12). The one place that decides who
 * sees a drawing and the roles ([SS-2], [DI-4], R35): a draft only reaches its author, a drawing
 * reaches everyone only in the gallery, and the role only reaches the viewer it belongs to.
 */
final class SketchySaurusViews {

  private SketchySaurusViews() {}

  /** Public {@code PHASE_STARTED} data of every phase: {@code round}, {@code totalRounds}. */
  static Map<String, Object> roundData(SketchySaurusState s) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("round", s.round);
    data.put("totalRounds", s.totalRounds);
    return data;
  }

  /**
   * Public {@code PHASE_STARTED} data of {@code ROLE_REVEAL}: also the roster's nicknames, for a
   * client that never takes a snapshot (G8). There are no seats or colours.
   */
  static Map<String, Object> roleRevealData(SketchySaurusState s) {
    Map<String, Object> data = roundData(s);
    data.put("nicknames", orderedNicknames(s));
    return data;
  }

  /** {@code SKETCHY_PROGRESS}: who has submitted, never what they drew. */
  static Map<String, Object> progress(SketchySaurusState s) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("phaseId", s.sketchPhaseId);
    payload.put("submittedGuestIds", submittedGuestIds(s));
    return payload;
  }

  /** {@code SKETCHY_GALLERY_OPENED}: every drawing with its artist, in session order. */
  static Map<String, Object> galleryOpened(SketchySaurusState s) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("round", s.round);
    payload.put("drawings", s.gallery);
    return payload;
  }

  /**
   * The gallery as it stands when {@code SKETCH} ends: one drawing per player still seated, in
   * session order, each as the draft was (submitted or not). The strokes are final, so none is left
   * open.
   */
  static List<Map<String, Object>> buildGallery(SketchySaurusState s) {
    List<Map<String, Object>> gallery = new ArrayList<>();
    for (UUID guest : s.order) {
      CanvasBuffer draft = s.drafts.get(guest);
      if (draft == null) {
        continue;
      }
      List<Map<String, Object>> strokes = draft.snapshot();
      strokes.forEach(stroke -> stroke.put("closed", true));
      Map<String, Object> drawing = new LinkedHashMap<>();
      drawing.put("guestId", guest);
      drawing.put("strokes", strokes);
      drawing.put("empty", strokes.isEmpty());
      gallery.add(drawing);
    }
    return List.copyOf(gallery);
  }

  /** {@code SketchySaurusSnapshot} for {@code viewer} (api-contracts §3.12). */
  static Map<String, Object> snapshot(SketchySaurusState s, UUID viewer) {
    boolean sketching = s.phase == Phase.SKETCH;
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("round", s.round);
    view.put("totalRounds", s.totalRounds);
    view.put("nicknames", orderedNicknames(s));
    // [DI-4]: only a player seated this round has a role, and only their own.
    boolean seated = s.current != null && s.seated.contains(viewer);
    view.put("role", seated ? s.current.role(viewer) : null);
    // [SS-2]: only the viewer's own draft, and only while sketching (G6: it stays after submit).
    CanvasBuffer own = sketching ? s.drafts.get(viewer) : null;
    view.put("draft", own == null ? null : draft(own));
    view.put("submitted", sketching && s.submitted.contains(viewer));
    view.put("submittedGuestIds", sketching ? submittedGuestIds(s) : List.of());
    view.put("gallery", s.gallery);
    ImpostorVoting voting = s.voting;
    view.put("votedGuestIds", voting.votedGuestIds());
    view.put("myVote", voting.myVote(viewer)); // the viewer's own vote, never another's (R35)
    view.put("recentChat", voting.recentChat());
    view.put("voteClosed", voting.voteClosed());
    view.put("roundEnd", voting.roundEnd());
    return view;
  }

  private static Map<String, Object> draft(CanvasBuffer canvas) {
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("strokes", canvas.snapshot());
    draft.put("canvasBudget", canvas.budget());
    return draft;
  }

  /** Players seated and still in the game who have submitted, in session order. */
  private static List<UUID> submittedGuestIds(SketchySaurusState s) {
    return s.order.stream().filter(s.submitted::contains).toList();
  }

  private static Map<UUID, String> orderedNicknames(SketchySaurusState s) {
    Map<UUID, String> names = new LinkedHashMap<>();
    for (UUID g : s.order) {
      names.put(g, s.nicknames.get(g));
    }
    return names;
  }
}
