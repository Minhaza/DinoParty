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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Dino Impostor over real STOMP up to the end of the drawing turns (P9-02): roles only on private
 * streams, live strokes with their author, an out-of-turn stroke refused, and a reconnect in the
 * middle of a turn. The game is disabled in the catalog, so each test enables it and switches it
 * off again.
 */
@RealtimeIntegrationTest
class DinoImpostorIT extends GameEngineSupport {

  private static final Duration LONG = Duration.ofSeconds(25);

  @Autowired JdbcTemplate catalog;

  @BeforeEach
  void enableTheGame() {
    catalog.update("UPDATE game_definition SET enabled = true WHERE key = 'dino-impostor'");
  }

  @AfterEach
  void disableTheGame() {
    closeClients();
    catalog.update("UPDATE game_definition SET enabled = false WHERE key = 'dino-impostor'");
  }

  /** A Dino Impostor room; index 0 is the host. */
  private final class Room {
    final List<Guest> guests = new ArrayList<>();
    final List<StompTestClient> sockets = new ArrayList<>();
    final String code;

    Room(int players, Map<String, Object> settings) {
      Guest host = newGuest();
      code =
          roomService
              .create(host.id(), new CreateRoomCommand("dino-impostor", settings, null))
              .code();
      guests.add(host);
      for (int i = 1; i < players; i++) {
        Guest g = newGuest();
        roomService.join(g.id(), code);
        guests.add(g);
      }
      guests.forEach(g -> sockets.add(connectAndSubscribe(g, code)));
      remember();
    }

    void start() {
      sockets.getFirst().command(code, "START_GAME", actionId(), "{}");
      awaitLong(sockets.getFirst(), m -> m.type().equals("GAME_STARTED"));
    }

    StompTestClient socketOf(String guestId) {
      for (int i = 0; i < guests.size(); i++) {
        if (guests.get(i).id().toString().equals(guestId)) {
          return sockets.get(i);
        }
      }
      throw new AssertionError("unknown guest " + guestId);
    }

    /** The first DRAWING phase of turn {@code turn}, seen by {@code ws}. */
    JsonNode turn(StompTestClient ws, int turn) {
      return awaitLong(
              ws,
              m ->
                  m.type().equals("PHASE_STARTED")
                      && m.payload().get("phase").asString().equals("DRAWING")
                      && m.payload().get("turn").asInt() == turn)
          .payload();
    }

    JsonNode snapshot(StompTestClient ws) {
      int before = ws.ofType("ROOM_SNAPSHOT").size();
      ws.command(code, "REQUEST_SNAPSHOT", actionId(), "{}");
      await().atMost(Duration.ofSeconds(5)).until(() -> ws.ofType("ROOM_SNAPSHOT").size() > before);
      return ws.ofType("ROOM_SNAPSHOT").getLast().payload();
    }

    void send(StompTestClient ws, String phaseId, String payload, String actionId) {
      ws.command(
          code,
          "{\"type\":\"IMPOSTOR_DRAW_STROKE\",\"actionId\":\"%s\",\"phaseId\":\"%s\",\"payload\":%s}"
              .formatted(actionId, phaseId, payload));
    }

    StompTestClient reconnect(String guestId) {
      int index = sockets.indexOf(socketOf(guestId));
      sockets.get(index).drop();
      StompTestClient again = connectAndSubscribe(guests.get(index), code);
      sockets.set(index, again);
      history.get(index).add(again);
      return again;
    }

    // ---- P9-03: whole games ----

    /** Every socket a guest has had, so the private messages of dropped ones stay readable. */
    final List<List<StompTestClient>> history = new ArrayList<>();

    void remember() {
      if (history.isEmpty()) {
        sockets.forEach(s -> history.add(new ArrayList<>(List.of(s))));
      }
    }

    int indexOf(String guestId) {
      for (int i = 0; i < guests.size(); i++) {
        if (guests.get(i).id().toString().equals(guestId)) {
          return i;
        }
      }
      throw new AssertionError("unknown guest " + guestId);
    }

    JsonNode roleOf(int index, int round) {
      return history.get(index).stream()
          .flatMap(s -> s.ofType("IMPOSTOR_ROLE").stream())
          .map(StompTestClient.Message::payload)
          .filter(p -> p.get("round").asInt() == round)
          .findFirst()
          .orElseThrow();
    }

    String impostor(int round) {
      awaitRoles(round);
      for (int i = 0; i < guests.size(); i++) {
        boolean seated =
            history.get(i).stream()
                .flatMap(s -> s.ofType("IMPOSTOR_ROLE").stream())
                .anyMatch(m -> m.payload().get("round").asInt() == round);
        if (seated && roleOf(i, round).get("role").asString().equals("IMPOSTOR")) {
          return guests.get(i).id().toString();
        }
      }
      throw new AssertionError("no impostor in round " + round);
    }

    String word(int round) {
      awaitRoles(round);
      for (int i = 0; i < guests.size(); i++) {
        boolean seated =
            history.get(i).stream()
                .flatMap(s -> s.ofType("IMPOSTOR_ROLE").stream())
                .anyMatch(m -> m.payload().get("round").asInt() == round);
        if (seated && !roleOf(i, round).get("word").isNull()) {
          return roleOf(i, round).get("word").asString();
        }
      }
      throw new AssertionError("no word in round " + round);
    }

    /** Guest ids seated in {@code round} other than its impostor. */
    List<String> artists(int round) {
      awaitRoles(round);
      String impostor = impostor(round);
      List<String> artists = new ArrayList<>();
      for (int i = 0; i < guests.size(); i++) {
        String id = guests.get(i).id().toString();
        boolean seated =
            history.get(i).stream()
                .flatMap(s -> s.ofType("IMPOSTOR_ROLE").stream())
                .anyMatch(m -> m.payload().get("round").asInt() == round);
        if (seated && !id.equals(impostor)) {
          artists.add(id);
        }
      }
      return artists;
    }

    /** Waits until any socket this room's guests ever had has received a message that matches. */
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

    JsonNode awaitTurn(int round, int turn) {
      return awaitAny(
              m ->
                  m.type().equals("PHASE_STARTED")
                      && m.payload().get("phase").asString().equals("DRAWING")
                      && m.payload().get("round").asInt() == round
                      && m.payload().get("turn").asInt() == turn)
          .payload();
    }

    JsonNode awaitRoundEnded(int round) {
      return awaitAny(
              m ->
                  m.type().equals("IMPOSTOR_ROUND_ENDED")
                      && m.payload().get("round").asInt() == round)
          .payload();
    }

    /** Waits until every player seated in {@code round} has received their role. */
    void awaitRoles(int round) {
      int seated = awaitPhase(round, "ROLE_REVEAL").get("seatOrder").size();
      await()
          .atMost(LONG)
          .until(
              () ->
                  history.stream()
                          .filter(
                              h ->
                                  h.stream()
                                      .flatMap(s -> s.ofType("IMPOSTOR_ROLE").stream())
                                      .anyMatch(m -> m.payload().get("round").asInt() == round))
                          .count()
                      == seated);
    }

    void cmd(StompTestClient ws, String type, String phaseId, String payload, String actionId) {
      ws.command(
          code,
          "{\"type\":\"%s\",\"actionId\":\"%s\",\"phaseId\":\"%s\",\"payload\":%s}"
              .formatted(type, actionId, phaseId, payload));
    }

    /** Sends a command and waits for its error; the command must be refused. */
    String refused(StompTestClient ws, String type, String phaseId, String payload) {
      String id = actionId();
      cmd(ws, type, phaseId, payload, id);
      return errorCode(ws, id);
    }

    /** Sends a command and waits for its acknowledgement. */
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
    void everyoneVotes(
        int round, String phaseId, String accused, String other, List<String> excluded) {
      List<String> seated = new ArrayList<>(artists(round));
      seated.add(impostor(round));
      for (String g : seated) {
        if (!excluded.contains(g)) {
          vote(g, phaseId, g.equals(accused) ? other : accused);
        }
      }
    }

    /**
     * Every drawing turn of {@code round}: the drawer's one final batch ends the turn. With {@code
     * reconnectMidStroke} the first drawer who is not the host leaves an open stroke, drops and
     * reconnects, and finds it in {@code turnStroke} (G4) before ending it.
     */
    void drawRound(int round, JsonNode colors, boolean reconnectMidStroke) {
      JsonNode first = awaitTurn(round, 0);
      int total = first.get("totalTurns").asInt();
      boolean pending = reconnectMidStroke;
      String hostId = guests.getFirst().id().toString();
      for (int t = 0; t < total; t++) {
        JsonNode p = t == 0 ? first : awaitTurn(round, t);
        String drawer = p.get("drawerGuestId").asString();
        String phaseId = p.get("phaseId").asString();
        String color = colors.get(drawer).asString();
        String strokeId = UUID.randomUUID().toString();
        int nextBatch = 0;
        if (pending && !drawer.equals(hostId)) {
          pending = false;
          String open = actionId();
          send(socketOf(drawer), phaseId, stroke(strokeId, 0, color, 3, false), open);
          awaitAck(socketOf(drawer), open);
          StompTestClient again = reconnect(drawer);
          JsonNode turnStroke = snapshot(again).get("game").get("turnStroke");
          assertThat(turnStroke.get("strokeId").asString()).isEqualTo(strokeId);
          assertThat(turnStroke.get("nextBatchNo").asInt()).isEqualTo(1);
          assertThat(turnStroke.get("closed").asBoolean()).isFalse();
          nextBatch = turnStroke.get("nextBatchNo").asInt();
        }
        String id = actionId();
        send(
            socketOf(drawer),
            phaseId,
            stroke(strokeId, nextBatch, color, nextBatch == 0 ? 3 : 2, true),
            id);
        awaitAck(socketOf(drawer), id);
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

  private static String stroke(
      String strokeId, int batchNo, String color, int points, boolean last) {
    String pts =
        IntStream.range(0, points)
            .mapToObj(i -> "[%d,5000]".formatted(1000 + 10 * i))
            .collect(Collectors.joining(","));
    return ("{\"strokeId\":\"%s\",\"batchNo\":%d,\"tool\":\"PENCIL\",\"color\":\"%s\","
            + "\"size\":2,\"points\":[%s],\"final\":%s}")
        .formatted(strokeId, batchNo, color, pts, last);
  }

  private static Set<String> fieldNames(JsonNode node) {
    Set<String> names = new HashSet<>();
    node.propertyNames().forEach(names::add);
    return names;
  }

  @Test
  void aFourPlayerRoundRunsThroughItsDrawingTurnsWithPrivateRolesAndLiveAuthoredStrokes() {
    Room r = new Room(4, Map.of("rounds", 3, "laps", 1));
    StompTestClient host = r.sockets.getFirst();
    r.start();

    // [DI-4]: one IMPOSTOR_ROLE per player, on the private stream only, of one shape.
    for (StompTestClient ws : r.sockets) {
      awaitLong(ws, m -> m.type().equals("IMPOSTOR_ROLE"));
    }
    List<JsonNode> roles = new ArrayList<>();
    for (StompTestClient ws : r.sockets) {
      List<StompTestClient.Message> own = ws.ofType("IMPOSTOR_ROLE");
      assertThat(own).hasSize(1);
      assertThat(own.getFirst().envelope().get("stream").asString()).isEqualTo("PRIVATE");
      roles.add(own.getFirst().payload());
      assertThat(ws.onStream("ROOM", r.code).stream().map(StompTestClient.Message::type))
          .doesNotContain("IMPOSTOR_ROLE");
    }
    assertThat(roles.stream().map(DinoImpostorIT::fieldNames).collect(Collectors.toSet()))
        .containsExactly(Set.of("phaseId", "round", "role", "category", "word"));
    long impostors =
        roles.stream().filter(p -> p.get("role").asString().equals("IMPOSTOR")).count();
    assertThat(impostors).isEqualTo(1);
    JsonNode impostorRole =
        roles.stream().filter(p -> p.get("role").asString().equals("IMPOSTOR")).findFirst().get();
    assertThat(impostorRole.has("word")).isTrue(); // the same key, present with a null value
    assertThat(impostorRole.get("word").isNull()).isTrue();
    String word =
        roles.stream()
            .filter(p -> !p.get("word").isNull())
            .map(p -> p.get("word").asString())
            .findFirst()
            .orElseThrow();
    // Nothing on any public stream or any snapshot of another player names the word.
    for (StompTestClient ws : r.sockets) {
      assertThat(ws.onStream("ROOM", r.code).toString()).doesNotContain(word);
    }

    JsonNode first = r.turn(host, 0);
    List<String> seats = new ArrayList<>();
    first.get("seatOrder").forEach(n -> seats.add(n.asString()));
    assertThat(first.get("totalTurns").asInt()).isEqualTo(4);
    assertThat(first.get("lap").asInt()).isEqualTo(1);

    String drawer0 = first.get("drawerGuestId").asString();
    assertThat(drawer0).isEqualTo(seats.get(0));
    JsonNode game = r.snapshot(r.socketOf(drawer0)).get("game");
    String color = game.get("colors").get(drawer0).asString();
    assertThat(color).matches("#[0-9A-F]{6}");

    // The drawer draws: live batch with its author on every client, then the final one.
    String strokeId = UUID.randomUUID().toString();
    String phase0 = first.get("phaseId").asString();
    StompTestClient ws0 = r.socketOf(drawer0);
    String id = actionId();
    r.send(ws0, phase0, stroke(strokeId, 0, color, 3, false), id);
    awaitAck(ws0, id);
    for (StompTestClient ws : r.sockets) {
      JsonNode update = awaitLong(ws, m -> m.type().equals("IMPOSTOR_DRAWING_UPDATED")).payload();
      assertThat(update.get("authorGuestId").asString()).isEqualTo(drawer0);
      assertThat(update.get("strokeId").asString()).isEqualTo(strokeId);
      assertThat(update.get("color").asString()).isEqualTo(color);
      assertThat(update.get("final").asBoolean()).isFalse();
    }

    // An out-of-turn stroke is refused and published to nobody.
    String other = seats.get(2);
    StompTestClient wsOther = r.socketOf(other);
    JsonNode otherGame = r.snapshot(wsOther).get("game");
    id = actionId();
    r.send(
        wsOther,
        phase0,
        stroke(
            UUID.randomUUID().toString(),
            0,
            otherGame.get("colors").get(other).asString(),
            2,
            true),
        id);
    assertThat(errorCode(wsOther, id)).isEqualTo("NOT_DRAWER");
    assertThat(host.ofType("IMPOSTOR_DRAWING_UPDATED")).hasSize(1);

    // A reconnect in the middle of the turn restores role, turn, canvas and colours.
    String viewer = seats.get(3);
    StompTestClient again = r.reconnect(viewer);
    JsonNode restored = r.snapshot(again).get("game");
    assertThat(restored.get("phase").asString()).isEqualTo("DRAWING");
    assertThat(restored.get("phaseId").asString()).isEqualTo(phase0);
    assertThat(restored.get("turn").asInt()).isZero();
    assertThat(restored.get("lap").asInt()).isEqualTo(1);
    assertThat(restored.get("drawerGuestId").asString()).isEqualTo(drawer0);
    assertThat(restored.get("deadlineAt").isNull()).isFalse();
    assertThat(restored.get("colors").get(drawer0).asString()).isEqualTo(color);
    assertThat(restored.get("strokes")).hasSize(1);
    assertThat(restored.get("strokes").get(0).get("authorGuestId").asString()).isEqualTo(drawer0);
    assertThat(restored.get("strokes").get(0).get("closed").asBoolean()).isFalse();
    assertThat(restored.get("strokes").get(0).get("points")).hasSize(3);
    JsonNode role = restored.get("role");
    JsonNode sent =
        roles.get(
            IntStream.range(0, 4)
                .filter(i -> r.guests.get(i).id().toString().equals(viewer))
                .findFirst()
                .orElseThrow());
    assertThat(role.get("role").asString()).isEqualTo(sent.get("role").asString());
    assertThat(role.get("word")).isEqualTo(sent.get("word"));
    assertThat(restored.has("votedGuestIds")).isTrue();
    assertThat(restored.get("votedGuestIds")).isEmpty();
    assertThat(restored.get("myVote").isNull()).isTrue();

    // The drawer finishes the stroke, which ends the turn at once.
    id = actionId();
    r.send(ws0, phase0, stroke(strokeId, 1, color, 2, true), id);
    awaitAck(ws0, id);
    // Not `host`: the reconnected viewer is a random seat and may be the host's socket.
    JsonNode second = r.turn(ws0, 1);
    assertThat(second.get("drawerGuestId").asString()).isEqualTo(seats.get(1));
    assertThat(second.get("phaseId").asString()).isNotEqualTo(phase0);

    // The old turn's phase id is stale for everybody.
    StompTestClient ws1 = r.socketOf(seats.get(1));
    id = actionId();
    r.send(
        ws1,
        phase0,
        stroke(
            UUID.randomUUID().toString(),
            0,
            r.snapshot(ws1).get("game").get("colors").get(seats.get(1)).asString(),
            2,
            true),
        id);
    assertThat(errorCode(ws1, id)).isEqualTo("STALE_ACTION");
  }

  // ------------------------------------------------------------------ P9-03: whole games

  private static String json(StompTestClient.Message m) {
    return m.envelope().toString();
  }

  /** The public room messages a socket received in the part of a round before {@code until}. */
  private static List<StompTestClient.Message> publicOf(StompTestClient ws, String code) {
    return ws.onStream("ROOM", code);
  }

  /**
   * R35 over the whole recorded traffic of one socket: before {@code IMPOSTOR_ROUND_ENDED} of a
   * round no public message carries its word, before {@code IMPOSTOR_VOTE_CLOSED} (when caught) or
   * the round end none names the impostor, and before {@code IMPOSTOR_VOTE_CLOSED} none carries a
   * vote target.
   */
  private void assertNothingLeaked(Room r, StompTestClient ws, int rounds) {
    List<StompTestClient.Message> all = publicOf(ws, r.code);
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
      for (int i = start; i < all.size() && i < ended; i++) {
        String text = json(all.get(i));
        assertThat(text).as("word in %s", all.get(i).type()).doesNotContainIgnoringCase(word);
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

  @Test
  void aFivePlayerGameRunsFromStartToResultsAndRematchWithReconnectsAndNoLeaks() throws Exception {
    Room r = new Room(5, Map.of("rounds", 3, "laps", 1, "voteSeconds", 30, "guessSeconds", 15));
    r.start();

    // ---- round 1: escaped ----
    JsonNode reveal1 = r.awaitPhase(1, "ROLE_REVEAL");
    // G1: the reveal carries the seats and colours of a client that never takes a snapshot.
    assertThat(reveal1.get("seatOrder")).hasSize(5);
    assertThat(reveal1.get("colors")).hasSize(5);
    JsonNode colors = reveal1.get("colors");
    // Reconnect during ROLE_REVEAL.
    StompTestClient inReveal = r.reconnect(r.guests.get(3).id().toString());
    JsonNode revealView = r.snapshot(inReveal).get("game");
    assertThat(revealView.get("phase").asString()).isEqualTo("ROLE_REVEAL");
    assertThat(revealView.get("role").get("role").asString()).isIn("ARTIST", "IMPOSTOR");
    assertThat(revealView.get("nicknames")).hasSize(5);

    r.drawRound(1, colors, false);
    JsonNode vote1 = r.awaitPhase(1, "VOTE");
    String phase1 = vote1.get("phaseId").asString();
    String impostor1 = r.impostor(1);
    String hostId = r.guests.getFirst().id().toString();
    List<String> nonHost = r.artists(1).stream().filter(g -> !g.equals(hostId)).toList();
    String n0 = nonHost.get(0);
    String n1 = nonHost.get(1);

    r.accepted(r.socketOf(n1), "IMPOSTOR_CHAT", phase1, "{\"text\":\"that looks scaly\"}");
    for (StompTestClient ws : r.sockets) {
      JsonNode line = awaitLong(ws, m -> m.type().equals("IMPOSTOR_CHAT_MESSAGE")).payload();
      assertThat(line.get("guestId").asString()).isEqualTo(n1);
      assertThat(line.get("text").asString()).isEqualTo("that looks scaly");
    }
    // A self-vote is refused ([DI-9]) and a vote is private until the close.
    assertThat(
            r.refused(
                r.socketOf(n1),
                "IMPOSTOR_VOTE",
                phase1,
                "{\"targetGuestId\":\"%s\"}".formatted(n1)))
        .isEqualTo("VALIDATION_FAILED");
    r.vote(n1, phase1, n0);
    StompTestClient inVote = r.reconnect(n1);
    JsonNode voteView = r.snapshot(inVote).get("game");
    assertThat(voteView.get("phase").asString()).isEqualTo("VOTE");
    assertThat(voteView.get("recentChat")).hasSize(1);
    assertThat(voteView.get("votedGuestIds")).hasSize(1);
    assertThat(voteView.get("votedGuestIds").get(0).asString()).isEqualTo(n1);
    assertThat(voteView.get("myVote").asString()).isEqualTo(n0);
    r.everyoneVotes(1, phase1, n0, n1, List.of(n1));

    JsonNode ended1 = r.awaitRoundEnded(1);
    assertThat(ended1.get("outcome").asString()).isEqualTo("ESCAPED");
    assertThat(ended1.get("impostorGuestId").asString()).isEqualTo(impostor1);
    assertThat(ended1.get("word").asString()).isEqualTo(r.word(1));
    assertThat(ended1.get("decoyWord").isNull()).isTrue();
    assertThat(ended1.get("scoreDeltas").get(impostor1).asInt()).isEqualTo(2);
    StompTestClient inEnd = r.reconnect(r.guests.get(2).id().toString());
    JsonNode endView = r.snapshot(inEnd).get("game");
    assertThat(endView.get("phase").asString()).isEqualTo("ROUND_END");
    assertThat(endView.get("roundEnd").get("outcome").asString()).isEqualTo("ESCAPED");
    assertThat(endView.get("voteClosed").get("caught").asBoolean()).isFalse();

    // ---- round 2: caught, the impostor guesses right ----
    JsonNode reveal2 = r.awaitPhase(2, "ROLE_REVEAL");
    r.drawRound(2, reveal2.get("colors"), true); // G4: a drawer reconnects mid-stroke
    JsonNode vote2 = r.awaitPhase(2, "VOTE");
    String phase2 = vote2.get("phaseId").asString();
    String impostor2 = r.impostor(2);
    List<String> artists2 = r.artists(2);
    r.everyoneVotes(2, phase2, impostor2, artists2.get(0), List.of());

    JsonNode guessPhase = r.awaitPhase(2, "GUESS");
    assertThat(guessPhase.get("impostorGuestId").asString()).isEqualTo(impostor2);
    JsonNode closed2 =
        r.awaitAny(
                m ->
                    m.type().equals("IMPOSTOR_VOTE_CLOSED")
                        && m.payload().get("round").asInt() == 2)
            .payload();
    assertThat(closed2.get("caught").asBoolean()).isTrue();
    assertThat(closed2.get("impostorGuestId").asString()).isEqualTo(impostor2);
    // Only the impostor was offered the guess; an artist who tries is refused.
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
    // The impostor reconnects in GUESS and still can guess: the snapshot tells them so.
    StompTestClient impostorAgain = r.reconnect(impostor2);
    JsonNode guessView = r.snapshot(impostorAgain).get("game");
    assertThat(guessView.get("phase").asString()).isEqualTo("GUESS");
    assertThat(guessView.get("role").get("role").asString()).isEqualTo("IMPOSTOR");
    assertThat(guessView.get("voteClosed").get("caught").asBoolean()).isTrue();
    r.accepted(
        impostorAgain,
        "IMPOSTOR_GUESS",
        guessPhase.get("phaseId").asString(),
        "{\"text\":\"%s\"}".formatted(r.word(2).toUpperCase()));
    JsonNode ended2 = r.awaitRoundEnded(2);
    assertThat(ended2.get("outcome").asString()).isEqualTo("GUESSED");
    assertThat(ended2.get("scoreDeltas").get(impostor2).asInt()).isEqualTo(2);

    // ---- round 3: caught, the guess is wrong ----
    JsonNode reveal3 = r.awaitPhase(3, "ROLE_REVEAL");
    r.drawRound(3, reveal3.get("colors"), false);
    JsonNode vote3 = r.awaitPhase(3, "VOTE");
    String impostor3 = r.impostor(3);
    r.everyoneVotes(3, vote3.get("phaseId").asString(), impostor3, r.artists(3).get(0), List.of());
    JsonNode guess3 = r.awaitPhase(3, "GUESS");
    r.accepted(
        r.socketOf(impostor3),
        "IMPOSTOR_GUESS",
        guess3.get("phaseId").asString(),
        "{\"text\":\"definitely wrong\"}");
    JsonNode ended3 = r.awaitRoundEnded(3);
    assertThat(ended3.get("outcome").asString()).isEqualTo("CAUGHT");
    assertThat(ended3.get("guess").asString()).isEqualTo("definitely wrong");
    for (String artist : r.artists(3)) {
      assertThat(ended3.get("scoreDeltas").get(artist).asInt()).isEqualTo(2);
    }

    // ---- results ----
    JsonNode finished = r.awaitAny(m -> m.type().equals("GAME_FINISHED")).payload();
    assertThat(finished.get("endReason").asString()).isEqualTo("COMPLETED");
    assertThat(finished.get("standings")).hasSize(5);
    JsonNode detail = finished.get("standings").get(0).get("detail");
    assertThat(fieldNames(detail))
        .containsExactlyInAnyOrder(
            "roundsAsImpostor", "escapes", "correctGuesses", "impostorsFound");
    String sessionId = finished.get("sessionId").asString();
    Guest member = r.guests.getFirst();
    mvc.perform(
            get("/api/v1/sessions/" + sessionId + "/results")
                .header("Authorization", bearer(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rounds.length()").value(3))
        .andExpect(jsonPath("$.rounds[0].endReason").value("ESCAPED"))
        .andExpect(jsonPath("$.rounds[0].meta.impostorGuestId").value(impostor1))
        .andExpect(jsonPath("$.rounds[0].meta.word").value(r.word(1)))
        .andExpect(jsonPath("$.rounds[1].endReason").value("GUESSED"))
        .andExpect(jsonPath("$.rounds[2].endReason").value("CAUGHT"))
        .andExpect(jsonPath("$.rounds[2].meta.accusedGuestId").value(impostor3));

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
    JsonNode fresh = r.snapshot(r.sockets.getFirst()).get("game");
    assertThat(fresh.get("round").asInt()).isEqualTo(1);
    assertThat(fresh.get("votedGuestIds")).isEmpty();
    assertThat(fresh.get("recentChat")).isEmpty();
    assertThat(fresh.get("roundEnd").isNull()).isTrue();
  }

  @Test
  void anImpostorOrArtistLeavingEndsTheRoundOrIsDroppedAndFewerThanThreeEndsTheGame() {
    Room r = new Room(5, Map.of("rounds", 3, "laps", 1, "voteSeconds", 30, "guessSeconds", 15));
    r.start();

    // Round 1: the impostor leaves during DRAWING.
    JsonNode reveal1 = r.awaitPhase(1, "ROLE_REVEAL");
    r.awaitTurn(1, 0);
    String impostor1 = r.impostor(1);
    roomService.leave(UUID.fromString(impostor1), r.code);
    JsonNode ended1 = r.awaitRoundEnded(1);
    assertThat(ended1.get("outcome").asString()).isEqualTo("IMPOSTOR_LEFT");
    assertThat(ended1.get("impostorGuestId").asString()).isEqualTo(impostor1);
    assertThat(ended1.get("guess").isNull()).isTrue();
    ended1.get("scoreDeltas").forEach(v -> assertThat(v.asInt()).isZero());
    assertThat(reveal1.get("seatOrder")).hasSize(5);

    // Round 2: four seated; an artist leaves in the vote; the impostor then leaves in the guess.
    JsonNode reveal2 = r.awaitPhase(2, "ROLE_REVEAL");
    assertThat(reveal2.get("seatOrder")).hasSize(4);
    r.drawRound(2, reveal2.get("colors"), false);
    JsonNode vote2 = r.awaitPhase(2, "VOTE");
    String phase2 = vote2.get("phaseId").asString();
    String impostor2 = r.impostor(2);
    List<String> artists2 = r.artists(2);
    String leaver = artists2.get(0);
    roomService.leave(UUID.fromString(leaver), r.code);
    r.everyoneVotes(2, phase2, impostor2, artists2.get(1), List.of(leaver));
    JsonNode guess2 = r.awaitPhase(2, "GUESS");
    assertThat(guess2.get("impostorGuestId").asString()).isEqualTo(impostor2);

    roomService.leave(UUID.fromString(impostor2), r.code);

    JsonNode ended2 = r.awaitRoundEnded(2);
    assertThat(ended2.get("outcome").asString()).isEqualTo("IMPOSTOR_LEFT");
    JsonNode finished = r.awaitAny(m -> m.type().equals("GAME_FINISHED")).payload();
    assertThat(finished.get("endReason").asString()).isEqualTo("INSUFFICIENT_PLAYERS");
  }

  @Test
  void unauthorizedCommandsAreRefusedAndChangeNothing() {
    Room r = new Room(4, Map.of("rounds", 3, "laps", 1));
    r.start();
    JsonNode reveal = r.awaitPhase(1, "ROLE_REVEAL");
    JsonNode turn0 = r.awaitTurn(1, 0);
    String phase = turn0.get("phaseId").asString();
    String drawer = turn0.get("drawerGuestId").asString();
    String other =
        r.guests.stream()
            .map(g -> g.id().toString())
            .filter(g -> !g.equals(drawer))
            .findFirst()
            .orElseThrow();
    StompTestClient watcher = r.sockets.getFirst();
    int updates = watcher.ofType("IMPOSTOR_DRAWING_UPDATED").size();

    // A stroke out of turn.
    String id = actionId();
    r.send(
        r.socketOf(other),
        phase,
        stroke(
            UUID.randomUUID().toString(), 0, reveal.get("colors").get(other).asString(), 2, true),
        id);
    assertThat(errorCode(r.socketOf(other), id)).isEqualTo("NOT_DRAWER");
    // Voting, chatting and guessing are not legal while drawing.
    assertThat(r.refused(r.socketOf(other), "IMPOSTOR_VOTE", phase, "{\"targetGuestId\":null}"))
        .isEqualTo("INVALID_PHASE");
    assertThat(r.refused(r.socketOf(other), "IMPOSTOR_CHAT", phase, "{\"text\":\"hi\"}"))
        .isEqualTo("INVALID_PHASE");
    assertThat(r.refused(r.socketOf(other), "IMPOSTOR_GUESS", phase, "{\"text\":\"hi\"}"))
        .isEqualTo("INVALID_PHASE");

    // A member of another room cannot command this one.
    Guest outsider = newGuest();
    String otherCode =
        roomService
            .create(outsider.id(), new CreateRoomCommand("dino-impostor", null, null))
            .code();
    StompTestClient foreign = connectAndSubscribe(outsider, otherCode);
    foreign.command(
        r.code,
        "{\"type\":\"IMPOSTOR_VOTE\",\"actionId\":\"%s\",\"phaseId\":\"%s\",\"payload\":{\"targetGuestId\":null}}"
            .formatted(actionId(), phase));
    assertThat(foreign.awaitType("ERROR").payload().get("code").asString())
        .isEqualTo("NOT_IN_ROOM");

    // A player who left the room is no longer on the roster of the game.
    String leaver = r.guests.get(3).id().toString();
    StompTestClient leaverSocket = r.socketOf(leaver);
    roomService.leave(UUID.fromString(leaver), r.code);
    leaverSocket.command(
        r.code,
        "{\"type\":\"IMPOSTOR_CHAT\",\"actionId\":\"%s\",\"phaseId\":\"%s\",\"payload\":{\"text\":\"hi\"}}"
            .formatted(actionId(), phase));
    assertThat(leaverSocket.awaitType("ERROR").payload().get("code").asString())
        .isEqualTo("NOT_IN_ROOM");

    assertThat(watcher.ofType("IMPOSTOR_DRAWING_UPDATED")).hasSize(updates);
    assertThat(watcher.ofType("IMPOSTOR_CHAT_MESSAGE")).isEmpty();
    assertThat(watcher.ofType("IMPOSTOR_VOTE_PROGRESS")).isEmpty();
  }

  @Test
  void twoRoomsPlayAtTheSameTimeAndEachFinishesOnItsOwn() {
    Room a = new Room(6, Map.of("rounds", 3, "laps", 1));
    Room b = new Room(6, Map.of("rounds", 3, "laps", 1));
    a.start();
    b.start();

    // Room B: round 1 ends with its impostor leaving; then too many leave and it finishes at once.
    b.awaitPhase(1, "ROLE_REVEAL");
    String impostorB = b.impostor(1);
    roomService.leave(UUID.fromString(impostorB), b.code);
    assertThat(b.awaitRoundEnded(1).get("outcome").asString()).isEqualTo("IMPOSTOR_LEFT");
    List<String> leaving = new ArrayList<>(b.artists(1));
    leaving.remove(b.guests.getFirst().id().toString());
    for (int i = 0; i < 3; i++) {
      roomService.leave(UUID.fromString(leaving.get(i)), b.code);
    }
    JsonNode finishedB = b.awaitAny(m -> m.type().equals("GAME_FINISHED")).payload();
    assertThat(finishedB.get("endReason").asString()).isEqualTo("INSUFFICIENT_PLAYERS");
    assertThat(a.sockets.stream().flatMap(w -> w.ofType("GAME_FINISHED").stream()))
        .isEmpty(); // A is still playing

    // Room A: each of its three rounds ends with the impostor leaving, then it completes.
    for (int round = 1; round <= 3; round++) {
      a.awaitPhase(round, "ROLE_REVEAL");
      roomService.leave(UUID.fromString(a.impostor(round)), a.code);
      assertThat(a.awaitRoundEnded(round).get("outcome").asString()).isEqualTo("IMPOSTOR_LEFT");
    }
    JsonNode finishedA = a.awaitAny(m -> m.type().equals("GAME_FINISHED")).payload();
    assertThat(finishedA.get("endReason").asString()).isEqualTo("COMPLETED");
    assertThat(finishedA.get("sessionId").asString())
        .isNotEqualTo(finishedB.get("sessionId").asString());

    // No event of one room reached the other room's sockets.
    for (StompTestClient ws : a.sockets) {
      assertThat(ws.messages())
          .allSatisfy(
              m -> {
                JsonNode code = m.envelope().get("roomCode");
                assertThat(code == null || code.isNull() || code.asString().equals(a.code))
                    .isTrue();
              });
      // A player who left does not hear the end of the game; nobody hears it twice.
      assertThat(ws.ofType("GAME_FINISHED")).hasSizeLessThanOrEqualTo(1);
      ws.ofType("GAME_STARTED")
          .forEach(
              m ->
                  assertThat(m.payload().get("sessionId").asString())
                      .isEqualTo(finishedA.get("sessionId").asString()));
    }
    for (StompTestClient ws : b.sockets) {
      assertThat(ws.messages())
          .allSatisfy(
              m -> {
                JsonNode code = m.envelope().get("roomCode");
                assertThat(code == null || code.isNull() || code.asString().equals(b.code))
                    .isTrue();
              });
      assertThat(ws.ofType("IMPOSTOR_ROUND_ENDED")).hasSizeLessThanOrEqualTo(1);
      ws.ofType("GAME_STARTED")
          .forEach(
              m ->
                  assertThat(m.payload().get("sessionId").asString())
                      .isEqualTo(finishedB.get("sessionId").asString()));
    }
  }
}
