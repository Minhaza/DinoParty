package com.dinoparty.session;

import static com.dinoparty.session.SessionFixture.CODE;

import com.dinoparty.session.SessionFixture.Published;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Plays a Sketchy Saurus game through the real engine for the session tests, on top of {@link
 * ImpostorDriver} (clock, phases, voting commands, leaving). What differs here: the impostor is
 * found by the word when {@code impostorKnows} is off (nobody has the role {@code IMPOSTOR}), and
 * the drawing commands are Sketchy's.
 */
final class SketchyDriver {

  static final Duration SKETCH = Duration.ofSeconds(60);
  static final Duration GALLERY = Duration.ofSeconds(60);

  final SessionFixture f;
  final ImpostorDriver d;

  SketchyDriver(SessionFixture f) {
    this.f = f;
    this.d = new ImpostorDriver(f);
  }

  List<UUID> players() {
    return d.players;
  }

  void start(int n, Map<String, Object> settings) {
    d.start(n, settings);
  }

  /** Who is the impostor of the latest round, by role or, when nobody is told, by the odd word. */
  UUID impostor() {
    Map<UUID, Map<String, Object>> roles = d.roles();
    for (Map.Entry<UUID, Map<String, Object>> e : roles.entrySet()) {
      if (e.getValue().get("role").equals("IMPOSTOR")) {
        return e.getKey();
      }
    }
    Map<String, List<UUID>> byWord =
        roles.entrySet().stream()
            .collect(
                Collectors.groupingBy(
                    e -> (String) e.getValue().get("word"),
                    Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
    return byWord.values().stream()
        .filter(holders -> holders.size() == 1)
        .findFirst()
        .orElseThrow()
        .getFirst();
  }

  /** The real word: the one most players were given. */
  String word() {
    Map<String, Long> counts =
        d.roles().values().stream()
            .filter(r -> r.get("word") != null)
            .collect(Collectors.groupingBy(r -> (String) r.get("word"), Collectors.counting()));
    return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
  }

  /** The decoy the unaware impostor holds, or {@code null} when the impostor knows. */
  String decoy() {
    String held = (String) d.roles().get(impostor()).get("word");
    return held;
  }

  List<UUID> artists() {
    UUID impostor = impostor();
    return d.seated().stream().filter(p -> !p.equals(impostor)).toList();
  }

  /** ROLE_REVEAL is over: SKETCH is running. */
  void toSketch() {
    d.advance(ImpostorDriver.REVEAL);
  }

  /** Everyone submits what they have; the gallery opens. */
  void toGallery() {
    toSketch();
    d.advance(SKETCH);
  }

  /** Sends one batch of {@code points} points as a stroke of the sender. */
  Map<String, Object> batch(UUID strokeId, int batchNo, int points, boolean last) {
    List<List<Integer>> pts = new ArrayList<>();
    for (int i = 0; i < points; i++) {
      pts.add(List.of(10 * (i % 900), 50 + i / 900));
    }
    Map<String, Object> p = new HashMap<>();
    p.put("strokeId", strokeId.toString());
    p.put("batchNo", batchNo);
    p.put("tool", "PENCIL");
    p.put("color", "#112233");
    p.put("size", 2);
    p.put("points", pts);
    p.put("final", last);
    return p;
  }

  /** A closed stroke of {@code points} points in one batch. */
  UUID stroke(UUID guest, int points) {
    UUID id = UUID.randomUUID();
    d.send(guest, "SKETCHY_DRAW_STROKE", batch(id, 0, points, true));
    return id;
  }

  void submit(UUID guest) {
    d.send(guest, "SKETCHY_SUBMIT", Map.of());
  }

  void undo(UUID guest) {
    d.send(guest, "SKETCHY_DRAW_UNDO", Map.of());
  }

  void clear(UUID guest) {
    d.send(guest, "SKETCHY_DRAW_CLEAR", Map.of());
  }

  /** Every seated player draws {@code points} points (0 = nothing) and submits. */
  void everyoneSubmits(int points) {
    for (UUID p : d.seated()) {
      if (!d.phase().equals("SKETCH")
          || ((List<?>) d.lastEvent("SKETCHY_PROGRESS").get("submittedGuestIds")).contains(p)) {
        continue; // the phase ended early, or this player already submitted
      }
      if (points > 0) {
        stroke(p, points);
      }
      submit(p);
    }
  }

  /** The latest gallery event: guest to its drawing. */
  Map<UUID, Map<String, Object>> galleryEvent() {
    Map<String, Object> payload = d.lastEvent("SKETCHY_GALLERY_OPENED");
    return byGuest(payload.get("drawings"));
  }

  @SuppressWarnings("unchecked")
  static Map<UUID, Map<String, Object>> byGuest(Object drawings) {
    Map<UUID, Map<String, Object>> byGuest = new LinkedHashMap<>();
    for (Map<String, Object> drawing : (List<Map<String, Object>>) drawings) {
      byGuest.put((UUID) drawing.get("guestId"), drawing);
    }
    return byGuest;
  }

  Map<String, Object> snapshot(UUID viewer) {
    return f.sessions.gameSnapshot(CODE, viewer);
  }

  /** Votes so that {@code accused} is caught (everyone else votes for them), then closes. */
  void catchImpostor() {
    UUID impostor = impostor();
    d.everyoneVotes(impostor);
    d.advance(Duration.ofSeconds(15));
  }

  /** Every private event of {@code type} the guest has received. */
  List<Published> privateTo(UUID guest, String type) {
    return f.sink.ofType(type).stream().filter(e -> guest.equals(e.recipient())).toList();
  }
}
