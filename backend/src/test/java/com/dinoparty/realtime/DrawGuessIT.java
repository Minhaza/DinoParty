package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.games.drawguess.DrawGuessSettings;
import com.dinoparty.room.CreateRoomCommand;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Draw & Guess end to end over real STOMP with the registered module (task 5.7/5.9): a full game
 * with role errors, secrecy, reconnect, concurrent guesses, a drawer leaving, results and rematch;
 * the maximal canvas snapshot; and the settings defaults against the catalog row.
 */
@RealtimeIntegrationTest
class DrawGuessIT extends GameEngineSupport {

  private static final Duration LONG = Duration.ofSeconds(20);

  /** A draw-guess room with fast settings; index 0 is the host. */
  private final class Room {
    final List<Guest> guests = new ArrayList<>();
    final List<StompTestClient> sockets = new ArrayList<>();
    final String code;

    Room(int players) {
      Guest host = newGuest();
      code =
          roomService
              .create(
                  host.id(),
                  new CreateRoomCommand(
                      "draw-guess",
                      Map.of("cycles", 1, "drawSeconds", 30, "wordChoiceSeconds", 5),
                      null))
              .code();
      guests.add(host);
      for (int i = 1; i < players; i++) {
        Guest g = newGuest();
        roomService.join(g.id(), code);
        guests.add(g);
      }
      guests.forEach(g -> sockets.add(connectAndSubscribe(g, code)));
    }

    StompTestClient socketOf(String guestId) {
      for (int i = 0; i < guests.size(); i++) {
        if (guests.get(i).id().toString().equals(guestId)) {
          return sockets.get(i);
        }
      }
      throw new AssertionError("unknown guest");
    }

    Guest guestOf(String guestId) {
      return guests.stream()
          .filter(g -> g.id().toString().equals(guestId))
          .findFirst()
          .orElseThrow();
    }

    void send(StompTestClient ws, String type, String phaseId, String payload, String actionId) {
      String phase = phaseId == null ? "null" : "\"" + phaseId + "\"";
      ws.command(
          code,
          "{\"type\":\"%s\",\"actionId\":\"%s\",\"phaseId\":%s,\"payload\":%s}"
              .formatted(type, actionId, phase, payload));
    }

    JsonNode snapshot(StompTestClient ws) {
      int before = ws.ofType("ROOM_SNAPSHOT").size();
      ws.command(code, "REQUEST_SNAPSHOT", actionId(), "{}");
      await().atMost(Duration.ofSeconds(5)).until(() -> ws.ofType("ROOM_SNAPSHOT").size() > before);
      return ws.ofType("ROOM_SNAPSHOT").getLast().payload();
    }
  }

  private static StompTestClient.Message awaitLong(
      StompTestClient ws, Predicate<StompTestClient.Message> p) {
    return await()
        .atMost(LONG)
        .until(() -> ws.messages().stream().filter(p).findFirst().orElse(null), m -> m != null);
  }

  /** The n-th (1-based) PHASE_STARTED of the given phase name. */
  private static JsonNode phase(StompTestClient ws, String name, int nth) {
    await()
        .atMost(LONG)
        .until(
            () ->
                ws.ofType("PHASE_STARTED").stream()
                        .filter(m -> m.payload().get("phase").asString().equals(name))
                        .count()
                    >= nth);
    return ws.ofType("PHASE_STARTED").stream()
        .filter(m -> m.payload().get("phase").asString().equals(name))
        .toList()
        .get(nth - 1)
        .payload();
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
        java.util.stream.IntStream.range(0, points)
            .mapToObj(i -> "[10000,10000]")
            .collect(Collectors.joining(","));
    return ("{\"strokeId\":\"%s\",\"batchNo\":%d,\"tool\":\"PENCIL\",\"color\":\"#336699\","
            + "\"size\":2,\"points\":[%s],\"final\":%s}")
        .formatted(strokeId, batchNo, pts, last);
  }

  /** True if the envelope carries {@code word} as a JSON string value. */
  private static boolean carries(StompTestClient.Message m, String word) {
    return m.envelope().toString().contains("\"" + word + "\"");
  }

  // ------------------------------------------------------------------ full game

  @Test
  void aFullGameOverStompWithRolesSecrecyReconnectLeavingResultsAndRematch() throws Exception {
    Room r = new Room(3);
    StompTestClient host = r.sockets.get(0);
    host.command(r.code, "START_GAME", actionId(), "{}");

    JsonNode started = awaitLong(host, m -> m.type().equals("GAME_STARTED")).payload();
    String sessionId = started.get("sessionId").asString();
    List<String> order = new ArrayList<>();
    started.get("order").forEach(n -> order.add(n.asString()));
    JsonNode wordChoice = phase(host, "WORD_CHOICE", 1);
    assertThat(wordChoice.get("drawerGuestId").asString()).isEqualTo(order.get(0));
    assertThat(wordChoice.get("totalRounds").asInt()).isEqualTo(3);

    StompTestClient drawer = r.socketOf(order.get(0));
    StompTestClient g1 = r.socketOf(order.get(1));
    StompTestClient g2 = r.socketOf(order.get(2));
    JsonNode choices = awaitLong(drawer, m -> m.type().equals("WORD_CHOICES")).payload();
    assertThat(choices.get("phaseId").asString()).isEqualTo(wordChoice.get("phaseId").asString());
    List<String> offered = new ArrayList<>();
    choices.get("choices").forEach(n -> offered.add(n.asString()));
    assertThat(g1.ofType("WORD_CHOICES")).isEmpty();
    assertThat(g2.ofType("WORD_CHOICES")).isEmpty();

    // Wrong role for the word choice, then the drawer picks.
    String choicePhase = wordChoice.get("phaseId").asString();
    String id = actionId();
    r.send(g1, "CHOOSE_WORD", choicePhase, "{\"wordIndex\":0}", id);
    assertThat(errorCode(g1, id)).isEqualTo("NOT_DRAWER");
    r.send(drawer, "CHOOSE_WORD", choicePhase, "{\"wordIndex\":1}", actionId());
    JsonNode drawing = phase(host, "DRAWING", 1);
    String word = offered.get(1);
    String phaseId = drawing.get("phaseId").asString();
    assertThat(drawing.get("hint").asString()).isEqualTo(word.replaceAll("[a-z]", "_"));
    JsonNode secret = awaitLong(drawer, m -> m.type().equals("DRAWER_WORD")).payload();
    assertThat(secret.get("word").asString()).isEqualTo(word);
    assertThat(secret.get("phaseId").asString()).isEqualTo(phaseId);

    // Stale phase, duplicate action id, wrong role per command.
    id = actionId();
    r.send(drawer, "CHOOSE_WORD", choicePhase, "{\"wordIndex\":0}", id);
    assertThat(errorCode(drawer, id)).isEqualTo("STALE_ACTION");
    String strokeId = UUID.randomUUID().toString();
    String strokeAction = actionId();
    r.send(drawer, "DRAW_STROKE", phaseId, stroke(strokeId, 0, 3, false), strokeAction);
    awaitAck(drawer, strokeAction);
    r.send(drawer, "DRAW_STROKE", phaseId, stroke(strokeId, 1, 1, false), strokeAction);
    assertThat(errorCode(drawer, strokeAction)).isEqualTo("DUPLICATE_ACTION");
    for (StompTestClient ws : r.sockets) { // the public stroke reaches everyone, drawer included
      JsonNode update = awaitLong(ws, m -> m.type().equals("DRAWING_UPDATED")).payload();
      assertThat(update.get("strokeId").asString()).isEqualTo(strokeId);
      assertThat(update.get("points")).hasSize(3);
    }
    id = actionId();
    r.send(g1, "DRAW_STROKE", phaseId, stroke(UUID.randomUUID().toString(), 0, 1, true), id);
    assertThat(errorCode(g1, id)).isEqualTo("NOT_DRAWER");
    id = actionId();
    r.send(g1, "DRAW_UNDO", phaseId, "{}", id);
    assertThat(errorCode(g1, id)).isEqualTo("NOT_DRAWER");
    id = actionId();
    r.send(drawer, "SUBMIT_GUESS", phaseId, "{\"text\":\"" + word + "\"}", id);
    assertThat(errorCode(drawer, id)).isEqualTo("IS_DRAWER");
    id = actionId();
    r.send(g1, "SUBMIT_GUESS", phaseId, "{\"text\":\"   \"}", id);
    assertThat(errorCode(g1, id)).isEqualTo("VALIDATION_FAILED");

    // A wrong guess is public chat.
    r.send(g1, "SUBMIT_GUESS", phaseId, "{\"text\":\"zzz wrong\"}", actionId());
    awaitLong(g2, m -> m.type().equals("GUESS_CHAT"));

    // Refresh mid-round: the snapshot restores the canvas, hint and chat but never the word.
    int g2Index = r.sockets.indexOf(g2);
    g2.drop();
    StompTestClient again = connectAndSubscribe(r.guestOf(order.get(2)), r.code);
    r.sockets.set(g2Index, again);
    if (g2 == host) { // the order is random: the host may be the guesser who refreshed
      host = again;
    }
    g2 = again;
    JsonNode game = r.snapshot(g2).get("game");
    assertThat(game.get("phase").asString()).isEqualTo("DRAWING");
    assertThat(game.get("word").isNull()).isTrue();
    assertThat(game.get("wordChoices").isNull()).isTrue();
    assertThat(game.get("strokes")).hasSize(1);
    assertThat(game.get("strokes").get(0).get("closed").asBoolean()).isFalse();
    assertThat(game.get("recentChat").get(0).get("text").asString()).isEqualTo("zzz wrong");
    assertThat(game.get("canvasBudget").get("points").asInt()).isEqualTo(3);
    assertThat(r.snapshot(drawer).get("game").get("word").asString()).isEqualTo(word);

    // Both guessers answer at the same moment: distinct orders, one round end.
    CountDownLatch go = new CountDownLatch(1);
    StompTestClient first = g1;
    StompTestClient second = g2;
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      for (StompTestClient ws : List.of(first, second)) {
        pool.submit(
            () -> {
              go.await();
              r.send(ws, "SUBMIT_GUESS", phaseId, "{\"text\":\"" + word + "\"}", actionId());
              return null;
            });
      }
      go.countDown();
    }
    JsonNode ended = awaitLong(host, m -> m.type().equals("ROUND_ENDED")).payload();
    assertThat(ended.get("reason").asString()).isEqualTo("ALL_GUESSED");
    assertThat(ended.get("word").asString()).isEqualTo(word);
    List<Integer> orders =
        host.ofType("GUESS_ACCEPTED").stream().map(m -> m.payload().get("order").asInt()).toList();
    assertThat(orders).containsExactly(0, 1);

    // Secrecy: guessers never saw the word before ROUND_ENDED, nor any unchosen word ever.
    for (StompTestClient ws : List.of(g1, g2)) {
      List<StompTestClient.Message> seen = ws.messages();
      int endedAt = -1;
      for (int i = 0; i < seen.size(); i++) {
        if (seen.get(i).type().equals("ROUND_ENDED")) {
          endedAt = i;
          break;
        }
      }
      assertThat(endedAt).isPositive();
      for (int i = 0; i < endedAt; i++) {
        assertThat(carries(seen.get(i), word)).as(seen.get(i).type()).isFalse();
      }
      for (StompTestClient.Message m : seen) {
        for (String other : offered) {
          if (!other.equals(word)) {
            assertThat(carries(m, other)).isFalse();
          }
        }
      }
    }
    for (StompTestClient.Message m : drawer.ofType("GAME_ERROR")) {
      assertThat(m.envelope().toString()).doesNotContain(word);
    }

    // Round 2: its drawer leaves for good; the round ends DRAWER_LEFT and the game goes on.
    JsonNode round2 = phase(host, "WORD_CHOICE", 2);
    assertThat(round2.get("drawerGuestId").asString()).isEqualTo(order.get(1));
    roomService.leave(UUID.fromString(order.get(1)), r.code);
    StompTestClient observer = r.socketOf(order.get(0));
    JsonNode left =
        awaitLong(
                observer,
                m ->
                    m.type().equals("ROUND_ENDED")
                        && m.payload().get("reason").asString().equals("DRAWER_LEFT"))
            .payload();
    assertThat(left.get("word").isNull()).isTrue();

    // Round 3: the last drawer; the remaining guesser solves it and the game finishes.
    JsonNode round3 = phase(observer, "WORD_CHOICE", 3);
    assertThat(round3.get("drawerGuestId").asString()).isEqualTo(order.get(2));
    StompTestClient lastDrawer = r.socketOf(order.get(2));
    String lastWord =
        awaitLong(
                lastDrawer,
                m ->
                    m.type().equals("WORD_CHOICES")
                        && m.payload()
                            .get("phaseId")
                            .asString()
                            .equals(round3.get("phaseId").asString()))
            .payload()
            .get("choices")
            .get(0)
            .asString();
    r.send(
        lastDrawer,
        "CHOOSE_WORD",
        round3.get("phaseId").asString(),
        "{\"wordIndex\":0}",
        actionId());
    String phase3 = phase(observer, "DRAWING", 2).get("phaseId").asString();
    r.send(observer, "SUBMIT_GUESS", phase3, "{\"text\":\"" + lastWord + "\"}", actionId());
    JsonNode finished = awaitLong(observer, m -> m.type().equals("GAME_FINISHED")).payload();
    assertThat(finished.get("endReason").asString()).isEqualTo("COMPLETED");
    assertThat(finished.get("standings")).hasSize(3);
    JsonNode detail = finished.get("standings").get(0).get("detail");
    assertThat(detail.has("correctGuesses")).isTrue();
    assertThat(detail.has("guesserPoints")).isTrue();
    assertThat(detail.has("drawerPoints")).isTrue();
    assertThat(detail.has("roundsDrawn")).isTrue();

    // Results are persisted with the round meta.
    Guest member = r.guestOf(order.get(0));
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                "FINISHED"
                    .equals(
                        jdbc.queryForObject(
                            "select status from game_session where id = ?::uuid",
                            String.class,
                            sessionId)));
    mvc.perform(
            get("/api/v1/sessions/" + sessionId + "/results")
                .header("Authorization", bearer(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rounds.length()").value(3))
        .andExpect(jsonPath("$.rounds[0].endReason").value("ALL_GUESSED"))
        .andExpect(jsonPath("$.rounds[0].meta.word").value(word))
        .andExpect(jsonPath("$.rounds[0].meta.drawerGuestId").value(order.get(0)))
        .andExpect(jsonPath("$.rounds[0].meta.autoPicked").value(false))
        .andExpect(jsonPath("$.rounds[0].meta.correctGuessers.length()").value(2))
        .andExpect(jsonPath("$.rounds[1].endReason").value("DRAWER_LEFT"))
        .andExpect(jsonPath("$.rounds[1].meta.word").doesNotExist())
        .andExpect(jsonPath("$.standings[0].detail.roundsDrawn").exists());

    // Rematch: everyone still connected votes yes, then a second session starts fresh.
    for (String g : List.of(order.get(0), order.get(2))) {
      r.socketOf(g).command(r.code, "REMATCH_VOTE", actionId(), "{\"vote\":true}");
    }
    awaitLong(
        observer,
        m ->
            m.type().equals("ROOM_STATUS_CHANGED")
                && m.payload().get("reason").asString().equals("REMATCH"));
    String newHost = roomService.getForMember(member.id(), r.code).hostGuestId().toString();
    r.socketOf(newHost).command(r.code, "START_GAME", actionId(), "{}");
    JsonNode restarted =
        awaitLong(
                observer,
                m ->
                    m.type().equals("GAME_STARTED")
                        && !m.payload().get("sessionId").asString().equals(sessionId))
            .payload();
    assertThat(restarted.get("order")).hasSize(2);
    JsonNode fresh = r.snapshot(observer).get("game");
    assertThat(fresh.get("scores").get(order.get(0)).asInt()).isZero();
    assertThat(fresh.get("round").asInt()).isEqualTo(1);
  }

  // ------------------------------------------------------------------ scores and disconnects

  @Test
  void scoresComeOnlyFromTheServerAndADrawerReconnectKeepsTheRound() {
    Room r = new Room(3);
    r.sockets.get(0).command(r.code, "START_GAME", actionId(), "{}");
    JsonNode started = awaitLong(r.sockets.get(0), m -> m.type().equals("GAME_STARTED")).payload();
    List<String> order = new ArrayList<>();
    started.get("order").forEach(n -> order.add(n.asString()));
    StompTestClient drawer = r.socketOf(order.get(0));
    StompTestClient g1 = r.socketOf(order.get(1));
    StompTestClient g2 = r.socketOf(order.get(2));
    JsonNode choice = phase(g2, "WORD_CHOICE", 1);
    String word =
        awaitLong(drawer, m -> m.type().equals("WORD_CHOICES"))
            .payload()
            .get("choices")
            .get(0)
            .asString();
    r.send(
        drawer, "CHOOSE_WORD", choice.get("phaseId").asString(), "{\"wordIndex\":0}", actionId());
    String phaseId = phase(g2, "DRAWING", 1).get("phaseId").asString();

    // The drawer's socket dies mid-round and comes back within grace: same phase, word and role.
    int drawerIndex = r.sockets.indexOf(drawer);
    drawer.drop();
    awaitLong(
        g2,
        m ->
            m.type().equals("PLAYER_DISCONNECTED")
                && m.payload().get("guestId").asString().equals(order.get(0)));
    drawer = connectAndSubscribe(r.guestOf(order.get(0)), r.code);
    r.sockets.set(drawerIndex, drawer);
    awaitLong(
        g2,
        m ->
            m.type().equals("PLAYER_RECONNECTED")
                && m.payload().get("guestId").asString().equals(order.get(0)));
    JsonNode restored = r.snapshot(drawer).get("game");
    assertThat(restored.get("phase").asString()).isEqualTo("DRAWING");
    assertThat(restored.get("phaseId").asString()).isEqualTo(phaseId);
    assertThat(restored.get("word").asString()).isEqualTo(word);
    String strokeAction = actionId();
    r.send(
        drawer,
        "DRAW_STROKE",
        phaseId,
        stroke(UUID.randomUUID().toString(), 0, 2, true),
        strokeAction);
    awaitAck(drawer, strokeAction);

    // There is no score command, and score fields in a guess are ignored.
    String id = actionId();
    r.send(g2, "SET_SCORE", phaseId, "{\"guestId\":\"" + order.get(2) + "\",\"score\":9999}", id);
    assertThat(errorCode(g2, id)).isEqualTo("INVALID_ACTION");
    String forged =
        "{\"text\":\"%s\",\"scoreDelta\":100000,\"totalScore\":100000,\"guestId\":\"%s\",\"order\":0}"
            .formatted(word, order.get(2));
    String guessAction = actionId();
    r.send(g1, "SUBMIT_GUESS", phaseId, forged, guessAction);
    awaitAck(g1, guessAction);
    JsonNode accepted = awaitLong(g2, m -> m.type().equals("GUESS_ACCEPTED")).payload();
    assertThat(accepted.get("guestId").asString()).isEqualTo(order.get(1));
    int delta = accepted.get("scoreDelta").asInt();
    assertThat(delta).isBetween(100, 500);
    assertThat(accepted.get("totalScore").asInt()).isEqualTo(delta);

    // The same action again, and the word again under a new id, award nothing more.
    r.send(g1, "SUBMIT_GUESS", phaseId, forged, guessAction);
    assertThat(errorCode(g1, guessAction)).isEqualTo("DUPLICATE_ACTION");
    String again = actionId();
    r.send(g1, "SUBMIT_GUESS", phaseId, "{\"text\":\"" + word + "\"}", again);
    awaitAck(g1, again);
    awaitLong(drawer, m -> m.type().equals("GUESSER_CHAT")); // solver talk, not a second guess

    JsonNode game = r.snapshot(g2).get("game");
    assertThat(game.get("phase").asString()).isEqualTo("DRAWING");
    assertThat(game.get("scores").get(order.get(1)).asInt()).isEqualTo(delta);
    assertThat(game.get("scores").get(order.get(2)).asInt()).isZero();
    assertThat(game.get("word").isNull()).isTrue();
    assertThat(g2.ofType("GUESS_ACCEPTED")).hasSize(1);
    assertThat(g2.ofType("GUESSER_CHAT")).isEmpty(); // the solver's repeat of the word stays hidden
    assertThat(g2.messages()).noneMatch(m -> carries(m, word));
  }

  // ------------------------------------------------------------------ rematch

  /**
   * Plays the n-th round seen by {@code observer}: the drawer picks word 0, a guesser solves it.
   */
  private void playRound(Room r, StompTestClient observer, int nth) {
    JsonNode choice = phase(observer, "WORD_CHOICE", nth);
    String drawerId = choice.get("drawerGuestId").asString();
    String choicePhase = choice.get("phaseId").asString();
    StompTestClient drawer = r.socketOf(drawerId);
    String word =
        awaitLong(
                drawer,
                m ->
                    m.type().equals("WORD_CHOICES")
                        && m.payload().get("phaseId").asString().equals(choicePhase))
            .payload()
            .get("choices")
            .get(0)
            .asString();
    r.send(drawer, "CHOOSE_WORD", choicePhase, "{\"wordIndex\":0}", actionId());
    String phaseId = phase(observer, "DRAWING", nth).get("phaseId").asString();
    StompTestClient guesser =
        r.sockets.stream().filter(ws -> ws != drawer).findFirst().orElseThrow();
    r.send(guesser, "SUBMIT_GUESS", phaseId, "{\"text\":\"" + word + "\"}", actionId());
  }

  private String results(Guest member, String sessionId) throws Exception {
    return mvc.perform(
            get("/api/v1/sessions/" + sessionId + "/results")
                .header("Authorization", bearer(member)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void aRematchStartsFreshAndLeavesThePreviousResultsUntouched() throws Exception {
    Room r = new Room(2);
    StompTestClient host = r.sockets.get(0);
    host.command(r.code, "START_GAME", actionId(), "{}");
    String firstSession =
        awaitLong(host, m -> m.type().equals("GAME_STARTED")).payload().get("sessionId").asString();
    playRound(r, host, 1);
    playRound(r, host, 2);
    JsonNode finished = awaitLong(host, m -> m.type().equals("GAME_FINISHED")).payload();
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
    String before = results(r.guests.get(0), firstSession);
    JsonNode stored = JsonMapper.builder().build().readTree(before);
    assertThat(stored.get("standings").get(0).get("score").asInt())
        .isEqualTo(finished.get("standings").get(0).get("score").asInt());

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
    JsonNode fresh = r.snapshot(host).get("game");
    assertThat(fresh.get("round").asInt()).isEqualTo(1);
    fresh.get("scores").forEach(score -> assertThat(score.asInt()).isZero());
    assertThat(fresh.get("strokes")).isEmpty();

    // Scoring in the new session does not touch the stored results of the first one.
    playRound(r, host, 3);
    await().atMost(LONG).until(() -> host.ofType("ROUND_ENDED").size() >= 3);
    assertThat(host.ofType("ROUND_ENDED").getLast().payload().get("round").asInt()).isEqualTo(1);
    assertThat(results(r.guests.get(0), firstSession)).isEqualTo(before);
    assertThat(secondSession).isNotEqualTo(firstSession);
    Integer finishedCount =
        jdbc.queryForObject(
            "select count(*) from game_session where room_id = (select room_id from game_session"
                + " where id = ?::uuid) and status = 'FINISHED'",
            Integer.class,
            firstSession);
    assertThat(finishedCount).isEqualTo(1);
  }

  // ------------------------------------------------------------------ limits

  @Test
  void aMaximalCanvasIsDeliveredInOneSnapshotAndBudgetErrorsArePrivate() throws Exception {
    Room r = new Room(2);
    r.sockets.get(0).command(r.code, "START_GAME", actionId(), "{}");
    StompTestClient host = r.sockets.get(0);
    JsonNode started = awaitLong(host, m -> m.type().equals("GAME_STARTED")).payload();
    StompTestClient drawer = r.socketOf(started.get("order").get(0).asString());
    StompTestClient guesser = r.socketOf(started.get("order").get(1).asString());
    JsonNode choice = phase(host, "WORD_CHOICE", 1);
    r.send(
        drawer, "CHOOSE_WORD", choice.get("phaseId").asString(), "{\"wordIndex\":0}", actionId());
    String phaseId = phase(host, "DRAWING", 1).get("phaseId").asString();

    // 6 strokes × 10 batches × 200 points = 12 000 live points. Paced below 40 batches/s.
    for (int s = 0; s < 6; s++) {
      String strokeId = UUID.randomUUID().toString();
      for (int b = 0; b < 10; b++) {
        String id = actionId();
        r.send(drawer, "DRAW_STROKE", phaseId, stroke(strokeId, b, 200, b == 9), id);
        awaitAck(drawer, id);
        Thread.sleep(30);
      }
    }
    String full = actionId();
    r.send(drawer, "DRAW_STROKE", phaseId, stroke(UUID.randomUUID().toString(), 0, 1, true), full);
    StompTestClient.Message rejected =
        awaitLong(
            drawer,
            m ->
                m.type().equals("GAME_ERROR")
                    && full.equals(m.payload().get("actionId").asString()));
    assertThat(rejected.payload().get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
    assertThat(rejected.envelope().get("stream").asString()).isEqualTo("PRIVATE");

    // A payload over the 8 KiB transport cap is refused before the mailbox (errors queue).
    String oversized = actionId();
    r.send(
        drawer,
        "DRAW_STROKE",
        phaseId,
        stroke(UUID.randomUUID().toString(), 0, 200, false)
            .replace("\"#336699\"", "\"#336699\",\"pad\":\"" + "x".repeat(6000) + "\""),
        oversized);
    StompTestClient.Message tooBig =
        awaitLong(
            drawer,
            m ->
                m.type().equals("ERROR")
                    && oversized.equals(m.payload().get("actionId").asString()));
    assertThat(tooBig.payload().get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");

    JsonNode snapshot = r.snapshot(guesser);
    int bytes = snapshot.toString().getBytes(StandardCharsets.UTF_8).length;
    JsonNode game = snapshot.get("game");
    int points = 0;
    for (JsonNode s : game.get("strokes")) {
      points += s.get("points").size();
    }
    assertThat(points).isEqualTo(12_000);
    assertThat(game.get("canvasBudget").get("points").asInt()).isEqualTo(12_000);
    assertThat(bytes).isLessThan(262_144);
  }

  @Test
  void theModuleDefaultsEqualTheCatalogRow() {
    String json =
        jdbc.queryForObject(
            "select default_settings::text from game_definition where key = 'draw-guess'",
            String.class);
    Map<String, Object> row =
        JsonMapper.builder()
            .build()
            .readValue(json, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    assertThat(row).isEqualTo(DrawGuessSettings.defaults());
    Integer enabled =
        jdbc.queryForObject(
            "select count(*) from game_definition where key = 'draw-guess' and enabled"
                + " and min_players = 2 and max_players = 10",
            Integer.class);
    assertThat(enabled).isEqualTo(1);
  }
}
