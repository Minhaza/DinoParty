package com.dinoparty.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.IntegrationTest;
import com.dinoparty.engine.PlayerInfo;
import com.dinoparty.engine.SubmissionRecord;
import com.dinoparty.guest.GuestRegistration;
import com.dinoparty.guest.GuestService;
import com.dinoparty.room.CreateRoomCommand;
import com.dinoparty.room.RoomDetails;
import com.dinoparty.room.RoomService;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Telephone albums on real PostgreSQL: never-drop submission writes (D44) and every state of {@code
 * GET /sessions/{id}/albums[/{chainIndex}]} (api-contracts §2, §3.10; D43, D45).
 */
@IntegrationTest
class TelephoneAlbumsIT {

  @Autowired SessionRecorder recorder;
  @Autowired GuestService guests;
  @Autowired RoomService rooms;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  private record Member(UUID id, String token, String nickname) {}

  private Member guest() {
    String nickname = "T" + UUID.randomUUID().toString().substring(0, 8);
    GuestRegistration r = guests.register(nickname, "dino-rex", null, "it");
    return new Member(r.guest().id(), r.token(), nickname);
  }

  private RoomDetails room(String gameKey, Member host, Member... others) {
    RoomDetails room = rooms.create(host.id(), new CreateRoomCommand(gameKey, null, null));
    for (Member m : others) {
      rooms.join(m.id(), room.code());
    }
    return room;
  }

  private SessionStart start(RoomDetails room, String gameKey, Member... members) {
    List<PlayerInfo> roster =
        Arrays.stream(members)
            .map(m -> new PlayerInfo(m.id(), m.nickname(), "dino-rex", "#336699"))
            .toList();
    SessionStart start =
        new SessionStart(
            UUID.randomUUID(), room.id(), gameKey, Map.of(), roster, 42L, Instant.now());
    recorder.sessionStarted(start);
    return start;
  }

  private static SubmissionRecord text(int chain, int step, Member author, String text, int e) {
    return new SubmissionRecord(
        chain,
        step,
        author.id(),
        SubmissionRecord.Kind.TEXT,
        text,
        null,
        "NONE",
        false,
        false,
        e,
        Instant.now());
  }

  private static SubmissionRecord drawing(
      int chain, int step, Member author, List<Map<String, Object>> strokes, int e) {
    boolean blank = strokes.isEmpty();
    return new SubmissionRecord(
        chain,
        step,
        author.id(),
        SubmissionRecord.Kind.DRAWING,
        null,
        strokes,
        blank ? "TIMEOUT" : "NONE",
        blank,
        blank,
        e,
        Instant.now());
  }

  private static Map<String, Object> stroke() {
    Map<String, Object> stroke = new LinkedHashMap<>();
    stroke.put("strokeId", "7d0f3a52-2b7e-4a6c-9d61-3f5f1c1b0a01");
    stroke.put("tool", "ERASER");
    stroke.put("color", "#2B2B2B");
    stroke.put("size", 3);
    stroke.put("points", List.of(new int[] {1000, 1000}, new int[] {2000, 2500}));
    return stroke;
  }

  private void finish(SessionStart start, String reason) {
    recorder.sessionFinished(
        new SessionResult(start.sessionId(), reason, Instant.now(), List.of()));
    await().atMost(Duration.ofSeconds(10)).until(() -> sessionStatus(start).equals("FINISHED"));
  }

  private String sessionStatus(SessionStart start) {
    return jdbc.queryForObject(
        "select status from game_session where id = ?", String.class, start.sessionId());
  }

  private ResultActions read(String path, Member who) throws Exception {
    return mvc.perform(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/sessions/" + path)
            .header("Authorization", "Bearer " + who.token()));
  }

  @Test
  void albumsAreNotReadyWhileTheSessionRunsAndOnlyMembersMayAsk() throws Exception {
    Member host = guest();
    Member other = guest();
    RoomDetails room = room("telephone", host, other);
    SessionStart start = start(room, "telephone", host, other);
    recorder.submissionAccepted(start.sessionId(), text(0, 0, host, "zz-hidden", 2));

    // the reveal cannot be bypassed: nothing is served while the game runs ([TP-8], D43)
    read(start.sessionId() + "/albums", other)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RESULTS_NOT_READY"));
    read(start.sessionId() + "/albums/0", other)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RESULTS_NOT_READY"));
    // another room's member, nobody, an unknown session
    read(start.sessionId() + "/albums", guest())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_IN_ROOM"));
    read(start.sessionId() + "/albums/0", guest())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_IN_ROOM"));
    mvc.perform(get("/api/v1/sessions/" + start.sessionId() + "/albums"))
        .andExpect(status().isUnauthorized());
    read(UUID.randomUUID() + "/albums", host)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
  }

  @Test
  void aFinishedSessionServesItsAlbumsInStepOrderWithAuthorsAndBadges() throws Exception {
    Member host = guest();
    Member other = guest();
    RoomDetails room = room("telephone", host, other);
    SessionStart start = start(room, "telephone", host, other);
    UUID id = start.sessionId();
    // written out of order on purpose: the read side orders by step
    recorder.submissionAccepted(id, drawing(0, 1, other, List.of(stroke()), 2));
    recorder.submissionAccepted(id, text(0, 0, host, "zz-sun <b>&</b> 🦖", 2));
    recorder.submissionAccepted(id, text(1, 0, other, "zz-moon", 2));
    recorder.submissionAccepted(id, drawing(1, 1, host, List.of(), 2));
    finish(start, "COMPLETED");

    read(id + "/albums", other)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionId").value(id.toString()))
        .andExpect(jsonPath("$.complete").value(true))
        .andExpect(jsonPath("$.albums.length()").value(2))
        .andExpect(jsonPath("$.albums[0].chainIndex").value(0))
        .andExpect(jsonPath("$.albums[0].ownerGuestId").value(host.id().toString()))
        .andExpect(jsonPath("$.albums[0].entryCount").value(2))
        .andExpect(jsonPath("$.albums[0].entriesPerChain").value(2))
        .andExpect(jsonPath("$.albums[0].complete").value(true))
        .andExpect(jsonPath("$.albums[0].hasPlaceholders").value(false))
        .andExpect(jsonPath("$.albums[1].ownerGuestId").value(other.id().toString()))
        .andExpect(jsonPath("$.albums[1].hasPlaceholders").value(true))
        // the summary list carries no content
        .andExpect(jsonPath("$.albums[0].entries").doesNotExist());

    read(id + "/albums/0", host)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionId").value(id.toString()))
        .andExpect(jsonPath("$.chainIndex").value(0))
        .andExpect(jsonPath("$.ownerGuestId").value(host.id().toString()))
        .andExpect(jsonPath("$.entriesPerChain").value(2))
        .andExpect(jsonPath("$.complete").value(true))
        .andExpect(jsonPath("$.entries.length()").value(2))
        .andExpect(jsonPath("$.entries[0].step").value(0))
        .andExpect(jsonPath("$.entries[0].kind").value("TEXT"))
        .andExpect(jsonPath("$.entries[0].text").value("zz-sun <b>&</b> 🦖"))
        .andExpect(jsonPath("$.entries[0].strokes").doesNotExist())
        .andExpect(jsonPath("$.entries[0].authorGuestId").value(host.id().toString()))
        .andExpect(jsonPath("$.entries[0].authorNickname").value(host.nickname()))
        .andExpect(jsonPath("$.entries[0].fill").value("NONE"))
        .andExpect(jsonPath("$.entries[1].step").value(1))
        .andExpect(jsonPath("$.entries[1].kind").value("DRAWING"))
        .andExpect(jsonPath("$.entries[1].text").doesNotExist())
        .andExpect(jsonPath("$.entries[1].authorNickname").value(other.nickname()))
        .andExpect(jsonPath("$.entries[1].strokes[0].tool").value("ERASER"))
        .andExpect(jsonPath("$.entries[1].strokes[0].points[1][0]").value(2000));

    read(id + "/albums/1", host)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[1].placeholder").value(true))
        .andExpect(jsonPath("$.entries[1].timedOut").value(true))
        .andExpect(jsonPath("$.entries[1].fill").value("TIMEOUT"))
        .andExpect(jsonPath("$.entries[1].strokes.length()").value(0));
    read(id + "/albums/2", host)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ALBUM_NOT_FOUND"));
    read(id + "/albums/-1", host)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ALBUM_NOT_FOUND"));
    // a member who joined the room later may read them too (D45: current members of the room)
    Member late = guest();
    rooms.join(late.id(), room.code());
    read(id + "/albums/0", late).andExpect(status().isOk());
  }

  @Test
  void anEarlyFinishFlagsIncompleteAlbumsAndAnAbortedSessionHasNone() throws Exception {
    Member host = guest();
    Member other = guest();
    RoomDetails room = room("telephone", host, other);
    SessionStart early = start(room, "telephone", host, other);
    recorder.submissionAccepted(early.sessionId(), text(0, 0, host, "zz-a", 4));
    recorder.submissionAccepted(early.sessionId(), text(1, 0, other, "zz-b", 4));
    finish(early, "INSUFFICIENT_PLAYERS");

    read(early.sessionId() + "/albums", host)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.complete").value(false))
        .andExpect(jsonPath("$.albums.length()").value(2))
        .andExpect(jsonPath("$.albums[0].entryCount").value(1))
        .andExpect(jsonPath("$.albums[0].entriesPerChain").value(4))
        .andExpect(jsonPath("$.albums[0].complete").value(false));
    read(early.sessionId() + "/albums/0", host)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.complete").value(false));

    SessionStart aborted = start(room, "telephone", host, other);
    recorder.submissionAccepted(aborted.sessionId(), text(0, 0, host, "zz-gone", 4));
    recorder.sessionAborted(aborted.sessionId(), "NO_COMPLETED_ROUND", Instant.now());
    await().atMost(Duration.ofSeconds(10)).until(() -> sessionStatus(aborted).equals("ABORTED"));

    read(aborted.sessionId() + "/albums", host)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.complete").value(false))
        .andExpect(jsonPath("$.albums.length()").value(0));
    read(aborted.sessionId() + "/albums/0", host)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ALBUM_NOT_FOUND"));
  }

  @Test
  void aSessionOfAnotherGameHasNoAlbums() throws Exception {
    Member host = guest();
    RoomDetails room = room("test-game", host);
    SessionStart start = start(room, "test-game", host);
    finish(start, "COMPLETED");

    read(start.sessionId() + "/albums", host)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
  }

  @Test
  void aSubmissionWaitsOutADatabaseOutageAndIsStoredBeforeTheFinish() {
    Member host = guest();
    Member other = guest();
    RoomDetails room = room("telephone", host, other);
    SessionStart start = start(room, "telephone", host, other);

    jdbc.execute("alter table game_submission rename to game_submission_away");
    try {
      recorder.submissionAccepted(start.sessionId(), text(0, 0, host, "zz-kept", 2));
      recorder.sessionFinished(
          new SessionResult(start.sessionId(), "COMPLETED", Instant.now(), List.of()));
      // the finish waits behind the failing submission: no FINISHED session without its entries
      await().pollDelay(Duration.ofMillis(700)).until(() -> sessionStatus(start).equals("RUNNING"));
    } finally {
      jdbc.execute("alter table game_submission_away rename to game_submission");
    }

    await().atMost(Duration.ofSeconds(15)).until(() -> sessionStatus(start).equals("FINISHED"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from game_submission where session_id = ?",
                Integer.class,
                start.sessionId()))
        .isEqualTo(1);
  }
}
