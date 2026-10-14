package com.dinoparty.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.IntegrationTest;
import com.dinoparty.engine.PlayerInfo;
import com.dinoparty.engine.RoundRecord;
import com.dinoparty.guest.GuestRegistration;
import com.dinoparty.guest.GuestService;
import com.dinoparty.room.CreateRoomCommand;
import com.dinoparty.room.RoomDetails;
import com.dinoparty.room.RoomService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Durable history on real PostgreSQL: constraints, write-behind with retry, restart recovery and
 * the results endpoint states (docs/game-engine.md §8, §9).
 */
@IntegrationTest
class SessionPersistenceIT {

  @Autowired SessionRecorder recorder;
  @Autowired SessionRecovery recovery;
  @Autowired GuestService guests;
  @Autowired RoomService rooms;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  private record Member(UUID id, String token) {}

  private Member guest() {
    GuestRegistration r =
        guests.register("H" + UUID.randomUUID().toString().substring(0, 8), "dino-rex", null, "it");
    return new Member(r.guest().id(), r.token());
  }

  private RoomDetails room(Member host) {
    return rooms.create(host.id(), new CreateRoomCommand("test-game", null, null));
  }

  private SessionStart start(RoomDetails room, Member... members) {
    List<PlayerInfo> roster =
        java.util.Arrays.stream(members)
            .map(
                m ->
                    new PlayerInfo(
                        m.id(), "Nick" + m.id().toString().substring(0, 4), "dino-rex", "#336699"))
            .toList();
    return new SessionStart(
        UUID.randomUUID(), room.id(), "test-game", Map.of(), roster, 42L, Instant.now());
  }

  private String sessionStatus(UUID id) {
    return jdbc.queryForObject("select status from game_session where id = ?", String.class, id);
  }

  @Test
  void aRoomCanHaveOnlyOneRunningSessionAndNumbersIncrease() {
    Member host = guest();
    RoomDetails room = room(host);
    SessionStart first = start(room, host);
    recorder.sessionStarted(first);

    assertThatThrownBy(() -> recorder.sessionStarted(start(room, host)))
        .isInstanceOf(DataIntegrityViolationException.class);

    recorder.sessionAborted(first.sessionId(), "NO_COMPLETED_ROUND", Instant.now());
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> sessionStatus(first.sessionId()).equals("ABORTED"));
    SessionStart second = start(room, host);
    recorder.sessionStarted(second);
    assertThat(
            jdbc.queryForObject(
                "select session_number from game_session where id = ?",
                Integer.class,
                second.sessionId()))
        .isEqualTo(2);
  }

  @Test
  void roundsAndResultsAreWrittenAndReadable() throws Exception {
    Member host = guest();
    Member other = guest();
    RoomDetails room = room(host);
    rooms.join(other.id(), room.code());
    SessionStart start = start(room, host, other);
    recorder.sessionStarted(start);
    recorder.roundCompleted(
        start.sessionId(),
        new RoundRecord(
            1, Instant.now(), Instant.now(), "TIME", Map.of(host.id(), 3), Map.of("secret", "x")));
    recorder.sessionFinished(
        new SessionResult(
            start.sessionId(),
            "COMPLETED",
            Instant.now(),
            List.of(
                new SessionResult.Standing(1, host.id(), "Host", 3, Map.of()),
                new SessionResult.Standing(2, other.id(), "Other", 0, Map.of()))));

    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> sessionStatus(start.sessionId()).equals("FINISHED"));
    mvc.perform(
            get("/api/v1/sessions/" + start.sessionId() + "/results")
                .header("Authorization", "Bearer " + other.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FINISHED"))
        .andExpect(jsonPath("$.standings[0].nickname").value("Host"))
        .andExpect(jsonPath("$.rounds[0].meta.secret").value("x"));
  }

  @Test
  void aRunningSessionHasNoResultsYetAndIsOnlyVisibleToMembers() throws Exception {
    Member host = guest();
    RoomDetails room = room(host);
    SessionStart start = start(room, host);
    recorder.sessionStarted(start);

    mvc.perform(
            get("/api/v1/sessions/" + start.sessionId() + "/results")
                .header("Authorization", "Bearer " + host.token()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RESULTS_NOT_READY"));
    mvc.perform(
            get("/api/v1/sessions/" + start.sessionId() + "/results")
                .header("Authorization", "Bearer " + guest().token()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/sessions/" + start.sessionId() + "/results"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aDatabaseOutageDelaysResultsButNeverBlocksOrLosesThem() {
    Member host = guest();
    RoomDetails room = room(host);
    SessionStart start = start(room, host);
    recorder.sessionStarted(start);

    jdbc.execute("alter table game_result rename to game_result_away");
    try {
      long startedAt = System.nanoTime();
      recorder.sessionFinished(
          new SessionResult(
              start.sessionId(),
              "COMPLETED",
              Instant.now(),
              List.of(new SessionResult.Standing(1, host.id(), "Host", 1, Map.of()))));
      // The caller (the room mailbox in production) is not held up by the failing write.
      assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
          .isLessThan(Duration.ofMillis(500));
      await()
          .pollDelay(Duration.ofMillis(700))
          .until(() -> sessionStatus(start.sessionId()).equals("RUNNING"));
    } finally {
      jdbc.execute("alter table game_result_away rename to game_result");
    }

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> sessionStatus(start.sessionId()).equals("FINISHED"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from game_result where session_id = ?",
                Integer.class,
                start.sessionId()))
        .isEqualTo(1);
  }

  @Test
  void startupRecoveryInterruptsRunningSessionsAndUnsticksRooms() throws Exception {
    Member host = guest();
    Member other = guest();
    RoomDetails room = room(host);
    rooms.join(other.id(), room.code());
    rooms.setReady(other.id(), room.code(), true);
    SessionStart start = start(room, host, other);
    recorder.sessionStarted(start);
    jdbc.update("update room set status = 'PLAYING' where id = ?", room.id());

    recovery.recover();

    assertThat(sessionStatus(start.sessionId())).isEqualTo("INTERRUPTED");
    assertThat(
            jdbc.queryForObject(
                "select end_reason from game_session where id = ?",
                String.class,
                start.sessionId()))
        .isEqualTo("SERVER_RESTART");
    assertThat(jdbc.queryForObject("select status from room where id = ?", String.class, room.id()))
        .isEqualTo("WAITING");
    // Members stay, ready flags are cleared.
    RoomDetails after = rooms.getForMember(host.id(), room.code());
    assertThat(after.members()).hasSize(2).noneMatch(m -> m.ready());
    // The interrupted game answers with no standings instead of "not ready".
    mvc.perform(
            get("/api/v1/sessions/" + start.sessionId() + "/results")
                .header("Authorization", "Bearer " + host.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("INTERRUPTED"))
        .andExpect(jsonPath("$.endReason").value("SERVER_RESTART"))
        .andExpect(jsonPath("$.standings.length()").value(0));
    // A second recovery changes nothing.
    recovery.recover();
    assertThat(sessionStatus(start.sessionId())).isEqualTo("INTERRUPTED");
  }
}
