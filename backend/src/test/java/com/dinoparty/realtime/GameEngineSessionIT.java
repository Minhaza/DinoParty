package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Engine behavior that needs a stable phase: rounds last a minute here, so nothing changes under
 * the test unless it asks for it (the test game ends a round when its turn player sends TEST_NEXT).
 */
@RealtimeIntegrationTest
@TestPropertySource(
    properties = {
      "test-game.round-millis=60000",
      // The periodic guest-session recheck reads the database and would add noise to the
      // statement count of the "no database per command" test.
      "app.realtime.session-recheck-interval=1h"
    })
class GameEngineSessionIT extends GameEngineSupport {

  // ------------------------------------------------------------------ authorization and validation

  @Test
  void startIsHostOnlyNeedsPlayersAndOutsidersAreRefused() {
    Table lobby = new Table(2);

    lobby.sockets.get(1).command(lobby.code, "START_GAME", actionId(), "{}");
    StompTestClient.Message notHost = lobby.sockets.get(1).awaitType("GAME_ERROR");
    assertThat(notHost.payload().get("code").asString()).isEqualTo("NOT_HOST");
    assertThat(lobby.sockets.get(1).ofType("ROOM_STATUS_CHANGED")).isEmpty();

    Guest outsider = newGuest();
    StompTestClient ws = connect(outsider);
    ws.subscribe("err", "/user/queue/errors");
    ws.command(
        lobby.code,
        "{\"type\":\"TEST_SCORE\",\"actionId\":\"%s\",\"phaseId\":\"%s\",\"payload\":{\"points\":1}}"
            .formatted(actionId(), UUID.randomUUID()));
    assertThat(ws.awaitType("ERROR").payload().get("code").asString()).isEqualTo("NOT_IN_ROOM");
  }

  @Test
  void aSingleMemberCannotStart() {
    Table small = new Table(1);

    small.host().command(small.code, "START_GAME", actionId(), "{}");

    assertThat(small.host().awaitType("GAME_ERROR").payload().get("code").asString())
        .isEqualTo("NOT_ENOUGH_PLAYERS");
    assertThat(roomService.getForMember(small.guests.get(0).id(), small.code).status().name())
        .isEqualTo("WAITING");
  }

  @Test
  void gameCommandsAreValidatedStaleDuplicateAndAuthorized() {
    Table t = new Table(2);
    t.start();
    t.host().awaitType("PHASE_STARTED");
    String phase = t.phaseId(t.host());
    StompTestClient ws = t.sockets.get(1);

    // Wrong phase id: stale. Missing phase id: validation. Same action id twice: duplicate.
    t.send(ws, "TEST_SCORE", UUID.randomUUID().toString(), "{\"points\":1}", actionId());
    assertThat(ws.awaitType("GAME_ERROR").payload().get("code").asString())
        .isEqualTo("STALE_ACTION");
    t.send(ws, "TEST_SCORE", null, "{\"points\":1}", actionId());
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                ws.ofType("GAME_ERROR").stream()
                    .anyMatch(e -> code(e).equals("VALIDATION_FAILED")));
    String repeated = actionId();
    t.send(ws, "TEST_SCORE", phase, "{\"points\":2}", repeated);
    ws.awaitType("ACTION_ACK");
    t.send(ws, "TEST_SCORE", phase, "{\"points\":2}", repeated);
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                ws.ofType("GAME_ERROR").stream().anyMatch(e -> code(e).equals("DUPLICATE_ACTION")));
    assertThat(sessions.info(t.code).scores().get(t.guests.get(1).id())).isEqualTo(2);

    // Semantic rejection by the module and a command the room's game does not own.
    t.send(ws, "TEST_SCORE", phase, "{\"points\":99}", actionId());
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> ws.ofType("GAME_ERROR").stream().anyMatch(e -> code(e).equals("INVALID_ACTION")));
    t.send(ws, "SUBMIT_GUESS", phase, "{\"text\":\"x\"}", actionId());
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                ws.ofType("GAME_ERROR").stream()
                        .filter(e -> code(e).equals("INVALID_ACTION"))
                        .count()
                    >= 2);
    assertThat(sessions.info(t.code).scores().get(t.guests.get(1).id())).isEqualTo(2);
  }

  @Test
  void anOversizedPayloadIsRefusedBeforeItReachesTheGame() {
    Table t = new Table(2);
    t.start();
    t.host().awaitType("PHASE_STARTED");
    StompTestClient ws = t.sockets.get(1);
    String big = "x".repeat(400);

    t.send(
        ws,
        "TEST_SCORE",
        t.phaseId(t.host()),
        "{\"points\":1,\"junk\":\"" + big + "\"}",
        actionId());

    assertThat(ws.awaitType("ERROR").payload().get("code").asString())
        .isEqualTo("PAYLOAD_TOO_LARGE");
    assertThat(sessions.info(t.code).scores().get(t.guests.get(1).id())).isZero();
  }

  @Test
  void floodingAGameCommandIsRateLimitedPerType() {
    Table t = new Table(2);
    t.start();
    t.host().awaitType("PHASE_STARTED");
    String phase = t.phaseId(t.host());
    StompTestClient ws = t.sockets.get(1);

    // TEST_NEXT allows 5 per second; the sixth in the same second is limited (not the turn
    // player, so the module rejects the others, which still count against the bucket).
    for (int i = 0; i < 12; i++) {
      t.send(ws, "TEST_NEXT", phase, "{}", actionId());
    }

    assertThat(ws.awaitType("ERROR").payload().get("code").asString()).isEqualTo("RATE_LIMITED");
  }

  // ------------------------------------------------------------------ snapshots

  @Test
  void aReconnectingPlayerGetsAViewerSpecificSnapshotInEveryRoomStatus() {
    Table t = new Table(3);

    // WAITING: no game part.
    JsonNode waiting = t.snapshotFor(t.sockets.get(1)).payload();
    assertThat(waiting.get("game").isNull()).isTrue();
    assertThat(waiting.get("room").get("lastSession").isNull()).isTrue();

    t.start();
    t.host().awaitType("PHASE_STARTED");
    // STARTING/early PLAYING: either no game yet or a consistent one; once playing it is complete.
    JsonNode started = t.host().awaitType("GAME_STARTED").payload();
    t.host().awaitType("PHASE_STARTED");
    int turn = t.indexOf(started.get("order").get(0).asString());
    String secret = t.sockets.get(turn).awaitType("TEST_SECRET").payload().get("secret").asString();
    // Reconnect a non-host who is not the turn player, so the host socket keeps its history.
    int other = turn == 1 ? 2 : 1;

    // Drop a socket and come back, like a refresh.
    t.sockets.get(other).drop();
    StompTestClient again = connectAndSubscribe(t.guests.get(other), t.code);
    t.sockets.set(other, again);
    JsonNode snapshot = t.snapshotFor(again).payload();
    JsonNode game = snapshot.get("game");
    assertThat(game.get("status").asString()).isEqualTo("RUNNING");
    assertThat(game.get("phaseId").asString()).isEqualTo(t.phaseId(t.host()));
    assertThat(game.has("secret")).isFalse();
    assertThat(snapshot.toString()).doesNotContain(secret);
    assertThat(snapshot.get("room").get("sessionId").asString())
        .isEqualTo(started.get("sessionId").asString());
    assertThat(snapshot.get("room").get("lastSession").isNull()).isTrue();
    // The turn player sees the secret in their own snapshot.
    JsonNode own = t.snapshotFor(t.sockets.get(turn)).payload().get("game");
    assertThat(own.get("secret").asString()).isEqualTo(secret);
    // The snapshot's private seq matches the envelope that carried it.
    StompTestClient.Message envelope = again.ofType("ROOM_SNAPSHOT").getLast();
    assertThat(envelope.payload().get("seq").get("private").asLong()).isEqualTo(envelope.seq());

    // RESULTS: the final standings and the rematch tally survive a refresh. Each round ends when
    // its turn player says so.
    t.send(t.sockets.get(turn), "TEST_NEXT", t.phaseId(t.host()), "{}", actionId());
    await().atMost(Duration.ofSeconds(5)).until(() -> t.host().ofType("PHASE_STARTED").size() == 2);
    JsonNode second = t.host().ofType("GAME_STARTED").getFirst().payload();
    int turn2 = t.indexOf(second.get("order").get(1).asString());
    t.send(t.sockets.get(turn2), "TEST_NEXT", t.phaseId(t.host()), "{}", actionId());
    t.host().awaitType("GAME_FINISHED");
    JsonNode resultsSnapshot = t.snapshotFor(again).payload();
    JsonNode last = resultsSnapshot.get("room").get("lastSession");
    assertThat(last.get("sessionId").asString()).isEqualTo(started.get("sessionId").asString());
    assertThat(last.get("status").asString()).isEqualTo("FINISHED");
    assertThat(last.get("endReason").asString()).isEqualTo("COMPLETED");
    JsonNode results = resultsSnapshot.get("game");
    assertThat(results.get("status").asString()).isEqualTo("FINISHED");
    assertThat(results.get("results").get("standings")).hasSize(3);
    assertThat(results.get("rematch").get("votes").isObject()).isTrue();

    // After the reset the game part is gone again.
    t.host().command(t.code, "REMATCH_VOTE", actionId(), "{\"vote\":true}");
    t.awaitStatus(again, "WAITING", "REMATCH");
    JsonNode reset = t.snapshotFor(again).payload();
    assertThat(reset.get("game").isNull()).isTrue();
    assertThat(reset.get("room").get("lastSession").get("status").asString()).isEqualTo("FINISHED");
  }

  @Test
  void aStrokeStyleCommandCostsNoDatabaseStatements() throws Exception {
    // Many accepted commands inside one phase must be served from memory ([C-12], D-engine 3.2).
    Table t = new Table(2);
    t.start();
    t.host().awaitType("PHASE_STARTED");
    String phase = t.phaseId(t.host());
    StompTestClient ws = t.sockets.get(1);
    // Settle: wait for the initial lifecycle queries and the history insert.
    await().atMost(Duration.ofSeconds(5)).until(() -> sessions.isActive(t.code));
    t.snapshotFor(ws);

    awaitQuiet(); // earlier tests disconnect players whose grace expiry still reads the database
    Map<String, Long> before = statementCounts();
    int sent = 40;
    for (int i = 0; i < sent; i++) {
      t.send(ws, "TEST_SCORE", phase, "{\"points\":1}", actionId());
    }
    await().atMost(Duration.ofSeconds(10)).until(() -> ws.ofType("ACTION_ACK").size() >= sent);
    Map<String, Long> after = statementCounts();

    assertThat(sessions.info(t.code).scores().get(t.guests.get(1).id())).isEqualTo(sent);
    assertThat(after.get("statements")).isEqualTo(before.get("statements"));
  }

  /** Waits until no statement ran for longer than the disconnect grace of the test profile. */
  private void awaitQuiet() throws InterruptedException {
    long last = statementCounts().get("statements");
    long quietSince = System.nanoTime();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      Thread.sleep(100);
      long now = statementCounts().get("statements");
      if (now != last) {
        last = now;
        quietSince = System.nanoTime();
      } else if (System.nanoTime() - quietSince > TimeUnit.MILLISECONDS.toNanos(2000)) {
        return;
      }
    }
  }

  @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;

  private Map<String, Long> statementCounts() {
    var stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("statements", stats.getPrepareStatementCount());
    return counts;
  }

  // ------------------------------------------------------------------ host REST actions

  @Test
  void hostCanStartKickAndCloseOverRest() throws Exception {
    Table t = new Table(3);
    Guest host = t.guests.get(0);
    Guest member = t.guests.get(1);

    mvc.perform(post("/api/v1/rooms/" + t.code + "/start").header("Authorization", bearer(member)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_HOST"));
    mvc.perform(post("/api/v1/rooms/" + t.code + "/start").header("Authorization", bearer(host)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("STARTING"));
    // Repeating start while STARTING is idempotent.
    mvc.perform(post("/api/v1/rooms/" + t.code + "/start").header("Authorization", bearer(host)))
        .andExpect(status().isAccepted());
    t.host().awaitType("GAME_STARTED");

    String kick = "{\"guestId\":\"%s\"}".formatted(t.guests.get(2).id());
    mvc.perform(
            post("/api/v1/rooms/" + t.code + "/kick")
                .header("Authorization", bearer(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content(kick))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/rooms/" + t.code + "/kick")
                .header("Authorization", bearer(host))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestId\":\"%s\"}".formatted(host.id())))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/rooms/" + t.code + "/kick")
                .header("Authorization", bearer(host))
                .contentType(MediaType.APPLICATION_JSON)
                .content(kick))
        .andExpect(status().isNoContent());
    StompTestClient.Message left = t.host().awaitType("PLAYER_LEFT");
    assertThat(left.payload().get("reason").asString()).isEqualTo("KICKED");
    // The kicked player no longer holds a seat in the running game.
    assertThat(sessions.gameSnapshot(t.code, t.guests.get(2).id())).isNull();

    mvc.perform(post("/api/v1/rooms/" + t.code + "/close").header("Authorization", bearer(member)))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/rooms/" + t.code + "/close").header("Authorization", bearer(host)))
        .andExpect(status().isNoContent());
    mvc.perform(post("/api/v1/rooms/" + t.code + "/close").header("Authorization", bearer(host)))
        .andExpect(status().isNoContent()); // idempotent
    mvc.perform(get("/api/v1/rooms/" + t.code).header("Authorization", bearer(host)))
        .andExpect(jsonPath("$.status").value("CLOSED"));
    await().atMost(Duration.ofSeconds(5)).until(() -> !sessions.isActive(t.code));
    // Members are free to join another room.
    assertThat(roomService.activeRoom(member.id())).isEmpty();
  }
}
