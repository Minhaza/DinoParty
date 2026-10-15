package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dinoparty.games.telephone.TelephoneSettings;
import com.dinoparty.room.CreateRoomCommand;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dino Telephone steps end to end over real STOMP with the registered module (P6-02): a full
 * four-player game with the secrecy of [TP-8], role/phase/finality errors, draft restore, the
 * maximal drawing task, and the settings defaults against the catalog row. The reveal and the album
 * endpoints are P6-03.
 */
@RealtimeIntegrationTest
class TelephoneIT extends GameEngineSupport {

  private static final Duration LONG = Duration.ofSeconds(20);

  /** A telephone room; index 0 is the host. */
  private final class Room {
    final List<Guest> guests = new ArrayList<>();
    final List<StompTestClient> sockets = new ArrayList<>();
    final String code;
    List<String> order = new ArrayList<>();

    Room(int players) {
      Guest host = newGuest();
      code =
          roomService.create(host.id(), new CreateRoomCommand("telephone", Map.of(), null)).code();
      guests.add(host);
      for (int i = 1; i < players; i++) {
        Guest g = newGuest();
        roomService.join(g.id(), code);
        guests.add(g);
      }
      guests.forEach(g -> sockets.add(connectAndSubscribe(g, code)));
    }

    /** Starts the game and returns the shuffled order. */
    void start() {
      sockets.get(0).command(code, "START_GAME", actionId(), "{}");
      JsonNode started = awaitLong(sockets.get(0), m -> m.type().equals("GAME_STARTED")).payload();
      started.get("order").forEach(n -> order.add(n.asString()));
    }

    StompTestClient socketOf(String guestId) {
      for (int i = 0; i < guests.size(); i++) {
        if (guests.get(i).id().toString().equals(guestId)) {
          return sockets.get(i);
        }
      }
      throw new AssertionError("unknown guest");
    }

    /** The player holding chain {@code chain} in {@code step} (chain c is owned by order[c]). */
    String holder(int chain, int step) {
      return order.get((chain + step) % order.size());
    }

    void send(StompTestClient ws, String type, String phaseId, String payload, String actionId) {
      ws.command(
          code,
          "{\"type\":\"%s\",\"actionId\":\"%s\",\"phaseId\":\"%s\",\"payload\":%s}"
              .formatted(type, actionId, phaseId, payload));
    }

    void sendAcked(StompTestClient ws, String type, String phaseId, String payload) {
      String id = actionId();
      send(ws, type, phaseId, payload, id);
      awaitAck(ws, id);
    }

    JsonNode snapshot(StompTestClient ws) {
      int before = ws.ofType("ROOM_SNAPSHOT").size();
      ws.command(code, "REQUEST_SNAPSHOT", actionId(), "{}");
      await().atMost(Duration.ofSeconds(5)).until(() -> ws.ofType("ROOM_SNAPSHOT").size() > before);
      return ws.ofType("ROOM_SNAPSHOT").getLast().payload();
    }

    /** The private task of {@code step}, waiting for it. */
    JsonNode task(StompTestClient ws, int step) {
      return awaitLong(
              ws, m -> m.type().equals("TELEPHONE_TASK") && m.payload().get("step").asInt() == step)
          .payload();
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
        IntStream.range(0, points).mapToObj(i -> "[10000,10000]").collect(Collectors.joining(","));
    return ("{\"strokeId\":\"%s\",\"batchNo\":%d,\"tool\":\"PENCIL\",\"color\":\"#336699\","
            + "\"size\":2,\"points\":[%s],\"final\":%s}")
        .formatted(strokeId, batchNo, pts, last);
  }

  private static boolean carries(StompTestClient.Message m, String word) {
    return m.envelope().toString().contains(word);
  }

  // ------------------------------------------------------------------ full game

  @Test
  void aFourPlayerGameOverStompKeepsEveryEntryWithItsNextHolder() {
    Room r = new Room(4);
    r.start();
    StompTestClient host = r.sockets.get(0);

    // step 0: each player writes a prompt
    for (int i = 0; i < 4; i++) {
      StompTestClient ws = r.socketOf(r.order.get(i));
      JsonNode task = r.task(ws, 0);
      assertThat(task.get("kind").asString()).isEqualTo("PROMPT");
      assertThat(task.get("source").isNull()).isTrue();
      r.sendAcked(
          ws,
          "TELEPHONE_SUBMIT_TEXT",
          task.get("phaseId").asString(),
          "{\"text\":\"zz-prompt-%d\"}".formatted(i));
    }
    // step 1 (DRAW): each holder sees exactly the prompt of the chain they hold
    for (int c = 0; c < 4; c++) {
      StompTestClient ws = r.socketOf(r.holder(c, 1));
      JsonNode task = r.task(ws, 1);
      assertThat(task.get("kind").asString()).isEqualTo("DRAW");
      assertThat(task.get("source").get("text").asString()).isEqualTo("zz-prompt-" + c);
      assertThat(task.toString()).doesNotContain(r.order.get(c));
      String phase = task.get("phaseId").asString();
      r.sendAcked(
          ws, "TELEPHONE_DRAW_STROKE", phase, stroke(UUID.randomUUID().toString(), 0, 3, true));
      r.sendAcked(ws, "TELEPHONE_SUBMIT_DRAWING", phase, "{}");
    }
    // step 2 (DESCRIBE)
    for (int c = 0; c < 4; c++) {
      StompTestClient ws = r.socketOf(r.holder(c, 2));
      JsonNode task = r.task(ws, 2);
      assertThat(task.get("kind").asString()).isEqualTo("DESCRIBE");
      assertThat(task.get("source").get("kind").asString()).isEqualTo("DRAWING");
      assertThat(task.get("source").get("strokes")).hasSize(1);
      r.sendAcked(
          ws,
          "TELEPHONE_SUBMIT_TEXT",
          task.get("phaseId").asString(),
          "{\"text\":\"zz-describe-%d\"}".formatted(c));
    }
    // step 3 (DRAW), the last
    for (int c = 0; c < 4; c++) {
      StompTestClient ws = r.socketOf(r.holder(c, 3));
      JsonNode task = r.task(ws, 3);
      assertThat(task.get("source").get("text").asString()).isEqualTo("zz-describe-" + c);
      r.sendAcked(ws, "TELEPHONE_SUBMIT_DRAWING", task.get("phaseId").asString(), "{}");
    }

    JsonNode finished = awaitLong(host, m -> m.type().equals("GAME_FINISHED")).payload();
    assertThat(finished.get("endReason").asString()).isEqualTo("COMPLETED");
    assertThat(finished.get("standings")).isEmpty();
    assertThat(host.ofType("PHASE_STARTED").stream().map(m -> m.payload().get("phase").asString()))
        .containsExactly("PROMPT", "DRAW", "DESCRIBE", "DRAW");

    // secrecy [TP-8]: no entry text reached anybody but its next holder
    for (int c = 0; c < 4; c++) {
      String prompt = "zz-prompt-" + c;
      String describe = "zz-describe-" + c;
      for (int i = 0; i < 4; i++) {
        String who = r.order.get(i);
        boolean nextOfPrompt = r.holder(c, 1).equals(who);
        boolean nextOfDescribe = r.holder(c, 3).equals(who);
        long withPrompt =
            r.socketOf(who).messages().stream().filter(m -> carries(m, prompt)).count();
        long withDescribe =
            r.socketOf(who).messages().stream().filter(m -> carries(m, describe)).count();
        // the author sees their own text only in the echo of nothing: it is never sent back
        assertThat(withPrompt).as("%s sees %s", i, prompt).isEqualTo(nextOfPrompt ? 1 : 0);
        assertThat(withDescribe).as("%s sees %s", i, describe).isEqualTo(nextOfDescribe ? 1 : 0);
      }
    }
  }

  // ------------------------------------------------------------------ errors and authorization

  @Test
  void commandsAreRefusedForTheWrongPhaseKindFinalityStalenessAndOutsiders() {
    Room r = new Room(4);
    r.start();
    StompTestClient a = r.socketOf(r.order.get(0));
    StompTestClient b = r.socketOf(r.order.get(1));
    JsonNode task = r.task(a, 0);
    String phase = task.get("phaseId").asString();

    String id = actionId();
    r.send(a, "TELEPHONE_SUBMIT_DRAWING", phase, "{}", id);
    assertThat(errorCode(a, id)).isEqualTo("INVALID_PHASE");

    id = actionId();
    r.send(a, "TELEPHONE_SUBMIT_TEXT", phase, "{\"text\":\"   \"}", id);
    assertThat(errorCode(a, id)).isEqualTo("VALIDATION_FAILED");

    r.sendAcked(a, "TELEPHONE_TEXT_DRAFT", phase, "{\"text\":\"half\"}");
    r.sendAcked(a, "TELEPHONE_SUBMIT_TEXT", phase, "{\"text\":\"first\"}");
    id = actionId();
    r.send(a, "TELEPHONE_SUBMIT_TEXT", phase, "{\"text\":\"second\"}", id);
    assertThat(errorCode(a, id)).isEqualTo("ALREADY_SUBMITTED");

    for (StompTestClient ws : r.sockets) {
      if (ws != a) {
        r.sendAcked(ws, "TELEPHONE_SUBMIT_TEXT", phase, "{\"text\":\"x\"}");
      }
    }
    id = actionId();
    r.send(b, "TELEPHONE_SUBMIT_TEXT", phase, "{\"text\":\"late\"}", id);
    assertThat(errorCode(b, id)).isEqualTo("STALE_ACTION");

    Guest outsider = newGuest();
    StompTestClient ws = connect(outsider);
    ws.subscribe("err", "/user/queue/errors");
    ws.command(
        r.code,
        "{\"type\":\"TELEPHONE_SUBMIT_TEXT\",\"actionId\":\"%s\",\"phaseId\":\"%s\",\"payload\":{\"text\":\"hi\"}}"
            .formatted(actionId(), phase));
    assertThat(ws.awaitType("ERROR").payload().get("code").asString()).isEqualTo("NOT_IN_ROOM");
  }

  // ------------------------------------------------------------------ drafts and sizes

  @Test
  void aDrawingDraftIsPrivateAndRestoredFromTheSnapshotAndTheMaximalTaskIsDelivered() {
    Room r = new Room(4);
    r.start();
    for (int i = 0; i < 4; i++) {
      StompTestClient ws = r.socketOf(r.order.get(i));
      r.sendAcked(
          ws, "TELEPHONE_SUBMIT_TEXT", r.task(ws, 0).get("phaseId").asString(), "{\"text\":\"p\"}");
    }
    StompTestClient drawer = r.socketOf(r.holder(0, 1));
    StompTestClient describer = r.socketOf(r.holder(0, 2));
    String phase = r.task(drawer, 1).get("phaseId").asString();

    // 12 000 points: six strokes of ten 200-point batches; nothing is broadcast
    for (int s = 0; s < 6; s++) {
      String strokeId = UUID.randomUUID().toString();
      for (int b = 0; b < 10; b++) {
        r.sendAcked(drawer, "TELEPHONE_DRAW_STROKE", phase, stroke(strokeId, b, 200, b == 9));
      }
    }
    for (StompTestClient ws : r.sockets) {
      assertThat(ws.ofType("DRAWING_UPDATED")).isEmpty();
    }
    JsonNode game = r.snapshot(drawer).get("game");
    assertThat(game.get("draft").get("canvasBudget").get("points").asInt()).isEqualTo(12_000);
    assertThat(game.get("submitted").asBoolean()).isFalse();
    // another member's snapshot carries no part of it
    JsonNode other = r.snapshot(describer).get("game");
    assertThat(other.get("draft").get("strokes")).isEmpty(); // their own, not the drawer's

    r.sendAcked(drawer, "TELEPHONE_SUBMIT_DRAWING", phase, "{}");
    for (StompTestClient ws : r.sockets) {
      if (ws != drawer) {
        r.sendAcked(ws, "TELEPHONE_SUBMIT_DRAWING", phase, "{}");
      }
    }
    JsonNode task = r.task(describer, 2);
    assertThat(task.get("source").get("strokes")).hasSize(6);
    byte[] snapshot =
        JsonMapper.builder()
            .build()
            .writeValueAsString(r.snapshot(describer))
            .getBytes(StandardCharsets.UTF_8);
    assertThat(snapshot.length).isLessThan(262_144);
    assertThat(snapshot.length).isGreaterThan(150_000);
  }

  @Test
  void theModuleDefaultsEqualTheCatalogRow() {
    String json =
        jdbc.queryForObject(
            "select default_settings::text from game_definition where key = 'telephone'",
            String.class);
    Map<String, Object> row =
        JsonMapper.builder()
            .build()
            .readValue(json, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    assertThat(row).isEqualTo(TelephoneSettings.defaults());
    Integer enabled =
        jdbc.queryForObject(
            "select count(*) from game_definition where key = 'telephone' and enabled"
                + " and min_players = 4 and max_players = 8",
            Integer.class);
    assertThat(enabled).isEqualTo(1);
  }
}
