package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Command validation, duplicate detection and safe errors. */
@RealtimeIntegrationTest
class RealtimeCommandIT extends RealtimeFixture {

  @Autowired JdbcTemplate jdbc;
  private Guest host;
  private String code;
  private StompTestClient ws;

  @AfterEach
  void tearDown() {
    closeClients();
  }

  private void joinRoom() {
    host = newGuest();
    code = newRoom(host).code();
    ws = connectAndSubscribe(host, code);
  }

  private StompTestClient.Message errorFor(String json) {
    int before = ws.ofType("GAME_ERROR").size();
    ws.command(code, json);
    return ws.awaitMessage(
        m -> m.type().equals("GAME_ERROR") && ws.ofType("GAME_ERROR").indexOf(m) >= before);
  }

  private boolean hostIsReady() {
    return roomService.getForMember(host.id(), code).members().getFirst().ready();
  }

  @Test
  void invalidEnvelopesAreRejectedAsValidationErrorsWithoutEchoingInput() {
    joinRoom();
    String id = actionId();
    List<String> invalid =
        List.of(
            "not json at all <script>alert(1)</script>",
            "[]",
            "\"string\"",
            "{}",
            "{\"actionId\":\"" + id + "\"}",
            "{\"type\":\"set_ready\",\"actionId\":\"" + id + "\"}",
            "{\"type\":\"<script>\",\"actionId\":\"" + id + "\"}",
            "{\"type\":\"" + "A".repeat(41) + "\",\"actionId\":\"" + id + "\"}",
            "{\"type\":7,\"actionId\":\"" + id + "\"}",
            "{\"type\":\"SET_READY\"}",
            "{\"type\":\"SET_READY\",\"actionId\":\"nope\"}",
            "{\"type\":\"SET_READY\",\"actionId\":\"" + "a".repeat(36) + "\"}",
            "{\"type\":\"SET_READY\",\"actionId\":12}",
            "{\"type\":\"SET_READY\",\"actionId\":\"" + actionId() + "\",\"phaseId\":\"bad\"}",
            "{\"type\":\"SET_READY\",\"actionId\":\"" + actionId() + "\",\"payload\":[]}",
            "{\"type\":\"SET_READY\",\"actionId\":\"" + actionId() + "\",\"payload\":{}}",
            "{\"type\":\"SET_READY\",\"actionId\":\""
                + actionId()
                + "\",\"payload\":{\"ready\":\"true\"}}",
            "{\"type\":\"SET_READY\",\"actionId\":\""
                + actionId()
                + "\",\"payload\":{\"ready\":1}}",
            "{\"type\":\"SET_READY\",\"actionId\":\""
                + actionId()
                + "\",\"payload\":{\"ready\":null}}");

    for (String json : invalid) {
      StompTestClient.Message error = errorFor(json);
      assertThat(error.payload().get("code").asString()).as(json).isEqualTo("VALIDATION_FAILED");
      assertThat(error.payload().get("retryable").asBoolean()).isFalse();
      assertThat(error.envelope().get("stream").asString()).isEqualTo("PRIVATE");
      assertThat(error.envelope().toString()).doesNotContain("script").doesNotContain("alert");
    }
    assertThat(hostIsReady()).isFalse();
  }

  @Test
  void aRejectedCommandEchoesItsActionId() {
    joinRoom();
    String id = actionId();

    StompTestClient.Message error =
        errorFor("{\"type\":\"SET_READY\",\"actionId\":\"" + id + "\",\"payload\":{}}");

    assertThat(error.envelope().get("actionId").asString()).isEqualTo(id);
    assertThat(error.payload().get("actionId").asString()).isEqualTo(id);
  }

  @Test
  void gameCommandsWithoutAModuleAndUnknownTypesAreInvalidActions() {
    joinRoom();
    for (String type :
        List.of(
            "CHOOSE_WORD",
            "DRAW_STROKE",
            "DRAW_UNDO",
            "DRAW_CLEAR",
            "SUBMIT_GUESS",
            "TELEPHONE_SUBMIT_TEXT",
            "TELEPHONE_SUBMIT_DRAWING",
            "REVEAL_NEXT",
            "REVEAL_PREV",
            "MAKE_ME_ADMIN")) {
      StompTestClient.Message error =
          errorFor(
              "{\"type\":\"%s\",\"actionId\":\"%s\",\"payload\":{}}".formatted(type, actionId()));
      assertThat(error.payload().get("code").asString()).as(type).isEqualTo("INVALID_ACTION");
    }
  }

  @Test
  void clientSuppliedIdentityRoleAndStateAreIgnored() {
    joinRoom();
    Guest alice = newGuest();
    roomService.join(alice.id(), code);
    // Extra fields claiming to be someone else or to change server state must have no effect.
    ws.command(
        code,
        ("{\"type\":\"SET_READY\",\"actionId\":\"%s\",\"guestId\":\"%s\",\"hostGuestId\":\"%s\","
                + "\"payload\":{\"ready\":true,\"guestId\":\"%s\",\"isHost\":false}}")
            .formatted(actionId(), alice.id(), alice.id(), alice.id()));
    ws.awaitType("ACTION_ACK");

    var members = roomService.getForMember(host.id(), code).members();
    assertThat(members.get(0).ready()).isTrue(); // the sender, not alice
    assertThat(members.get(1).ready()).isFalse();
    assertThat(roomService.getForMember(host.id(), code).hostGuestId()).isEqualTo(host.id());
  }

  @Test
  void aRepeatedActionIdIsNotAppliedTwice() {
    joinRoom();
    String id = actionId();
    ws.command(code, "SET_READY", id, "{\"ready\":true}");
    ws.awaitType("ACTION_ACK");
    // Same id, opposite value: a retry must not undo or redo anything.
    ws.command(code, "SET_READY", id, "{\"ready\":false}");

    StompTestClient.Message error = ws.awaitType("GAME_ERROR");
    assertThat(error.payload().get("code").asString()).isEqualTo("DUPLICATE_ACTION");
    assertThat(error.payload().get("retryable").asBoolean()).isFalse();
    assertThat(error.envelope().get("actionId").asString()).isEqualTo(id);
    assertThat(hostIsReady()).isTrue();
    assertThat(ws.ofType("READY_UPDATED")).hasSize(1);
    assertThat(ws.ofType("ACTION_ACK")).hasSize(1);
  }

  @Test
  void sameActionIdFromDifferentGuestsIsNotADuplicate() {
    joinRoom();
    Guest alice = newGuest();
    roomService.join(alice.id(), code);
    StompTestClient aliceWs = connectAndSubscribe(alice, code);
    String id = actionId();

    ws.command(code, "SET_READY", id, "{\"ready\":true}");
    aliceWs.command(code, "SET_READY", id, "{\"ready\":true}");

    ws.awaitType("ACTION_ACK");
    aliceWs.awaitType("ACTION_ACK");
    assertThat(aliceWs.ofType("GAME_ERROR")).isEmpty();
  }

  @Test
  void pingNeedsNoActionIdAndAnswersWithServerTime() {
    joinRoom();
    ws.command(code, "{\"type\":\"PING\"}");

    StompTestClient.Message pong = ws.awaitMessage(m -> m.type().equals("PONG") && m.seq() > 1);
    assertThat(pong.payload().get("serverNow").asString()).endsWith("Z");
  }

  @Test
  void aMessageOverTheSizeLimitClosesTheConnection() {
    joinRoom();
    String huge =
        "{\"type\":\"SET_READY\",\"actionId\":\"%s\",\"payload\":{\"pad\":\"%s\"}}"
            .formatted(actionId(), "x".repeat(70_000));

    ws.command(code, huge);

    assertThat(ws.awaitClose()).isEqualTo(1009);
    assertThat(hostIsReady()).isFalse();
  }

  @Test
  void aSpoofedPrincipalHeaderIsIgnored() {
    joinRoom();
    Guest alice = newGuest();
    roomService.join(alice.id(), code);
    // STOMP lets clients set arbitrary headers; none may influence who the sender is.
    ws.sendFrame(
        "SEND",
        Map.of(
            "destination",
            "/app/rooms/" + code + "/command",
            "content-type",
            "application/json",
            "user-name",
            alice.id().toString(),
            "simpUser",
            alice.id().toString()),
        "{\"type\":\"SET_READY\",\"actionId\":\"%s\",\"payload\":{\"ready\":true}}"
            .formatted(actionId()));
    ws.awaitType("ACTION_ACK");

    assertThat(roomService.getForMember(host.id(), code).members().get(0).ready()).isTrue();
    assertThat(roomService.getForMember(host.id(), code).members().get(1).ready()).isFalse();
  }
}
