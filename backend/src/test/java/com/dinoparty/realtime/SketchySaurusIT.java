package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.guest.Guest;
import com.dinoparty.room.CreateRoomCommand;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Sketchy Saurus over real STOMP (P9-04): whole 5-player games in the three settings from start to
 * results and rematch, reconnecting in {@code SKETCH}, {@code GALLERY} and {@code GUESS}, leaving
 * in every phase, unauthorized commands, and a Dino Impostor room playing at the same time (which
 * also proves that the shared declarations register).
 */
@RealtimeIntegrationTest
class SketchySaurusIT extends GameEngineSupport {

  private static final Duration LONG = Duration.ofSeconds(30);

  private static final Map<String, Object> FAST = Map.of("voteSeconds", 30, "guessSeconds", 15);

  @AfterEach
  void closeTheClients() {
    closeClients();
  }

  private static Map<String, Object> settings(int rounds, Map<String, Object> extra) {
    Map<String, Object> all = new HashMap<>(FAST);
    all.put("rounds", rounds);
    all.putAll(extra);
    return all;
  }

  /** A room of one game; index 0 is the host. */
  private final class Room {
    final String gameKey;
    final List<Guest> guests = new ArrayList<>();
    final List<StompTestClient> sockets = new ArrayList<>();

    /** Every socket a guest has had, so the messages of dropped ones stay readable. */
    final List<List<StompTestClient>> history = new ArrayList<>();

    final Set<String> left = new HashSet<>();
    final String code;

    Room(String gameKey, int players, Map<String, Object> settings) {
      this.gameKey = gameKey;
      Guest host = newGuest();
      code = roomService.create(host.id(), new CreateRoomCommand(gameKey, settings, null)).code();
      guests.add(host);
      for (int i = 1; i < players; i++) {
        Guest g = newGuest();
        roomService.join(g.id(), code);
        guests.add(g);
      }
      guests.forEach(g -> sockets.add(connectAndSubscribe(g, code)));
      sockets.forEach(s -> history.add(new ArrayList<>(List.of(s))));
    }

    void start() {
      sockets.getFirst().command(code, "START_GAME", actionId(), "{}");
      awaitLong(sockets.getFirst(), m -> m.type().equals("GAME_STARTED"));
    }

    String id(int index) {
      return guests.get(index).id().toString();
    }

    int indexOf(String guestId) {
      for (int i = 0; i < guests.size(); i++) {
        if (id(i).equals(guestId)) {
          return i;
        }
      }
      throw new AssertionError("unknown guest " + guestId);
    }

    StompTestClient socketOf(String guestId) {
      return sockets.get(indexOf(guestId));
    }

    List<String> seated() {
      return IntStream.range(0, guests.size())
          .mapToObj(this::id)
          .filter(g -> !left.contains(g))
          .toList();
    }

    void leave(String guestId) {
      left.add(guestId);
      roomService.leave(UUID.fromString(guestId), code);
    }

    StompTestClient reconnect(String guestId) {
      int index = indexOf(guestId);
      sockets.get(index).drop();
      StompTestClient again = connectAndSubscribe(guests.get(index), code);
      sockets.set(index, again);
      history.get(index).add(again);
      return again;
    }

    JsonNode snapshot(StompTestClient ws) {
      int before = ws.ofType("ROOM_SNAPSHOT").size();
      ws.command(code, "REQUEST_SNAPSHOT", actionId(), "{}");
      await().atMost(Duration.ofSeconds(5)).until(() -> ws.ofType("ROOM_SNAPSHOT").size() > before);
      return ws.ofType("ROOM_SNAPSHOT").getLast().payload().get("game");
    }

    /** Waits until any socket this room's guests ever had has received a matching message. */
    StompTestClient.Message awaitAny(Predicate<StompTestClient.Message> p) {
      return await()
          .atMost(LONG)
          .until(
              () ->
                  history.stream()
                      .flatMap(List::stream)
                      .flatMap(s -> s.messages().stream())
                      .filter(p)
                      .findFirst()
                      .orElse(null),
              m -> m != null);
    }

    JsonNode awaitPhase(int round, String phase) {
      return awaitAny(
              m ->
                  m.type().equals("PHASE_STARTED")
                      && m.payload().get("phase").asString().equals(phase)
                      && m.payload().get("round").asInt() == round)
          .payload();
    }

    JsonNode awaitRoundEnded(int round) {
      return awaitAny(
              m ->
                  m.type().equals("IMPOSTOR_ROUND_ENDED")
                      && m.payload().get("round").asInt() == round)
          .payload();
    }

    /** The role every seated player of {@code round} was sent, by guest id. */
    Map<String, JsonNode> roles(int round) {
      await().atMost(LONG).until(() -> rolesNow(round).size() >= guests.size() - left.size());
      return rolesNow(round);
    }

    private Map<String, JsonNode> rolesNow(int round) {
      Map<String, JsonNode> roles = new HashMap<>();
      for (int i = 0; i < guests.size(); i++) {
        for (StompTestClient s : history.get(i)) {
          for (StompTestClient.Message m : s.ofType("IMPOSTOR_ROLE")) {
            if (m.payload().get("round").asInt() == round) {
              roles.put(id(i), m.payload());
            }
          }
        }
      }
      return roles;
    }

    /** The impostor: by role when told, else the one player holding the odd word. */
    String impostor(int round) {
      Map<String, JsonNode> roles = roles(round);
      for (Map.Entry<String, JsonNode> e : roles.entrySet()) {
        if (e.getValue().get("role").asString().equals("IMPOSTOR")) {
          return e.getKey();
        }
      }
      Map<String, List<String>> byWord =
          roles.entrySet().stream()
              .collect(
                  Collectors.groupingBy(
                      e -> e.getValue().get("word").asString(),
                      Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
      return byWord.values().stream()
          .filter(holders -> holders.size() == 1)
          .findFirst()
          .orElseThrow()
          .getFirst();
    }

    /** The real word: the one most players were given. */
    String word(int round) {
      return roles(round).values().stream()
          .filter(p -> !p.get("word").isNull())
          .collect(Collectors.groupingBy(p -> p.get("word").asString(), Collectors.counting()))
          .entrySet()
          .stream()
          .max(Map.Entry.comparingByValue())
          .orElseThrow()
          .getKey();
    }

    /** The decoy of {@code round}, or null when the impostor was told their role. */
    String decoy(int round) {
      JsonNode role = roles(round).get(impostor(round));
      return role.get("word").isNull() ? null : role.get("word").asString();
    }

    List<String> artists(int round) {
      String impostor = impostor(round);
      return roles(round).keySet().stream().filter(g -> !g.equals(impostor)).sorted().toList();
    }

    void cmd(StompTestClient ws, String type, String phaseId, String payload, String actionId) {
      ws.command(
          code,
          "{\"type\":\"%s\",\"actionId\":\"%s\",\"phaseId\":\"%s\",\"payload\":%s}"
              .formatted(type, actionId, phaseId, payload));
    }

    String refused(StompTestClient ws, String type, String phaseId, String payload) {
      String id = actionId();
      cmd(ws, type, phaseId, payload, id);
      return errorCode(ws, id);
    }

    void accepted(StompTestClient ws, String type, String phaseId, String payload) {
      String id = actionId();
      cmd(ws, type, phaseId, payload, id);
      awaitAck(ws, id);
    }

    void vote(String voter, String phaseId, String target) {
      accepted(
          socketOf(voter),
          "IMPOSTOR_VOTE",
          phaseId,
          "{\"targetGuestId\":%s}".formatted(target == null ? "null" : "\"" + target + "\""));
    }

    /** Everyone seated votes for {@code accused}, who votes for {@code other}. */
    void everyoneVotes(String phaseId, String accused, String other) {
      for (String g : seated()) {
        vote(g, phaseId, g.equals(accused) ? other : accused);
      }
    }

    /** Draws one stroke for {@code guest} and waits for the acknowledgement; returns its id. */
    String draw(String guest, String phaseId, int points) {
      String strokeId = UUID.randomUUID().toString();
      accepted(socketOf(guest), "SKETCHY_DRAW_STROKE", phaseId, stroke(strokeId, 0, points, true));
      return strokeId;
    }

    /** Everyone seated draws one stroke; the ids by guest. */
    Map<String, String> everyoneDraws(String phaseId) {
      Map<String, String> strokes = new HashMap<>();
      for (String g : seated()) {
        strokes.put(g, draw(g, phaseId, 4));
      }
      return strokes;
    }

    void everyoneSubmits(String phaseId) {
      for (String g : seated()) {
        accepted(socketOf(g), "SKETCHY_SUBMIT", phaseId, "{}");
      }
    }
  }

  private static StompTestClient.Message awaitLong(
      StompTestClient ws, Predicate<StompTestClient.Message> p) {
    return await()
        .atMost(LONG)
        .until(() -> ws.messages().stream().filter(p).findFirst().orElse(null), m -> m != null);
  }

  private static String errorCode(StompTestClient ws, String actionId) {
    return awaitLong(
            ws,
            m ->
                (m.type().equals("GAME_ERROR") || m.type().equals("ERROR"))
                    && actionId.equals(m.payload().get("actionId").asString()))
        .payload()
        .get("code")
        .asString();
  }

  private static void awaitAck(StompTestClient ws, String actionId) {
    awaitLong(
        ws,
        m ->
            m.type().equals("ACTION_ACK")
                && actionId.equals(m.payload().get("actionId").asString()));
  }

  private static String stroke(String strokeId, int batchNo, int points, boolean last) {
    String pts =
        IntStream.range(0, points)
            .mapToObj(i -> "[%d,5000]".formatted(1000 + 10 * i))
            .collect(Collectors.joining(","));
    return ("{\"strokeId\":\"%s\",\"batchNo\":%d,\"tool\":\"PENCIL\",\"color\":\"#112233\","
            + "\"size\":2,\"points\":[%s],\"final\":%s}")
        .formatted(strokeId, batchNo, pts, last);
  }

  private static Set<String> fieldNames(JsonNode node) {
    Set<String> names = new HashSet<>();
    node.propertyNames().forEach(names::add);
    return names;
  }

  private static String json(StompTestClient.Message m) {
    return m.envelope().toString();
  }

  private static final java.util.regex.Pattern UUID_TEXT =
      java.util.regex.Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  /**
   * The text values of a message payload, without keys and ids: what a player could read. A short
   * word must not match a key name or the hex digits of an id, so those are left out.
   */
  private static String leakText(StompTestClient.Message m) {
    StringBuilder text = new StringBuilder();
    collectValues(m.payload(), text);
    return text.toString();
  }

  private static void collectValues(JsonNode node, StringBuilder out) {
    if (node.isString()) {
      if (!UUID_TEXT.matcher(node.asString()).matches()) {
        out.append(node.asString()).append(' ');
      }
    } else if (node.isObject()) {
      node.properties().forEach(e -> collectValues(e.getValue(), out));
    } else if (node.isArray()) {
      node.forEach(child -> collectValues(child, out));
    }
  }

  // ------------------------------------------------------------------ whole games

  /**
   * R35 over the whole recorded traffic of one socket: before {@code IMPOSTOR_ROUND_ENDED} of a
   * round no public message carries its word or decoy, before {@code IMPOSTOR_VOTE_CLOSED} (when
   * caught) or the round end none names the impostor, and before {@code IMPOSTOR_VOTE_CLOSED} none
   * carries a vote target.
   */
  private void assertNothingLeaked(Room r, StompTestClient ws, int rounds) {
    List<StompTestClient.Message> all = ws.onStream("ROOM", r.code);
    for (int round = 1; round <= rounds; round++) {
      int rd = round;
      int start = -1;
      int closed = all.size();
      int caught = all.size();
      int ended = all.size();
      for (int i = 0; i < all.size(); i++) {
        StompTestClient.Message m = all.get(i);
        JsonNode p = m.payload();
        if (start < 0
            && m.type().equals("PHASE_STARTED")
            && p.get("phase").asString().equals("ROLE_REVEAL")
            && p.get("round").asInt() == rd) {
          start = i;
        }
        if (start >= 0 && p.has("round") && p.get("round").asInt() == rd) {
          if (m.type().equals("IMPOSTOR_VOTE_CLOSED") && closed == all.size()) {
            closed = i;
            if (p.get("caught").asBoolean()) {
              caught = i;
            }
          }
          if (m.type().equals("IMPOSTOR_ROUND_ENDED")) {
            ended = i;
            caught = Math.min(caught, i);
            closed = Math.min(closed, i);
            break;
          }
        }
      }
      if (start < 0) {
        continue; // this socket joined after the round began (a reconnect)
      }
      String word = r.word(rd);
      String decoy = r.decoy(rd);
      for (int i = start; i < all.size() && i < ended; i++) {
        String readable = leakText(all.get(i));
        String text = json(all.get(i));
        assertThat(readable).as("word in %s", all.get(i).type()).doesNotContainIgnoringCase(word);
        if (decoy != null) {
          assertThat(readable)
              .as("decoy in %s", all.get(i).type())
              .doesNotContainIgnoringCase(decoy);
        }
        if (i < caught) {
          assertThat(text)
              .as("impostor in %s", all.get(i).type())
              .doesNotContain("\"impostorGuestId\":\"");
        }
        if (i < closed) {
          assertThat(text)
              .as("votes in %s", all.get(i).type())
              .doesNotContain("\"votes\":{", "\"accusedGuestId\":\"", "\"targetGuestId\"");
        }
      }
    }
  }

  /** No stroke of {@code round}'s drafts appeared on any socket before the gallery opened. */
  private void assertDraftsStayPrivate(Room r, Map<String, String> strokes) {
    for (int i = 0; i < r.guests.size(); i++) {
      for (StompTestClient ws : r.history.get(i)) {
        List<StompTestClient.Message> before = new ArrayList<>();
        for (StompTestClient.Message m : ws.messages()) {
          if (m.type().equals("SKETCHY_GALLERY_OPENED")
              || (m.type().equals("PHASE_STARTED")
                  && m.payload().get("phase").asString().equals("GALLERY"))) {
            break;
          }
          if (!m.type().equals("ROOM_SNAPSHOT")) {
            before.add(m); // a snapshot of one's own draft is legitimate, checked separately
          }
        }
        String traffic = before.stream().map(SketchySaurusIT::json).collect(Collectors.joining());
        strokes.forEach(
            (author, strokeId) ->
                assertThat(traffic)
                    .as("%s's stroke on the socket of guest %d", author, 0)
                    .doesNotContain(strokeId));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"knows", "unaware", "unawareGuess"})
  void aFivePlayerGameRunsFromStartToResultsAndRematchInEverySetting(String setting)
      throws Exception {
    boolean unaware = !setting.equals("knows");
    boolean mayGuess = !setting.equals("unaware");
    Room r =
        new Room(
            "sketchy-saurus",
            5,
            settings(
                3,
                switch (setting) {
                  case "knows" -> Map.of();
                  case "unaware" -> Map.of("impostorKnows", false);
                  default -> Map.of("impostorKnows", false, "unawareGuess", true);
                }));
    r.start();

    // ---- round 1: escaped ----
    JsonNode reveal1 = r.awaitPhase(1, "ROLE_REVEAL");
    assertThat(reveal1.get("nicknames")).hasSize(5); // G8
    assertThat(reveal1.has("seatOrder")).isFalse();
    Map<String, JsonNode> roles1 = r.roles(1);
    assertThat(
            roles1.values().stream().map(SketchySaurusIT::fieldNames).collect(Collectors.toSet()))
        .containsExactly(Set.of("phaseId", "round", "role", "category", "word"));
    if (unaware) {
      assertThat(
              roles1.values().stream()
                  .map(p -> p.get("role").asString())
                  .collect(Collectors.toSet()))
          .containsExactly("ARTIST");
      assertThat(r.decoy(1)).isNotNull().isNotEqualTo(r.word(1));
    } else {
      assertThat(r.decoy(1)).isNull();
    }
    for (StompTestClient ws : r.sockets) {
      assertThat(
              ws.onStream("ROOM", r.code).stream()
                  .map(SketchySaurusIT::leakText)
                  .collect(Collectors.joining(" ")))
          .doesNotContainIgnoringCase(r.word(1));
    }
    String hostId = r.id(0);

    JsonNode sketch1 = r.awaitPhase(1, "SKETCH");
    String sketchPhase1 = sketch1.get("phaseId").asString();
    Map<String, String> strokes1 = r.everyoneDraws(sketchPhase1);
    // Reconnect in SKETCH: the viewer's own draft comes back with the stroke they drew.
    String viewer = r.id(3);
    StompTestClient inSketch = r.reconnect(viewer);
    JsonNode sketchView = r.snapshot(inSketch);
    assertThat(sketchView.get("phase").asString()).isEqualTo("SKETCH");
    assertThat(sketchView.get("draft").get("strokes")).hasSize(1);
    assertThat(sketchView.get("draft").get("strokes").get(0).get("strokeId").asString())
        .isEqualTo(strokes1.get(viewer));
    assertThat(sketchView.get("draft").get("canvasBudget").get("maxPoints").asInt())
        .isEqualTo(1000);
    assertThat(sketchView.get("submitted").asBoolean()).isFalse();
    assertThat(sketchView.get("gallery").isNull()).isTrue();
    assertThat(sketchView.get("role").get("role").asString()).isIn("ARTIST", "IMPOSTOR");
    // Nobody got any draft before the gallery.
    assertDraftsStayPrivate(r, strokes1);

    r.accepted(r.socketOf(viewer), "SKETCHY_SUBMIT", sketchPhase1, "{}");
    // G6: the submitted drawing stays in the viewer's draft until SKETCH ends.
    JsonNode submitted = r.snapshot(r.reconnect(viewer));
    assertThat(submitted.get("submitted").asBoolean()).isTrue();
    assertThat(submitted.get("draft").get("strokes")).hasSize(1);
    assertThat(submitted.get("submittedGuestIds")).hasSize(1);
    assertThat(
            r.refused(
                r.socketOf(viewer),
                "SKETCHY_DRAW_STROKE",
                sketchPhase1,
                stroke(UUID.randomUUID().toString(), 0, 2, true)))
        .isEqualTo("ALREADY_SUBMITTED");
    for (String g : r.seated()) {
      if (!g.equals(viewer)) {
        r.accepted(r.socketOf(g), "SKETCHY_SUBMIT", sketchPhase1, "{}");
      }
    }

    JsonNode gallery1 = r.awaitPhase(1, "GALLERY");
    String galleryPhase1 = gallery1.get("phaseId").asString();
    JsonNode opened = r.awaitAny(m -> m.type().equals("SKETCHY_GALLERY_OPENED")).payload();
    assertThat(opened.get("drawings")).hasSize(5);
    for (JsonNode drawing : opened.get("drawings")) {
      assertThat(drawing.get("empty").asBoolean()).isFalse();
      assertThat(drawing.get("strokes").get(0).get("strokeId").asString())
          .isEqualTo(strokes1.get(drawing.get("guestId").asString()));
    }
    List<String> artists1 = r.artists(1);
    String impostor1 = r.impostor(1);
    List<String> nonHost = artists1.stream().filter(g -> !g.equals(hostId)).toList();
    String n0 = nonHost.get(0);
    String n1 = nonHost.get(1);
    // The unauthorized: drawing in GALLERY, a self-vote.
    assertThat(
            r.refused(
                r.socketOf(n1),
                "SKETCHY_DRAW_STROKE",
                galleryPhase1,
                stroke(UUID.randomUUID().toString(), 0, 2, true)))
        .isEqualTo("INVALID_PHASE");
    assertThat(r.refused(r.socketOf(n1), "SKETCHY_SUBMIT", galleryPhase1, "{}"))
        .isEqualTo("INVALID_PHASE");
    r.accepted(r.socketOf(n1), "IMPOSTOR_CHAT", galleryPhase1, "{\"text\":\"11 22 33\"}");
    for (StompTestClient ws : r.sockets) {
      JsonNode line = awaitLong(ws, m -> m.type().equals("IMPOSTOR_CHAT_MESSAGE")).payload();
      assertThat(line.get("guestId").asString()).isEqualTo(n1);
    }
    assertThat(
            r.refused(
                r.socketOf(n1),
                "IMPOSTOR_VOTE",
                galleryPhase1,
                "{\"targetGuestId\":\"%s\"}".formatted(n1)))
        .isEqualTo("VALIDATION_FAILED");
    r.vote(n1, galleryPhase1, n0);
    // Reconnect in GALLERY: the whole gallery, the chat and the viewer's own vote.
    JsonNode galleryView = r.snapshot(r.reconnect(n1));
    assertThat(galleryView.get("phase").asString()).isEqualTo("GALLERY");
    assertThat(galleryView.get("gallery")).hasSize(5);
    assertThat(galleryView.get("recentChat")).hasSize(1);
    assertThat(galleryView.get("votedGuestIds")).hasSize(1);
    assertThat(galleryView.get("myVote").asString()).isEqualTo(n0);
    assertThat(galleryView.get("draft").isNull()).isTrue();
    r.everyoneVotes(galleryPhase1, n0, n1);

    JsonNode ended1 = r.awaitRoundEnded(1);
    assertThat(ended1.get("outcome").asString()).isEqualTo("ESCAPED");
    assertThat(ended1.get("impostorGuestId").asString()).isEqualTo(impostor1);
    assertThat(ended1.get("word").asString()).isEqualTo(r.word(1));
    assertThat(ended1.get("decoyWord").isNull()).isEqualTo(!unaware);
    assertThat(ended1.get("scoreDeltas").get(impostor1).asInt()).isEqualTo(2);

    // ---- round 2: caught, then right guess if allowed ----
    JsonNode sketch2 = r.awaitPhase(2, "SKETCH");
    r.everyoneDraws(sketch2.get("phaseId").asString());
    r.everyoneSubmits(sketch2.get("phaseId").asString());
    String gallery2 = r.awaitPhase(2, "GALLERY").get("phaseId").asString();
    String impostor2 = r.impostor(2);
    List<String> artists2 = r.artists(2);
    r.everyoneVotes(gallery2, impostor2, artists2.get(0));
    if (mayGuess) {
      JsonNode guessPhase = r.awaitPhase(2, "GUESS");
      assertThat(guessPhase.get("impostorGuestId").asString()).isEqualTo(impostor2);
      awaitLong(r.socketOf(impostor2), m -> m.type().equals("IMPOSTOR_GUESS_OPEN"));
      for (String artist : artists2) {
        assertThat(r.socketOf(artist).ofType("IMPOSTOR_GUESS_OPEN")).isEmpty();
      }
      assertThat(
              r.refused(
                  r.socketOf(artists2.get(1)),
                  "IMPOSTOR_GUESS",
                  guessPhase.get("phaseId").asString(),
                  "{\"text\":\"%s\"}".formatted(r.word(2))))
          .isEqualTo("FORBIDDEN");
      // The impostor reconnects in GUESS: voteClosed names them, so a client knows it may guess.
      StompTestClient again = r.reconnect(impostor2);
      JsonNode guessView = r.snapshot(again);
      assertThat(guessView.get("phase").asString()).isEqualTo("GUESS");
      assertThat(guessView.get("voteClosed").get("impostorGuestId").asString())
          .isEqualTo(impostor2);
      assertThat(guessView.get("role").get("role").asString())
          .isEqualTo(unaware ? "ARTIST" : "IMPOSTOR");
      r.accepted(
          again,
          "IMPOSTOR_GUESS",
          guessPhase.get("phaseId").asString(),
          "{\"text\":\"%s\"}".formatted(r.word(2).toUpperCase()));
      JsonNode ended2 = r.awaitRoundEnded(2);
      assertThat(ended2.get("outcome").asString()).isEqualTo("GUESSED");
      assertThat(ended2.get("scoreDeltas").get(impostor2).asInt()).isEqualTo(2);
    } else {
      JsonNode ended2 = r.awaitRoundEnded(2);
      assertThat(ended2.get("outcome").asString()).isEqualTo("CAUGHT");
      assertThat(ended2.get("guess").isNull()).isTrue();
      assertThat(ended2.get("decoyWord").asString()).isEqualTo(r.decoy(2));
      for (String artist : artists2) {
        assertThat(ended2.get("scoreDeltas").get(artist).asInt()).isEqualTo(2);
      }
      for (StompTestClient ws : r.sockets) {
        assertThat(ws.ofType("IMPOSTOR_GUESS_OPEN")).isEmpty();
      }
      assertThat(
              r.history.stream()
                  .flatMap(List::stream)
                  .flatMap(s -> s.ofType("PHASE_STARTED").stream())
                  .filter(m -> m.payload().get("round").asInt() == 2)
                  .map(m -> m.payload().get("phase").asString()))
          .doesNotContain("GUESS");
    }

    // ---- round 3: caught, wrong guess if allowed ----
    JsonNode sketch3 = r.awaitPhase(3, "SKETCH");
    r.everyoneDraws(sketch3.get("phaseId").asString());
    r.everyoneSubmits(sketch3.get("phaseId").asString());
    String gallery3 = r.awaitPhase(3, "GALLERY").get("phaseId").asString();
    String impostor3 = r.impostor(3);
    r.everyoneVotes(gallery3, impostor3, r.artists(3).get(0));
    if (mayGuess) {
      JsonNode guess3 = r.awaitPhase(3, "GUESS");
      r.accepted(
          r.socketOf(impostor3),
          "IMPOSTOR_GUESS",
          guess3.get("phaseId").asString(),
          "{\"text\":\"definitely wrong\"}");
    }
    JsonNode ended3 = r.awaitRoundEnded(3);
    assertThat(ended3.get("outcome").asString()).isEqualTo("CAUGHT");

    // ---- results ----
    JsonNode finished = r.awaitAny(m -> m.type().equals("GAME_FINISHED")).payload();
    assertThat(finished.get("endReason").asString()).isEqualTo("COMPLETED");
    assertThat(finished.get("standings")).hasSize(5);
    assertThat(fieldNames(finished.get("standings").get(0).get("detail")))
        .containsExactlyInAnyOrder(
            "roundsAsImpostor", "escapes", "correctGuesses", "impostorsFound");
    String sessionId = finished.get("sessionId").asString();
    mvc.perform(
            get("/api/v1/sessions/" + sessionId + "/results")
                .header("Authorization", bearer(r.guests.getFirst())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rounds.length()").value(3))
        .andExpect(jsonPath("$.rounds[0].endReason").value("ESCAPED"))
        .andExpect(jsonPath("$.rounds[0].meta.impostorGuestId").value(impostor1))
        .andExpect(jsonPath("$.rounds[0].meta.word").value(r.word(1)))
        .andExpect(jsonPath("$.rounds[1].endReason").value(mayGuess ? "GUESSED" : "CAUGHT"))
        .andExpect(jsonPath("$.rounds[2].endReason").value("CAUGHT"))
        .andExpect(jsonPath("$.rounds[2].meta.accusedGuestId").value(impostor3));
    if (unaware) {
      mvc.perform(
              get("/api/v1/sessions/" + sessionId + "/results")
                  .header("Authorization", bearer(r.guests.getFirst())))
          .andExpect(jsonPath("$.rounds[2].meta.decoyWord").value(r.decoy(3)));
    }

    // ---- secrecy over everything every socket received ----
    for (List<StompTestClient> sockets : r.history) {
      for (StompTestClient ws : sockets) {
        assertNothingLeaked(r, ws, 3);
      }
    }

    // ---- rematch and a second start ----
    for (Guest g : r.guests) {
      r.socketOf(g.id().toString()).command(r.code, "REMATCH_VOTE", actionId(), "{\"vote\":true}");
    }
    r.awaitAny(
        m ->
            m.type().equals("ROOM_STATUS_CHANGED")
                && m.payload().get("reason").asString().equals("REMATCH"));
    r.sockets.getFirst().command(r.code, "START_GAME", actionId(), "{}");
    JsonNode restarted =
        r.awaitAny(
                m ->
                    m.type().equals("GAME_STARTED")
                        && !m.payload().get("sessionId").asString().equals(sessionId))
            .payload();
    assertThat(restarted.get("order")).hasSize(5);
    JsonNode fresh = r.snapshot(r.sockets.getFirst());
    assertThat(fresh.get("round").asInt()).isEqualTo(1);
    assertThat(fresh.get("votedGuestIds")).isEmpty();
    assertThat(fresh.get("recentChat")).isEmpty();
    assertThat(fresh.get("roundEnd").isNull()).isTrue();
    assertThat(fresh.get("gallery").isNull()).isTrue();
  }

  // ------------------------------------------------------------------ leaving

  @Test
  void aPlayerLeavingInSketchGalleryOrGuessIsDroppedOrEndsTheRoundAndFewerThanThreeEndsTheGame() {
    Room r = new Room("sketchy-saurus", 6, settings(3, Map.of()));
    r.start();

    // Round 1: the impostor leaves during SKETCH.
    JsonNode sketch1 = r.awaitPhase(1, "SKETCH");
    r.everyoneDraws(sketch1.get("phaseId").asString());
    String impostor1 = r.impostor(1);
    r.leave(impostor1);
    JsonNode ended1 = r.awaitRoundEnded(1);
    assertThat(ended1.get("outcome").asString()).isEqualTo("IMPOSTOR_LEFT");
    assertThat(ended1.get("impostorGuestId").asString()).isEqualTo(impostor1);
    ended1.get("scoreDeltas").forEach(v -> assertThat(v.asInt()).isZero());

    // Round 2 (five seated): an artist leaves in SKETCH, the rest submit; an artist leaves in
    // GALLERY; the caught impostor leaves in GUESS.
    JsonNode sketch2 = r.awaitPhase(2, "SKETCH");
    String phase2 = sketch2.get("phaseId").asString();
    String impostor2 = r.impostor(2);
    List<String> artists2 = r.artists(2);
    String sketchLeaver = artists2.get(0);
    r.everyoneDraws(phase2);
    r.leave(sketchLeaver);
    r.everyoneSubmits(phase2);
    JsonNode gallery2 = r.awaitPhase(2, "GALLERY");
    JsonNode opened2 =
        r.awaitAny(
                m ->
                    m.type().equals("SKETCHY_GALLERY_OPENED")
                        && m.payload().get("round").asInt() == 2)
            .payload();
    assertThat(opened2.get("drawings")).hasSize(4);
    assertThat(opened2.get("drawings").toString()).doesNotContain(sketchLeaver);

    String galleryLeaver = artists2.get(1);
    r.leave(galleryLeaver);
    r.everyoneVotes(gallery2.get("phaseId").asString(), impostor2, artists2.get(2));
    JsonNode guess2 = r.awaitPhase(2, "GUESS");
    assertThat(guess2.get("impostorGuestId").asString()).isEqualTo(impostor2);

    r.leave(impostor2);

    JsonNode ended2 = r.awaitRoundEnded(2);
    assertThat(ended2.get("outcome").asString()).isEqualTo("IMPOSTOR_LEFT");
    JsonNode finished = r.awaitAny(m -> m.type().equals("GAME_FINISHED")).payload();
    assertThat(finished.get("endReason").asString()).isEqualTo("INSUFFICIENT_PLAYERS");
  }

  // ------------------------------------------------------------------ unauthorized

  @Test
  void unauthorizedCommandsAreRefusedAndChangeNothing() {
    Room r = new Room("sketchy-saurus", 5, settings(3, Map.of()));
    r.start();
    JsonNode sketch = r.awaitPhase(1, "SKETCH");
    String phase = sketch.get("phaseId").asString();
    String impostor = r.impostor(1);
    List<String> artists = r.artists(1);
    String a = artists.get(0);
    String b = artists.get(1);

    // A guess or vote or chat while sketching is not legal.
    assertThat(r.refused(r.socketOf(a), "IMPOSTOR_VOTE", phase, "{\"targetGuestId\":null}"))
        .isEqualTo("INVALID_PHASE");
    assertThat(r.refused(r.socketOf(a), "IMPOSTOR_CHAT", phase, "{\"text\":\"hi\"}"))
        .isEqualTo("INVALID_PHASE");
    assertThat(r.refused(r.socketOf(a), "IMPOSTOR_GUESS", phase, "{\"text\":\"hi\"}"))
        .isEqualTo("INVALID_PHASE");
    // The Dino Impostor drawing command is not Sketchy's.
    assertThat(
            r.refused(
                r.socketOf(a),
                "IMPOSTOR_DRAW_STROKE",
                phase,
                stroke(UUID.randomUUID().toString(), 0, 2, true)))
        .isEqualTo("INVALID_ACTION");
    // A stroke with a bad payload.
    assertThat(r.refused(r.socketOf(a), "SKETCHY_DRAW_STROKE", phase, "{\"strokeId\":\"x\"}"))
        .isEqualTo("VALIDATION_FAILED");
    // A stroke carrying an earlier phase's id is stale.
    assertThat(
            r.refused(
                r.socketOf(a),
                "SKETCHY_DRAW_STROKE",
                UUID.randomUUID().toString(),
                stroke(UUID.randomUUID().toString(), 0, 2, true)))
        .isEqualTo("STALE_ACTION");

    // A member of another room cannot command this one.
    Guest outsider = newGuest();
    String otherCode =
        roomService
            .create(outsider.id(), new CreateRoomCommand("sketchy-saurus", null, null))
            .code();
    StompTestClient foreign = connectAndSubscribe(outsider, otherCode);
    foreign.command(
        r.code,
        "{\"type\":\"SKETCHY_SUBMIT\",\"actionId\":\"%s\",\"phaseId\":\"%s\",\"payload\":{}}"
            .formatted(actionId(), phase));
    assertThat(foreign.awaitType("ERROR").payload().get("code").asString())
        .isEqualTo("NOT_IN_ROOM");

    // A player who left the room is no longer on the roster of the game.
    String leaver = r.artists(1).get(2);
    StompTestClient leaverSocket = r.socketOf(leaver);
    r.leave(leaver);
    leaverSocket.command(
        r.code,
        "{\"type\":\"SKETCHY_SUBMIT\",\"actionId\":\"%s\",\"phaseId\":\"%s\",\"payload\":{}}"
            .formatted(actionId(), phase));
    assertThat(leaverSocket.awaitType("ERROR").payload().get("code").asString())
        .isEqualTo("NOT_IN_ROOM");

    // Nothing of any of that reached the others.
    StompTestClient watcher = r.socketOf(impostor);
    assertThat(watcher.ofType("IMPOSTOR_CHAT_MESSAGE")).isEmpty();
    assertThat(watcher.ofType("IMPOSTOR_VOTE_PROGRESS")).isEmpty();
    assertThat(watcher.ofType("SKETCHY_GALLERY_OPENED")).isEmpty();
    assertThat(b).isNotNull();

    // In GALLERY: a guess by an artist is forbidden once the impostor is caught.
    r.everyoneDraws(phase);
    r.everyoneSubmits(phase);
    String gallery = r.awaitPhase(1, "GALLERY").get("phaseId").asString();
    assertThat(r.refused(r.socketOf(a), "IMPOSTOR_GUESS", gallery, "{\"text\":\"anything\"}"))
        .isEqualTo("INVALID_PHASE");
  }

  // ------------------------------------------------------------------ two games at once

  @Test
  void aSketchyRoomAndADinoImpostorRoomPlayAtTheSameTimeAndEachFinishesOnItsOwn() {
    Room sketchy = new Room("sketchy-saurus", 6, settings(3, Map.of("impostorKnows", false)));
    Room dino = new Room("dino-impostor", 6, settings(3, Map.of("laps", 1)));
    sketchy.start();
    dino.start();

    // Each round of both rooms ends with its impostor leaving; both complete.
    for (int round = 1; round <= 3; round++) {
      sketchy.awaitPhase(round, "ROLE_REVEAL");
      dino.awaitPhase(round, "ROLE_REVEAL");
      sketchy.leave(sketchy.impostor(round));
      dino.leave(dino.impostor(round));
      assertThat(sketchy.awaitRoundEnded(round).get("outcome").asString())
          .isEqualTo("IMPOSTOR_LEFT");
      assertThat(dino.awaitRoundEnded(round).get("outcome").asString()).isEqualTo("IMPOSTOR_LEFT");
    }
    JsonNode finishedS = sketchy.awaitAny(m -> m.type().equals("GAME_FINISHED")).payload();
    JsonNode finishedD = dino.awaitAny(m -> m.type().equals("GAME_FINISHED")).payload();
    assertThat(finishedS.get("sessionId").asString())
        .isNotEqualTo(finishedD.get("sessionId").asString());

    // Neither room heard the other, and each game kept to its own message types.
    for (StompTestClient ws : sketchy.sockets) {
      assertThat(ws.messages())
          .allSatisfy(
              m -> {
                JsonNode code = m.envelope().get("roomCode");
                assertThat(code == null || code.isNull() || code.asString().equals(sketchy.code))
                    .isTrue();
                assertThat(m.type()).isNotEqualTo("IMPOSTOR_DRAWING_UPDATED");
              });
    }
    for (StompTestClient ws : dino.sockets) {
      assertThat(ws.messages())
          .allSatisfy(
              m -> {
                JsonNode code = m.envelope().get("roomCode");
                assertThat(code == null || code.isNull() || code.asString().equals(dino.code))
                    .isTrue();
                assertThat(m.type()).isNotIn("SKETCHY_PROGRESS", "SKETCHY_GALLERY_OPENED");
              });
    }
  }
}
