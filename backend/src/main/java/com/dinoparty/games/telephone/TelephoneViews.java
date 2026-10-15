package com.dinoparty.games.telephone;

import com.dinoparty.common.Timestamps;
import com.dinoparty.engine.OutboundEvent;
import com.dinoparty.games.telephone.TelephoneState.Draft;
import com.dinoparty.games.telephone.TelephoneState.Entry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wire shapes of api-contracts §3.10 built from the state. The only place that turns chain
 * content into data a client may receive, so the secrecy rules of [TP-8] live here: a source is the
 * previous entry of the viewer's own chain and never carries its author.
 */
final class TelephoneViews {

  private TelephoneViews() {}

  /** {@code TelephoneSource}: the previous entry as the next holder sees it; no author. */
  static Map<String, Object> source(Entry entry) {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("kind", entry.isDrawing() ? "DRAWING" : "TEXT");
    if (entry.isDrawing()) {
      source.put("strokes", entry.strokes());
    } else {
      source.put("text", entry.text());
    }
    source.put("placeholder", entry.placeholder());
    return source;
  }

  /** The source of the chain the guest holds now; null in the prompt step. */
  static Map<String, Object> sourceOf(TelephoneState s, UUID guest) {
    if (s.step == 0) {
      return null;
    }
    List<Entry> chain = s.chains.get(s.chainOf(guest));
    return source(chain.get(s.step - 1));
  }

  /** Private {@code TELEPHONE_TASK} of one holder. */
  static OutboundEvent task(TelephoneState s, UUID guest, UUID phaseId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("phaseId", phaseId);
    payload.put("step", s.step);
    payload.put("kind", s.kind.name());
    payload.put("source", sourceOf(s, guest));
    payload.put("deadlineAt", Timestamps.format(s.deadline));
    return OutboundEvent.privateEvent(guest, "TELEPHONE_TASK", payload);
  }

  /** Public {@code TELEPHONE_PROGRESS}: who has submitted, never what. */
  static OutboundEvent progress(TelephoneState s) {
    List<UUID> eligible = s.eligible();
    List<UUID> done = s.submitted.stream().filter(g -> !s.isLeft(g)).toList();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("phaseId", s.phaseId);
    payload.put("submittedCount", done.size());
    payload.put("total", eligible.size());
    payload.put("submittedGuestIds", done);
    return OutboundEvent.publicEvent("TELEPHONE_PROGRESS", payload);
  }

  /** The game part of one viewer's snapshot ({@code TelephoneSnapshot}). */
  static Map<String, Object> snapshot(TelephoneState s, UUID viewer) {
    List<UUID> eligible = s.eligible();
    List<UUID> done = s.submitted.stream().filter(g -> !s.isLeft(g)).toList();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("step", s.over ? s.totalSteps : s.step);
    view.put("totalSteps", s.totalSteps);
    view.put("entriesPerChain", s.totalSteps);
    view.put("kind", s.over ? null : s.kind.name());
    boolean holder = !s.over && s.index.containsKey(viewer) && !s.isLeft(viewer);
    view.put("task", holder ? task(s, viewer) : null);
    view.put("draft", holder ? draft(s, viewer) : null);
    view.put("submitted", s.submitted.contains(viewer));
    view.put("submittedGuestIds", done);
    view.put("waitingOn", s.over ? 0 : eligible.size() - done.size());
    view.put("reveal", null);
    return view;
  }

  private static Map<String, Object> task(TelephoneState s, UUID viewer) {
    Map<String, Object> task = new LinkedHashMap<>();
    task.put("kind", s.kind.name());
    task.put("source", sourceOf(s, viewer));
    return task;
  }

  /** The viewer's own work in progress; null once submitted (the draft became the entry). */
  private static Map<String, Object> draft(TelephoneState s, UUID viewer) {
    Draft draft = s.drafts.get(viewer);
    if (draft == null || s.submitted.contains(viewer)) {
      return null;
    }
    Map<String, Object> view = new LinkedHashMap<>();
    if (draft.canvas != null) {
      view.put("strokes", draft.canvas.snapshot());
      view.put("canvasBudget", draft.canvas.budget());
    } else {
      view.put("text", draft.text);
    }
    return view;
  }
}
