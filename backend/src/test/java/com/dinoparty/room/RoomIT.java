package com.dinoparty.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.ApiTestSupport;
import com.dinoparty.ApiTestSupport.TestGuest;
import com.dinoparty.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class RoomIT {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  ApiTestSupport api;

  @BeforeEach
  void setUp() {
    api = new ApiTestSupport(mvc);
  }

  private TestGuest guest(String prefix) throws Exception {
    return api.createGuest(ApiTestSupport.unique(prefix));
  }

  // --- authentication / authorization -------------------------------------------------------

  @Test
  void everyRoomEndpointRequiresAuthentication() throws Exception {
    mvc.perform(post("/api/v1/rooms").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/rooms/ABCDEF")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/rooms/ABCDEF/join")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/rooms/ABCDEF/leave")).andExpect(status().isUnauthorized());
    mvc.perform(
            patch("/api/v1/rooms/ABCDEF/settings").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
  }

  // --- create --------------------------------------------------------------------------------

  @Test
  void createRoomMakesCallerTheHostWithGameDefaults() throws Exception {
    TestGuest host = guest("Host");

    api.request(post("/api/v1/rooms"), host, "{\"gameKey\":\"draw-guess\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.matchesPattern("[A-Z2-9]{6}")))
        .andExpect(
            jsonPath("$.inviteUrl")
                .value(org.hamcrest.Matchers.startsWith("http://app.test/join/")))
        .andExpect(jsonPath("$.status").value("WAITING"))
        .andExpect(jsonPath("$.gameKey").value("draw-guess"))
        .andExpect(jsonPath("$.hostGuestId").value(host.id().toString()))
        .andExpect(jsonPath("$.minPlayers").value(2))
        .andExpect(jsonPath("$.maxPlayers").value(8))
        .andExpect(jsonPath("$.settings.drawSeconds").value(80))
        .andExpect(jsonPath("$.settings.hintReveal").value(true))
        .andExpect(jsonPath("$.players.length()").value(1))
        .andExpect(jsonPath("$.players[0].guestId").value(host.id().toString()))
        .andExpect(jsonPath("$.players[0].isHost").value(true))
        .andExpect(jsonPath("$.sessionId").doesNotExist());
  }

  @Test
  void createRoomValidatesGameSettingsAndCapacity() throws Exception {
    TestGuest host = guest("Val");

    api.request(post("/api/v1/rooms"), host, "{\"gameKey\":\"chess\"}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
    api.request(post("/api/v1/rooms"), host, "{}").andExpect(status().isBadRequest());
    api.request(
            post("/api/v1/rooms"),
            host,
            "{\"gameKey\":\"draw-guess\",\"settings\":{\"drawSeconds\":5}}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("settings.drawSeconds"));
    api.request(
            post("/api/v1/rooms"), host, "{\"gameKey\":\"draw-guess\",\"settings\":{\"nope\":1}}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("settings.nope"));
    api.request(post("/api/v1/rooms"), host, "{\"gameKey\":\"telephone\",\"maxPlayers\":3}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("maxPlayers"));
    api.request(post("/api/v1/rooms"), host, "{\"gameKey\":\"draw-guess\",\"maxPlayers\":99999}")
        .andExpect(status().isBadRequest());
  }

  @Test
  void guestCannotCreateASecondRoomWhileInOne() throws Exception {
    TestGuest host = guest("Dup");
    api.createRoom(host, "draw-guess");

    api.request(post("/api/v1/rooms"), host, "{\"gameKey\":\"draw-guess\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ALREADY_IN_ROOM"));
  }

  // --- join / duplicate join / capacity ------------------------------------------------------

  @Test
  void secondGuestJoinsByCodeCaseInsensitively() throws Exception {
    TestGuest host = guest("Host");
    TestGuest friend = guest("Friend");
    String code = api.createRoom(host, "draw-guess").get("code").asString();

    api.join(friend, code.toLowerCase())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.players.length()").value(2))
        .andExpect(jsonPath("$.players[1].guestId").value(friend.id().toString()))
        .andExpect(jsonPath("$.players[1].isHost").value(false));
  }

  @Test
  void joiningTwiceIsIdempotent() throws Exception {
    TestGuest host = guest("Host");
    TestGuest friend = guest("Friend");
    String code = api.createRoom(host, "draw-guess").get("code").asString();

    api.join(friend, code).andExpect(status().isOk());
    api.join(friend, code)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.players.length()").value(2));
    api.join(host, code)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.players.length()").value(2));

    Integer rows =
        jdbc.queryForObject(
            "select count(*) from room_participant p join room r on r.id = p.room_id "
                + "where r.code = ? and p.left_at is null",
            Integer.class,
            code);
    assertThat(rows).isEqualTo(2);
  }

  @Test
  void joiningAnotherRoomWhileInOneIsRejected() throws Exception {
    TestGuest hostA = guest("HostA");
    TestGuest hostB = guest("HostB");
    TestGuest friend = guest("Friend");
    String codeA = api.createRoom(hostA, "draw-guess").get("code").asString();
    String codeB = api.createRoom(hostB, "draw-guess").get("code").asString();
    api.join(friend, codeA).andExpect(status().isOk());

    api.join(friend, codeB)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ALREADY_IN_ROOM"));
  }

  @Test
  void fullRoomRejectsJoinWithRoomFull() throws Exception {
    TestGuest host = guest("Host");
    String code = api.createRoom(host, "telephone", 4).get("code").asString();
    for (int i = 0; i < 3; i++) {
      api.join(guest("P" + i), code).andExpect(status().isOk());
    }

    api.join(guest("Late"), code)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ROOM_FULL"));
  }

  @Test
  void unknownOrMalformedCodesAre404() throws Exception {
    TestGuest guest = guest("Lost");

    for (String code : new String[] {"ZZZZZZ", "abc", "ABCDE0", "ABCDEFG", "ab-cde"}) {
      api.join(guest, code)
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    }
    api.request(get("/api/v1/rooms/ZZZZZZ"), guest, null).andExpect(status().isNotFound());
    api.leave(guest, "ZZZZZZ").andExpect(status().isNotFound());
  }

  @Test
  void roomInProgressOrClosedCannotBeJoined() throws Exception {
    TestGuest host = guest("Host");
    String code = api.createRoom(host, "draw-guess").get("code").asString();

    jdbc.update("update room set status = 'PLAYING' where code = ?", code);
    api.join(guest("Late"), code)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ROOM_IN_PROGRESS"));

    jdbc.update("update room set status = 'CLOSED' where code = ?", code);
    api.join(guest("Later"), code)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ROOM_CLOSED"));
  }

  // --- get -----------------------------------------------------------------------------------

  @Test
  void membersSeeDetailsAndOutsidersOnlyAPreview() throws Exception {
    TestGuest host = guest("Host");
    TestGuest outsider = guest("Outsider");
    String code = api.createRoom(host, "draw-guess").get("code").asString();

    api.request(get("/api/v1/rooms/" + code), host, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.players.length()").value(1))
        .andExpect(jsonPath("$.settings").exists());
    api.request(get("/api/v1/rooms/" + code), outsider, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.playerCount").value(1))
        .andExpect(jsonPath("$.hostNickname").value(host.nickname()))
        .andExpect(jsonPath("$.players").doesNotExist())
        .andExpect(jsonPath("$.settings").doesNotExist())
        .andExpect(jsonPath("$.inviteUrl").doesNotExist());
  }

  // --- settings ------------------------------------------------------------------------------

  @Test
  void hostUpdatesSettingsAndNonHostIsForbidden() throws Exception {
    TestGuest host = guest("Host");
    TestGuest friend = guest("Friend");
    TestGuest outsider = guest("Outsider");
    String code = api.createRoom(host, "draw-guess").get("code").asString();
    api.join(friend, code).andExpect(status().isOk());

    api.patchSettings(
            host, code, "{\"settings\":{\"cycles\":3,\"difficulty\":\"hard\"},\"maxPlayers\":6}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settings.cycles").value(3))
        .andExpect(jsonPath("$.settings.difficulty").value("hard"))
        .andExpect(jsonPath("$.settings.drawSeconds").value(80))
        .andExpect(jsonPath("$.maxPlayers").value(6));

    api.patchSettings(friend, code, "{\"maxPlayers\":3}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_HOST"));
    api.patchSettings(outsider, code, "{\"maxPlayers\":3}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_HOST"));
    api.patchSettings(null, code, "{\"maxPlayers\":3}").andExpect(status().isUnauthorized());
    api.request(get("/api/v1/rooms/" + code), host, null)
        .andExpect(jsonPath("$.maxPlayers").value(6));
  }

  @Test
  void settingsAreValidatedAndCannotDropBelowPlayerCount() throws Exception {
    TestGuest host = guest("Host");
    String code = api.createRoom(host, "draw-guess").get("code").asString();
    api.join(guest("P1"), code).andExpect(status().isOk());
    api.join(guest("P2"), code).andExpect(status().isOk());

    api.patchSettings(host, code, "{\"maxPlayers\":2}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("maxPlayers"));
    api.patchSettings(host, code, "{\"settings\":{\"hintReveal\":\"yes\"}}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("settings.hintReveal"));
    api.patchSettings(host, code, "{\"gameKey\":\"nope\"}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
  }

  @Test
  void changingGameResetsSettingsAndRespectsPlayerBounds() throws Exception {
    TestGuest host = guest("Host");
    String code = api.createRoom(host, "draw-guess").get("code").asString();
    api.join(guest("P1"), code).andExpect(status().isOk());

    // Two players cannot play telephone (minimum 4) once the room is full enough to matter, but
    // switching the game is allowed; the bounds apply to maxPlayers, not the current head count.
    api.patchSettings(host, code, "{\"gameKey\":\"telephone\",\"settings\":{\"drawSeconds\":100}}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gameKey").value("telephone"))
        .andExpect(jsonPath("$.settings.drawSeconds").value(100))
        .andExpect(jsonPath("$.settings.promptSeconds").value(60))
        .andExpect(jsonPath("$.settings.cycles").doesNotExist())
        .andExpect(jsonPath("$.minPlayers").value(4))
        .andExpect(jsonPath("$.maxPlayers").value(8));
  }

  @Test
  void settingsCannotChangeOutsideWaiting() throws Exception {
    TestGuest host = guest("Host");
    String code = api.createRoom(host, "draw-guess").get("code").asString();
    jdbc.update("update room set status = 'PLAYING' where code = ?", code);

    api.patchSettings(host, code, "{\"maxPlayers\":5}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_PHASE"));
  }

  // --- leave / host transfer -----------------------------------------------------------------

  @Test
  void hostLeavingTransfersHostToLongestStandingPlayer() throws Exception {
    TestGuest host = guest("Host");
    TestGuest second = guest("Second");
    TestGuest third = guest("Third");
    String code = api.createRoom(host, "draw-guess").get("code").asString();
    api.join(second, code).andExpect(status().isOk());
    api.join(third, code).andExpect(status().isOk());

    api.leave(host, code).andExpect(status().isNoContent());

    JsonNode room =
        api.parse(
            api.request(get("/api/v1/rooms/" + code), second, null).andExpect(status().isOk()));
    assertThat(room.get("hostGuestId").asString()).isEqualTo(second.id().toString());
    assertThat(room.get("players")).hasSize(2);
    assertThat(room.get("players").get(0).get("isHost").asBoolean()).isTrue();
    // The old host is now an outsider and can only see the preview.
    api.request(get("/api/v1/rooms/" + code), host, null)
        .andExpect(jsonPath("$.players").doesNotExist());
    api.patchSettings(host, code, "{\"maxPlayers\":5}").andExpect(status().isForbidden());
    api.patchSettings(second, code, "{\"maxPlayers\":5}").andExpect(status().isOk());
  }

  @Test
  void nonHostLeavingKeepsTheHostAndFreesTheSeat() throws Exception {
    TestGuest host = guest("Host");
    TestGuest friend = guest("Friend");
    String code = api.createRoom(host, "telephone", 4).get("code").asString();
    api.join(friend, code).andExpect(status().isOk());

    api.leave(friend, code).andExpect(status().isNoContent());

    api.request(get("/api/v1/rooms/" + code), host, null)
        .andExpect(jsonPath("$.hostGuestId").value(host.id().toString()))
        .andExpect(jsonPath("$.players.length()").value(1));
    // Leaving again (or by a stranger) is a harmless no-op.
    api.leave(friend, code).andExpect(status().isNoContent());
    api.leave(guest("Stranger"), code).andExpect(status().isNoContent());
  }

  @Test
  void leavingFreesTheGuestToJoinOrCreateElsewhereAndRejoinWorks() throws Exception {
    TestGuest hostA = guest("HostA");
    TestGuest hostB = guest("HostB");
    TestGuest friend = guest("Friend");
    String codeA = api.createRoom(hostA, "draw-guess").get("code").asString();
    String codeB = api.createRoom(hostB, "draw-guess").get("code").asString();

    api.join(friend, codeA).andExpect(status().isOk());
    api.leave(friend, codeA).andExpect(status().isNoContent());
    api.join(friend, codeB).andExpect(status().isOk());
    api.leave(friend, codeB).andExpect(status().isNoContent());
    api.join(friend, codeA)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.players.length()").value(2));
  }

  @Test
  void lastPlayerLeavingClosesTheRoom() throws Exception {
    TestGuest host = guest("Host");
    TestGuest friend = guest("Friend");
    String code = api.createRoom(host, "draw-guess").get("code").asString();

    api.leave(host, code).andExpect(status().isNoContent());

    api.join(friend, code)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ROOM_CLOSED"));
    api.request(get("/api/v1/rooms/" + code), friend, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"));
    // The former host may start a fresh room.
    api.createRoom(host, "draw-guess");
  }
}
