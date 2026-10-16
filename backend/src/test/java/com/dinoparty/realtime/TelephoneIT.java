package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.games.telephone.TelephoneSettings;
import com.dinoparty.room.CreateRoomCommand;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dino Telephone end to end over real STOMP with the registered module (P6-02..P6-04, the reveal
 * and albums of P6-03): start validation and initial tasks, a full four-player game with the
 * secrecy of [TP-8] and the host-driven reveal, role/phase/finality errors, timeout progression,
 * concurrent final submissions, reconnect restore in a step and in the reveal, results, albums and
 * rematch, draft restore, the maximal drawing task, and the settings defaults against the catalog
 * row.
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
      this(players, Map.of());
    }

    Room(int players, Map<String, Object> settings) {
      Guest host = newGuest();
      code =
          roomService.create(host.id(), new CreateRoomCommand("telephone", settings, null)).code();
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

    /** Replaces a guest's socket with a fresh connection (a browser refresh). */
    StompTestClient reconnect(String guestId) {
      int index = sockets.indexOf(socketOf(guestId));
      sockets.get(index).drop();
      StompTestClient again = connectAndSubscribe(guests.get(index), code);
      sockets.set(index, again);
      return again;
    }

    /** Every holder submits their entry of {@code step}; texts are tagged with step and chain. */
    void playStep(int step, String tag) {
      for (int c = 0; c < order.size(); c++) {
        StompTestClient ws = socketOf(holder(c, step));
        JsonNode task = task(ws, step);
        String phase = task.get("phaseId").asString();
        if (task.get("kind").asString().equals("DRAW")) {
          sendAcked(ws, "TELEPHONE_SUBMIT_DRAWING", phase, "{}");
        } else {
          sendAcked(
              ws,
              "TELEPHONE_SUBMIT_TEXT",
              phase,
              "{\"text\":\"%s-%d-%d\"}".formatted(tag, step, c));
        }
      }
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

    /** The REVEAL phase id, waiting for the reveal to start. */
    String revealPhase(StompTestClient ws) {
      return awaitLong(
              ws,
              m ->
                  m.type().equals("PHASE_STARTED")
                      && m.payload().get("phase").asString().equals("REVEAL"))
          .payload()
          .get("phaseId")
          .asString();
    }

    /**
     * The cursor of the {@code n}-th ALBUM_REVEALED a socket received (1-based), waiting for it.
     */
    JsonNode revealed(StompTestClient ws, int n) {
      await().atMost(LONG).until(() -> ws.ofType("ALBUM_REVEALED").size() >= n);
      return ws.ofType("ALBUM_REVEALED").get(n - 1).payload();
    }

    static String cursor(JsonNode revealed) {
      return "{\"chainIndex\":%d,\"entryIndex\":%d}"
          .formatted(revealed.get("chainIndex").asInt(), revealed.get("entryIndex").asInt());
    }

    /** The host steps through every entry; the last NEXT finishes the game. */
    void revealAll(StompTestClient host) {
      String phase = revealPhase(host);
      int total = order.size() * order.size(); // E = n for the even rooms used here
      for (int i = host.ofType("ALBUM_REVEALED").size(); i <= total; i++) {
        sendAcked(host, "REVEAL_NEXT", phase, cursor(revealed(host, i)));
      }
      awaitLong(host, m -> m.type().equals("GAME_FINISHED"));
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

    r.revealPhase(host);
    for (StompTestClient ws : r.sockets) {
      r.snapshot(ws); // orders every earlier frame of this socket
    }

    // secrecy [TP-8]: until ALBUM_REVEALED no entry text reached anybody but its next holder
    for (int c = 0; c < 4; c++) {
      String prompt = "zz-prompt-" + c;
      String describe = "zz-describe-" + c;
      for (int i = 0; i < 4; i++) {
        String who = r.order.get(i);
        boolean nextOfPrompt = r.holder(c, 1).equals(who);
        boolean nextOfDescribe = r.holder(c, 3).equals(who);
        java.util.function.Predicate<StompTestClient.Message> secret =
            m -> !m.type().equals("ALBUM_REVEALED") && !m.type().equals("ROOM_SNAPSHOT");
        long withPrompt =
            r.socketOf(who).messages().stream()
                .filter(secret)
                .filter(m -> carries(m, prompt))
                .count();
        long withDescribe =
            r.socketOf(who).messages().stream()
                .filter(secret)
                .filter(m -> carries(m, describe))
                .count();
        // the author sees their own text only in the echo of nothing: it is never sent back
        assertThat(withPrompt).as("%s sees %s", i, prompt).isEqualTo(nextOfPrompt ? 1 : 0);
        assertThat(withDescribe).as("%s sees %s", i, describe).isEqualTo(nextOfDescribe ? 1 : 0);
      }
    }
    // the reveal starts with album 0's prompt only; no other album has been shown
    for (StompTestClient ws : r.sockets) {
      assertThat(ws.ofType("ALBUM_REVEALED")).hasSize(1);
      assertThat(carries(ws.ofType("ALBUM_REVEALED").getFirst(), "zz-prompt-0")).isTrue();
      for (int c = 1; c < 4; c++) {
        String prompt = "zz-prompt-" + c;
        assertThat(ws.messages().stream().filter(m -> m.type().equals("ALBUM_REVEALED")))
            .noneMatch(m -> carries(m, prompt));
      }
    }

    r.revealAll(host);
    JsonNode finished = awaitLong(host, m -> m.type().equals("GAME_FINISHED")).payload();
    assertThat(finished.get("endReason").asString()).isEqualTo("COMPLETED");
    assertThat(finished.get("standings")).isEmpty();
    assertThat(host.ofType("PHASE_STARTED").stream().map(m -> m.payload().get("phase").asString()))
        .containsExactly("PROMPT", "DRAW", "DESCRIBE", "DRAW", "REVEAL");
    // every player saw every entry exactly once, in reveal order
    for (StompTestClient ws : r.sockets) {
      r.snapshot(ws);
      List<JsonNode> shown = ws.ofType("ALBUM_REVEALED").stream().map(m -> m.payload()).toList();
      assertThat(shown).hasSize(16);
      for (int i = 0; i < 16; i++) {
        assertThat(shown.get(i).get("chainIndex").asInt()).isEqualTo(i / 4);
        assertThat(shown.get(i).get("entryIndex").asInt()).isEqualTo(i % 4);
        assertThat(shown.get(i).get("newlyRevealed").asBoolean()).isTrue();
      }
      assertThat(shown.get(4 + 2).get("entry").get("text").asString()).isEqualTo("zz-describe-1");
    }
  }

  @Test
  void TP6_onlyTheHostRevealsADoubleClickIsStaleAndAReconnectRestoresThePosition()
      throws Exception {
    Room r = new Room(4);
    r.start();
    for (int step = 0; step < 4; step++) {
      r.playStep(step, "zz-r");
    }
    StompTestClient host = r.sockets.get(0);
    StompTestClient guest = r.sockets.get(1);
    String phase = r.revealPhase(host);
    String session =
        awaitLong(host, m -> m.type().equals("GAME_STARTED")).payload().get("sessionId").asString();

    String id = actionId();
    r.send(guest, "REVEAL_NEXT", phase, Room.cursor(r.revealed(guest, 1)), id);
    assertThat(errorCode(guest, id)).isEqualTo("NOT_HOST");

    String first = Room.cursor(r.revealed(host, 1));
    r.sendAcked(host, "REVEAL_NEXT", phase, first);
    id = actionId();
    r.send(host, "REVEAL_NEXT", phase, first, id); // the double click
    assertThat(errorCode(host, id)).isEqualTo("STALE_ACTION");
    r.sendAcked(host, "REVEAL_NEXT", phase, Room.cursor(r.revealed(host, 2)));
    r.sendAcked(host, "REVEAL_PREV", phase, Room.cursor(r.revealed(host, 3)));
    assertThat(r.revealed(host, 4).get("entryIndex").asInt()).isEqualTo(1);
    assertThat(r.revealed(host, 4).get("newlyRevealed").asBoolean()).isFalse();

    // the albums cannot be fetched around the reveal while it runs
    mvc.perform(
            get("/api/v1/sessions/" + session + "/albums/1")
                .header("Authorization", bearer(r.guests.get(1))))
        .andExpect(status().isConflict());

    StompTestClient again = r.reconnect(r.guests.get(1).id().toString());
    JsonNode game = r.snapshot(again).get("game");
    assertThat(game.get("phase").asString()).isEqualTo("REVEAL");
    assertThat(game.get("phaseId").asString()).isEqualTo(phase);
    assertThat(game.get("deadlineAt").isNull()).isTrue();
    JsonNode reveal = game.get("reveal");
    assertThat(reveal.get("cursor").get("entryIndex").asInt()).isEqualTo(1);
    assertThat(reveal.get("highWater").get("entryIndex").asInt()).isEqualTo(2);
    assertThat(reveal.get("albums").get(0).get("entries")).hasSize(3);
    assertThat(reveal.get("albums").get(1).get("entries")).isEmpty();
    // nothing of an unrevealed album is in the restored view
    assertThat(reveal.toString()).doesNotContain("zz-r-0-1").doesNotContain("zz-r-2-1");
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

  // ------------------------------------------------------------------ start

  @Test
  void C1_aStartWithFewerThanFourPlayersIsRefusedAndNoSessionStarts() {
    Room r = new Room(3);
    String id = actionId();
    r.sockets.get(0).command(r.code, "START_GAME", id, "{}");
    assertThat(errorCode(r.sockets.get(0), id)).isEqualTo("NOT_ENOUGH_PLAYERS");
    for (StompTestClient ws : r.sockets) {
      assertThat(ws.ofType("GAME_STARTED")).isEmpty();
      assertThat(ws.ofType("TELEPHONE_TASK")).isEmpty();
    }
  }

  @Test
  void T1_everyHolderGetsOnePrivatePromptTaskAndASnapshotWithTheServerDeadline() {
    Room r = new Room(4);
    r.start();
    JsonNode phase = awaitLong(r.sockets.get(0), m -> m.type().equals("PHASE_STARTED")).payload();
    assertThat(phase.get("phase").asString()).isEqualTo("PROMPT");
    assertThat(phase.get("totalSteps").asInt()).isEqualTo(4);
    String deadline = phase.get("deadlineAt").asString();
    assertThat(deadline).isNotBlank();
    String phaseId = phase.get("phaseId").asString();

    for (StompTestClient ws : r.sockets) {
      JsonNode task = r.task(ws, 0);
      assertThat(task.get("phaseId").asString()).isEqualTo(phaseId);
      assertThat(task.get("kind").asString()).isEqualTo("PROMPT");
      assertThat(ws.ofType("TELEPHONE_TASK")).hasSize(1);
      // the task is private: it never travels on the room topic
      assertThat(ws.onStream("ROOM", r.code)).noneMatch(m -> m.type().equals("TELEPHONE_TASK"));

      JsonNode game = r.snapshot(ws).get("game");
      assertThat(game.get("phaseId").asString()).isEqualTo(phaseId);
      assertThat(game.get("deadlineAt").asString()).isEqualTo(deadline);
      assertThat(game.get("task").get("kind").asString()).isEqualTo("PROMPT");
      assertThat(game.get("submitted").asBoolean()).isFalse();
    }
  }

  // ------------------------------------------------------------------ synchronized progression

  @Test
  void TP5_theDeadlineAdvancesTheStepWithTheDraftOrAnAutoPromptForThoseWhoDidNotSubmit() {
    Room r = new Room(4, Map.of("promptSeconds", 20));
    r.start();
    String drafter = r.order.get(0); // chain 0: drafts but never submits
    String phase = r.task(r.socketOf(drafter), 0).get("phaseId").asString();
    r.sendAcked(
        r.socketOf(drafter), "TELEPHONE_TEXT_DRAFT", phase, "{\"text\":\"zz-unsent-draft\"}");
    // chain 1's owner does nothing at all; chains 2 and 3 are submitted
    for (int c = 2; c < 4; c++) {
      r.sendAcked(
          r.socketOf(r.order.get(c)),
          "TELEPHONE_SUBMIT_TEXT",
          phase,
          "{\"text\":\"zz-sent-%d\"}".formatted(c));
    }

    // Only the server deadline ends the step (no client clock): one ending, one next phase.
    StompTestClient observer = r.socketOf(r.order.get(2));
    JsonNode ended =
        await()
            .atMost(Duration.ofSeconds(35))
            .until(
                () ->
                    observer.messages().stream()
                        .filter(m -> m.type().equals("TELEPHONE_STEP_ENDED"))
                        .findFirst()
                        .orElse(null),
                m -> m != null)
            .payload();
    assertThat(ended.get("reason").asString()).isEqualTo("TIME");
    assertThat(r.task(r.socketOf(r.holder(0, 1)), 1).get("source").get("text").asString())
        .isEqualTo("zz-unsent-draft");
    JsonNode autoPrompt = r.task(r.socketOf(r.holder(1, 1)), 1).get("source");
    assertThat(autoPrompt.get("text").asString()).isNotBlank().doesNotStartWith("zz-");
    assertThat(autoPrompt.get("placeholder").asBoolean()).isFalse();
    r.snapshot(observer);
    for (StompTestClient ws : r.sockets) {
      assertThat(ws.ofType("TELEPHONE_STEP_ENDED")).hasSize(1);
      assertThat(ws.ofType("PHASE_STARTED").stream().map(m -> m.payload().get("phase").asString()))
          .containsExactly("PROMPT", "DRAW");
    }
  }

  @Test
  void TP4_concurrentFinalSubmissionsEndTheStepExactlyOnce() {
    Room r = new Room(4);
    r.start();
    String phase = r.task(r.sockets.get(0), 0).get("phaseId").asString();
    for (int i = 0; i < 2; i++) {
      r.sendAcked(r.sockets.get(i), "TELEPHONE_SUBMIT_TEXT", phase, "{\"text\":\"early\"}");
    }
    List<String> ids = List.of(actionId(), actionId());
    CompletableFuture.allOf(
            IntStream.range(0, 2)
                .mapToObj(
                    i ->
                        CompletableFuture.runAsync(
                            () ->
                                r.send(
                                    r.sockets.get(2 + i),
                                    "TELEPHONE_SUBMIT_TEXT",
                                    phase,
                                    "{\"text\":\"last\"}",
                                    ids.get(i))))
                .toArray(CompletableFuture[]::new))
        .join();
    awaitAck(r.sockets.get(2), ids.get(0));
    awaitAck(r.sockets.get(3), ids.get(1));

    for (StompTestClient ws : r.sockets) {
      r.task(ws, 1);
      JsonNode ended = awaitLong(ws, m -> m.type().equals("TELEPHONE_STEP_ENDED")).payload();
      assertThat(ended.get("reason").asString()).isEqualTo("ALL_SUBMITTED");
      // a snapshot round trip orders every earlier frame of this socket before counting
      r.snapshot(ws);
      assertThat(ws.ofType("TELEPHONE_STEP_ENDED")).hasSize(1);
      assertThat(ws.ofType("PHASE_STARTED")).hasSize(2);
      assertThat(ws.ofType("TELEPHONE_TASK")).hasSize(2);
    }
  }

  // ------------------------------------------------------------------ reconnect

  @Test
  void TP9_aRefreshMidDescribeRestoresTaskDraftAndDeadlineThenTheSubmissionIsFinal() {
    Room r = new Room(4);
    r.start();
    r.playStep(0, "zz-prompt");
    r.playStep(1, "zz-draw");
    String describer = r.holder(0, 2);
    String phase = r.task(r.socketOf(describer), 2).get("phaseId").asString();
    r.sendAcked(
        r.socketOf(describer), "TELEPHONE_TEXT_DRAFT", phase, "{\"text\":\"zz-half-typed\"}");

    StompTestClient again = r.reconnect(describer);
    JsonNode game = r.snapshot(again).get("game");
    assertThat(game.get("phase").asString()).isEqualTo("DESCRIBE");
    assertThat(game.get("phaseId").asString()).isEqualTo(phase);
    assertThat(game.get("deadlineAt").asString()).isNotBlank();
    assertThat(game.get("task").get("source").get("kind").asString()).isEqualTo("DRAWING");
    assertThat(game.get("draft").get("text").asString()).isEqualTo("zz-half-typed");
    assertThat(game.get("submitted").asBoolean()).isFalse();
    // [TP-8]: the chain's original prompt is not part of the describer's view
    assertThat(game.toString()).doesNotContain("zz-prompt");

    r.sendAcked(again, "TELEPHONE_SUBMIT_TEXT", phase, "{\"text\":\"zz-final\"}");
    StompTestClient third = r.reconnect(describer);
    JsonNode after = r.snapshot(third).get("game");
    assertThat(after.get("submitted").asBoolean()).isTrue();
    assertThat(after.toString()).doesNotContain("zz-half-typed");
    String id = actionId();
    r.send(third, "TELEPHONE_SUBMIT_TEXT", phase, "{\"text\":\"again\"}", id);
    assertThat(errorCode(third, id)).isEqualTo("ALREADY_SUBMITTED");
  }

  // ------------------------------------------------------------------ results and rematch

  @Test
  void TP13_aFinishedGameReachesResultsAndARematchStartsAFreshSessionWithNewTasks()
      throws Exception {
    Room r = new Room(4);
    r.start();
    StompTestClient host = r.sockets.get(0);
    String firstSession =
        awaitLong(host, m -> m.type().equals("GAME_STARTED")).payload().get("sessionId").asString();
    String oldPhase = r.task(host, 0).get("phaseId").asString();
    for (int step = 0; step < 4; step++) {
      r.playStep(step, "zz-first");
    }
    r.revealAll(host);
    awaitLong(
        host,
        m ->
            m.type().equals("ROOM_STATUS_CHANGED")
                && m.payload().get("status").asString().equals("RESULTS"));
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                "FINISHED"
                    .equals(
                        jdbc.queryForObject(
                            "select status from game_session where id = ?::uuid",
                            String.class,
                            firstSession)));
    JsonNode results =
        JsonMapper.builder()
            .build()
            .readTree(
                mvc.perform(
                        get("/api/v1/sessions/" + firstSession + "/results")
                            .header("Authorization", bearer(r.guests.get(0))))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
    assertThat(results.get("standings")).isEmpty();
    assertThat(results.get("rounds")).hasSize(4);
    // neither results nor a finished snapshot carry entry content ([TP-8]): only the albums do
    assertThat(results.toString()).doesNotContain("zz-first");
    assertThat(r.snapshot(r.sockets.get(1)).toString()).doesNotContain("zz-first");
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> album(r, firstSession, 1).get("entries").size() == 4);
    JsonNode album = album(r, firstSession, 1);
    assertThat(album.get("entries").get(0).get("text").asString()).isEqualTo("zz-first-0-1");
    assertThat(album.get("entries").get(0).get("authorGuestId").asString())
        .isEqualTo(r.order.get(1));

    for (StompTestClient ws : r.sockets) {
      ws.command(r.code, "REMATCH_VOTE", actionId(), "{\"vote\":true}");
    }
    awaitLong(
        host,
        m ->
            m.type().equals("ROOM_STATUS_CHANGED")
                && m.payload().get("reason").asString().equals("REMATCH"));
    host.command(r.code, "START_GAME", actionId(), "{}");
    String secondSession =
        awaitLong(
                host,
                m ->
                    m.type().equals("GAME_STARTED")
                        && !m.payload().get("sessionId").asString().equals(firstSession))
            .payload()
            .get("sessionId")
            .asString();

    for (StompTestClient ws : r.sockets) {
      JsonNode fresh =
          awaitLong(
                  ws,
                  m ->
                      m.type().equals("TELEPHONE_TASK")
                          && m.payload().get("step").asInt() == 0
                          && !m.payload().get("phaseId").asString().equals(oldPhase))
              .payload();
      assertThat(fresh.get("kind").asString()).isEqualTo("PROMPT");
      JsonNode game = r.snapshot(ws).get("game");
      assertThat(game.get("sessionId").asString()).isEqualTo(secondSession);
      assertThat(game.get("submitted").asBoolean()).isFalse();
      assertThat(game.toString()).doesNotContain("zz-first");
    }
    String id = actionId();
    r.send(host, "TELEPHONE_SUBMIT_TEXT", oldPhase, "{\"text\":\"old\"}", id);
    assertThat(errorCode(host, id)).isEqualTo("STALE_ACTION");
    assertThat(
            jdbc.queryForObject(
                "select status from game_session where id = ?::uuid", String.class, firstSession))
        .isEqualTo("FINISHED");
    // the finished game's albums stay readable after the rematch reset ([TP-13], D45)
    assertThat(album(r, firstSession, 0).get("complete").asBoolean()).isTrue();
  }

  private JsonNode album(Room r, String sessionId, int chain) throws Exception {
    return JsonMapper.builder()
        .build()
        .readTree(
            mvc.perform(
                    get("/api/v1/sessions/" + sessionId + "/albums/" + chain)
                        .header("Authorization", bearer(r.guests.get(2))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
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
