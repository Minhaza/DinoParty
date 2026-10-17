package com.dinoparty.games.telephone;

import com.dinoparty.common.Timestamps;
import com.dinoparty.engine.OutboundEvent;
import com.dinoparty.games.telephone.TelephoneState.Draft;
import com.dinoparty.games.telephone.TelephoneState.Entry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The wire shapes of api-contracts §3.10 built from the state. The only place that turns chain
 * content into data a client may receive, so the secrecy rules of [TP-8] live here: a source is the
 * previous entry of the viewer's own chain and never carries its author, and the reveal never shows
 * an entry past the furthest one revealed ({@code highWater}).
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
    boolean steps = !s.over && !s.revealing;
    List<UUID> eligible = s.eligible();
    List<UUID> done = s.submitted.stream().filter(g -> !s.isLeft(g)).toList();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("step", steps ? s.step : s.totalSteps);
    view.put("totalSteps", s.totalSteps);
    view.put("entriesPerChain", s.totalSteps);
    view.put("kind", steps ? s.kind.name() : null);
    boolean holder = steps && s.index.containsKey(viewer) && !s.isLeft(viewer);
    view.put("task", holder ? task(s, viewer) : null);
    view.put("draft", holder ? draft(s, viewer) : null);
    view.put("submitted", s.submitted.contains(viewer));
    view.put("submittedGuestIds", done);
    view.put("waitingOn", steps ? eligible.size() - done.size() : 0);
    view.put("reveal", s.revealing ? reveal(s) : null);
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

  // ------------------------------------------------------------------ reveal ([TP-6], §3.10)

  /** Cumulative size of the stroke arrays a reveal snapshot may carry (api-contracts §3.10). */
  static final long REVEAL_STROKE_BUDGET = 205_000;

  /** {@code RevealEntryDto}; {@code withStrokes=false} withholds a drawing ({@code omitted}). */
  static Map<String, Object> revealEntry(TelephoneState s, int position, boolean withStrokes) {
    Entry entry = s.entryAt(position);
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("entryIndex", position % s.totalSteps);
    dto.put("kind", entry.isDrawing() ? "DRAWING" : "TEXT");
    dto.put("authorGuestId", entry.author());
    dto.put("authorNickname", s.nicknames.get(entry.author()));
    boolean omitted = entry.isDrawing() && !withStrokes;
    if (entry.isDrawing()) {
      dto.put("strokes", omitted ? null : entry.strokes());
    } else {
      dto.put("text", entry.text());
    }
    dto.put("omitted", omitted);
    dto.put("fill", entry.fill().name());
    dto.put("timedOut", entry.timedOut());
    dto.put("placeholder", entry.placeholder());
    return dto;
  }

  static Map<String, Object> position(TelephoneState s, int position) {
    Map<String, Object> cursor = new LinkedHashMap<>();
    cursor.put("chainIndex", position / s.totalSteps);
    cursor.put("entryIndex", position % s.totalSteps);
    return cursor;
  }

  /** Public {@code ALBUM_REVEALED}: the entry travels only the first time it is shown. */
  static OutboundEvent albumRevealed(TelephoneState s, boolean newlyRevealed, boolean auto) {
    Map<String, Object> payload = new LinkedHashMap<>(position(s, s.cursor));
    payload.put("newlyRevealed", newlyRevealed);
    payload.put("entry", newlyRevealed ? revealEntry(s, s.cursor, true) : null);
    payload.put("autoAdvanced", auto);
    payload.put("autoAdvanceAt", Timestamps.format(s.autoAdvanceAt));
    return OutboundEvent.publicEvent("ALBUM_REVEALED", payload);
  }

  /**
   * The snapshot {@code reveal}: every revealed entry (nothing past {@code highWater}); drawings
   * carry strokes for the cursor entry and then newest first while the budget lasts.
   */
  static Map<String, Object> reveal(TelephoneState s) {
    Set<Integer> withStrokes = new HashSet<>();
    withStrokes.add(s.cursor);
    long used = strokeBytes(s.entryAt(s.cursor));
    for (int pos = s.highWater; pos >= 0; pos--) {
      Entry entry = s.entryAt(pos);
      if (pos == s.cursor || !entry.isDrawing()) {
        continue;
      }
      long size = strokeBytes(entry);
      if (used + size > REVEAL_STROKE_BUDGET) {
        break;
      }
      used += size;
      withStrokes.add(pos);
    }
    List<Map<String, Object>> albums = new ArrayList<>();
    for (int chain = 0; chain < s.n(); chain++) {
      int first = chain * s.totalSteps;
      int revealed = Math.clamp(s.highWater - first + 1, 0, s.totalSteps);
      List<Map<String, Object>> entries = new ArrayList<>();
      for (int pos = first; pos < first + revealed; pos++) {
        entries.add(revealEntry(s, pos, withStrokes.contains(pos)));
      }
      Map<String, Object> album = new LinkedHashMap<>();
      album.put("chainIndex", chain);
      album.put("ownerGuestId", s.order.get(chain));
      album.put("entryCount", s.totalSteps);
      album.put("revealedCount", revealed);
      album.put("entries", entries);
      albums.add(album);
    }
    Map<String, Object> reveal = new LinkedHashMap<>();
    reveal.put("cursor", position(s, s.cursor));
    reveal.put("highWater", position(s, s.highWater));
    reveal.put("autoAdvanceAt", Timestamps.format(s.autoAdvanceAt));
    reveal.put("chains", s.n());
    reveal.put("albums", albums);
    return reveal;
  }

  private static long strokeBytes(Entry entry) {
    return entry.isDrawing() ? jsonLength(entry.strokes()) : 0;
  }

  /**
   * The length of the compact JSON form of a stroke list (maps, lists, strings without escapes,
   * numbers and booleans), so the budget is checked without a serializer in the module.
   */
  static long jsonLength(Object value) {
    return switch (value) {
      case null -> 4;
      case String text -> text.length() + 2L;
      case UUID id -> 38;
      case Map<?, ?> map -> {
        long length = 2 + Math.max(0, map.size() - 1);
        for (Map.Entry<?, ?> e : map.entrySet()) {
          length += e.getKey().toString().length() + 3 + jsonLength(e.getValue());
        }
        yield length;
      }
      case Collection<?> list -> {
        long length = 2 + Math.max(0, list.size() - 1);
        for (Object item : list) {
          length += jsonLength(item);
        }
        yield length;
      }
      case int[] pair -> {
        long length = 2 + Math.max(0, pair.length - 1);
        for (int v : pair) {
          length += Integer.toString(v).length();
        }
        yield length;
      }
      default -> value.toString().length();
    };
  }
}
