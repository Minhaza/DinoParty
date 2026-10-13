package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The shared engine end to end over real STOMP with the test-only game and short rounds (600 ms,
 * countdown 300 ms, application-test.yml): the full lifecycle, rematch and countdown races.
 */
@RealtimeIntegrationTest
class GameEngineIT extends GameEngineSupport {

  // ------------------------------------------------------------------ lifecycle

  @Test
  void aFullGameRunsThroughTheEngineAndRematchStartsASecondSession() throws Exception {
    Table t = new Table(3);

    t.start();
    StompTestClient.Message starting =
        t.host()
            .awaitMessage(
                m ->
                    m.type().equals("ROOM_STATUS_CHANGED")
                        && m.payload().get("status").asString().equals("STARTING"));
    assertThat(starting.payload().get("reason").asString()).isEqualTo("HOST_STARTED");
    assertThat(starting.payload().get("startsAt").asString()).isNotBlank();
    JsonNode started = t.host().awaitType("GAME_STARTED").payload();
    String sessionId = started.get("sessionId").asString();
    assertThat(started.get("order")).hasSize(3);
    assertThat(started.has("seed")).isFalse();
    String firstPhase = t.host().awaitType("PHASE_STARTED").payload().get("phaseId").asString();

    // The player at order[0] holds the round-1 secret and is the only one told about it.
    int turn = t.indexOf(started.get("order").get(0).asString());
    StompTestClient turnWs = t.sockets.get(turn);
    String secret = turnWs.awaitType("TEST_SECRET").payload().get("secret").asString();
    for (int i = 0; i < 3; i++) {
      if (i != turn) {
        assertThat(t.sockets.get(i).ofType("TEST_SECRET")).isEmpty();
      }
    }

    int scorer = (turn + 1) % 3;
    String scoreId = actionId();
    t.send(t.sockets.get(scorer), "TEST_SCORE", firstPhase, "{\"points\":3}", scoreId);
    StompTestClient.Message ack =
        t.sockets
            .get(scorer)
            .awaitMessage(
                m ->
                    m.type().equals("ACTION_ACK")
                        && m.payload().get("actionId").asString().equals(scoreId));
    assertThat(ack.payload().get("actionId").asString()).isEqualTo(scoreId);
    t.host().awaitType("TEST_SCORED");

    JsonNode finished = t.host().awaitType("GAME_FINISHED").payload();
    assertThat(finished.get("sessionId").asString()).isEqualTo(sessionId);
    assertThat(finished.get("endReason").asString()).isEqualTo("COMPLETED");
    JsonNode first = finished.get("standings").get(0);
    assertThat(first.get("rank").asInt()).isEqualTo(1);
    assertThat(first.get("score").asInt()).isEqualTo(3);
    assertThat(first.get("guestId").asString()).isEqualTo(t.guests.get(scorer).id().toString());
    t.awaitStatus(t.host(), "RESULTS", "GAME_FINISHED");

    // Events reach everyone in the same order, with a gapless room sequence.
    List<Long> seqs =
        t.host().onStream("ROOM", t.code).stream().map(StompTestClient.Message::seq).toList();
    for (int i = 1; i < seqs.size(); i++) {
      assertThat(seqs.get(i)).isEqualTo(seqs.get(i - 1) + 1);
    }
    List<String> statuses =
        t.host().ofType("ROOM_STATUS_CHANGED").stream()
            .map(m -> m.payload().get("status").asString())
            .toList();
    assertThat(statuses).containsExactly("STARTING", "PLAYING", "RESULTS");

    // The secret stays private until the round ends, then a public event reveals it.
    for (int i = 0; i < 3; i++) {
      if (i == turn) {
        continue;
      }
      assertThat(t.sockets.get(i).onStream("PRIVATE", t.code).toString()).doesNotContain(secret);
    }
    List<StompTestClient.Message> leaked =
        t.host().onStream("ROOM", t.code).stream()
            .filter(m -> m.envelope().toString().contains(secret))
            .toList();
    assertThat(leaked).extracting(StompTestClient.Message::type).containsOnly("TEST_ROUND_ENDED");

    // Results are persisted and readable by members only.
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                jdbc.queryForObject(
                        "select status from game_session where id = ?::uuid",
                        String.class,
                        sessionId)
                    .equals("FINISHED"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from game_result where session_id = ?::uuid",
                Integer.class,
                sessionId))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from game_round where session_id = ?::uuid",
                Integer.class,
                sessionId))
        .isEqualTo(2);
    mvc.perform(
            get("/api/v1/sessions/" + sessionId + "/results")
                .header("Authorization", bearer(t.guests.get(1))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FINISHED"))
        .andExpect(jsonPath("$.standings.length()").value(3))
        .andExpect(jsonPath("$.rounds.length()").value(2));
    Guest outsider = newGuest();
    mvc.perform(
            get("/api/v1/sessions/" + sessionId + "/results")
                .header("Authorization", bearer(outsider)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_IN_ROOM"));
    mvc.perform(
            get("/api/v1/sessions/" + UUID.randomUUID() + "/results")
                .header("Authorization", bearer(outsider)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));

    // A non-host "yes" does not reset; the host's does.
    t.sockets.get(1).command(t.code, "REMATCH_VOTE", actionId(), "{\"vote\":true}");
    t.host().awaitType("REMATCH_VOTES_UPDATED");
    assertThat(roomService.getForMember(t.guests.get(0).id(), t.code).status().name())
        .isEqualTo("RESULTS");
    t.host().command(t.code, "REMATCH_VOTE", actionId(), "{\"vote\":true}");
    t.awaitStatus(t.host(), "WAITING", "REMATCH");
    assertThat(sessions.isActive(t.code)).isFalse();

    t.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> t.host().ofType("GAME_STARTED").size() == 2);
    String secondSession =
        t.host().ofType("GAME_STARTED").getLast().payload().get("sessionId").asString();
    assertThat(secondSession).isNotEqualTo(sessionId);
    t.host().awaitMessage(m -> m.type().equals("GAME_FINISHED") && m != finishedMessage(t));
    assertThat(
            jdbc.queryForObject(
                "select session_number from game_session where id = ?::uuid",
                Integer.class,
                secondSession))
        .isEqualTo(2);
  }

  private static StompTestClient.Message finishedMessage(Table t) {
    return t.host().ofType("GAME_FINISHED").getFirst();
  }

  // ------------------------------------------------------------------ countdown races

  @Test
  void concurrentStartRequestsProduceExactlyOneCountdown() throws Exception {
    Table t = new Table(3);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch go = new CountDownLatch(1);
    UUID host = t.guests.get(0).id();
    for (int i = 0; i < 8; i++) {
      pool.submit(
          () -> {
            go.await();
            sessions.start(t.code, host);
            return null;
          });
    }
    go.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

    t.host().awaitType("GAME_STARTED");
    assertThat(
            t.host().ofType("ROOM_STATUS_CHANGED").stream()
                .filter(m -> m.payload().get("status").asString().equals("STARTING")))
        .hasSize(1);
    assertThat(t.host().ofType("GAME_STARTED")).hasSize(1);
  }

  @Test
  void aMemberLeavingDuringTheCountdownCancelsIt() {
    Table t = new Table(2);
    t.host().command(t.code, "START_GAME", actionId(), "{}");
    t.awaitStatus(t.host(), "STARTING", "HOST_STARTED");

    roomService.leave(t.guests.get(1).id(), t.code);

    t.awaitStatus(t.host(), "WAITING", "START_CANCELLED");
    assertThat(t.host().ofType("GAME_STARTED")).isEmpty();
    assertThat(sessions.isActive(t.code)).isFalse();
  }
}
