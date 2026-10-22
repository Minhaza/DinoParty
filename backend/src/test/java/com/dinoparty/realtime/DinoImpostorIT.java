package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
      return again;
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
    JsonNode second = r.turn(host, 1);
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
}
