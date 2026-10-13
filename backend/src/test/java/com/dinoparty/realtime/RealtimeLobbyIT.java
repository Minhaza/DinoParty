package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.room.RoomDetails;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Room events, sequencing, ready state and snapshots through a real STOMP connection. */
@RealtimeIntegrationTest
class RealtimeLobbyIT extends RealtimeFixture {

  @Autowired MockMvc mvc;
  @Autowired SnapshotService snapshots;
  private final JsonMapper json = JsonMapper.builder().build();

  @AfterEach
  void tearDown() {
    closeClients();
  }

  @Test
  void connectedFrameAdvertisesTheContractHeartbeat() {
    StompTestClient client = connect(newGuest());

    StompTestClient.Frame connected = client.awaitFrame(f -> f.command().equals("CONNECTED"));
    assertThat(connected.header("heart-beat")).isEqualTo("10000,10000");
    assertThat(connected.header("version")).isEqualTo("1.2");
  }

  @Test
  void roomEventsCarryEnvelopeFieldsAndGaplessSequence() {
    Guest host = newGuest();
    Guest alice = newGuest();
    RoomDetails room = newRoom(host);
    String code = room.code();
    StompTestClient hostWs = connectAndSubscribe(host, code);

    roomService.join(alice.id(), code);
    StompTestClient aliceWs = connectAndSubscribe(alice, code);
    aliceWs.command(code, "SET_READY", actionId(), "{\"ready\":true}");
    aliceWs.awaitType("ACTION_ACK");
    roomService.leave(alice.id(), code);
    roomService.leave(host.id(), code);

    // The last member to leave still hears their own PLAYER_LEFT, but nobody is left to hear
    // ROOM_CLOSED, which is why it is not part of this stream.
    await().atMost(Duration.ofSeconds(5)).until(() -> hostWs.ofType("PLAYER_LEFT").size() == 2);
    List<StompTestClient.Message> events = hostWs.onStream("ROOM", code);
    assertThat(events)
        .extracting(StompTestClient.Message::type)
        .containsExactly("PLAYER_JOINED", "READY_UPDATED", "PLAYER_LEFT", "PLAYER_LEFT");
    assertThat(events).extracting(StompTestClient.Message::seq).containsExactly(1L, 2L, 3L, 4L);

    JsonNode joined = events.getFirst().envelope();
    assertThat(joined.get("roomId").asString()).isEqualTo(room.id().toString());
    assertThat(joined.get("roomCode").asString()).isEqualTo(code);
    assertThat(joined.get("stream").asString()).isEqualTo("ROOM");
    assertThat(joined.get("actionId").isNull()).isTrue();
    assertThat(joined.get("ts").asString())
        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    JsonNode player = joined.get("payload").get("player");
    assertThat(player.get("guestId").asString()).isEqualTo(alice.id().toString());
    assertThat(player.get("ready").asBoolean()).isFalse();
    assertThat(player.get("connection").asString()).isEqualTo("CONNECTED");
    assertThat(player.get("isHost").asBoolean()).isFalse();

    JsonNode ready = events.get(1).payload();
    assertThat(ready.get("guestId").asString()).isEqualTo(alice.id().toString());
    assertThat(ready.get("ready").asBoolean()).isTrue();
    assertThat(events.get(2).payload().get("reason").asString()).isEqualTo("LEFT");
    assertThat(events.get(3).payload().get("guestId").asString()).isEqualTo(host.id().toString());
  }

  @Test
  void idempotentRejoinAndNoOpReadyEmitNoEvent() {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code = newRoom(host).code();
    StompTestClient hostWs = connectAndSubscribe(host, code);

    roomService.join(alice.id(), code);
    roomService.join(alice.id(), code); // already a member: idempotent, no second PLAYER_JOINED
    roomService.setReady(alice.id(), code, false); // already false: nothing changed
    roomService.setReady(alice.id(), code, true);
    roomService.setReady(alice.id(), code, true);

    hostWs.awaitType("READY_UPDATED");
    assertThat(hostWs.ofType("PLAYER_JOINED")).hasSize(1);
    assertThat(hostWs.ofType("READY_UPDATED")).hasSize(1);
  }

  @Test
  void commandsAreAcknowledgedAndEchoTheActionId() {
    Guest host = newGuest();
    String code = newRoom(host).code();
    StompTestClient ws = connectAndSubscribe(host, code);
    String id = actionId();

    ws.command(code, "SET_READY", id, "{\"ready\":true}");

    StompTestClient.Message ack = ws.awaitType("ACTION_ACK");
    assertThat(ack.envelope().get("stream").asString()).isEqualTo("PRIVATE");
    assertThat(ack.envelope().get("actionId").asString()).isEqualTo(id);
    assertThat(ack.payload().get("actionId").asString()).isEqualTo(id);
    assertThat(ack.payload().get("accepted").asBoolean()).isTrue();
    assertThat(ack.destination()).isEqualTo("/user/queue/rooms/" + code);
    assertThat(roomService.getForMember(host.id(), code).members().getFirst().ready()).isTrue();
  }

  @Test
  void commandsFromOneConnectionAreProcessedInOrder() {
    Guest host = newGuest();
    String code = newRoom(host).code();
    StompTestClient ws = connectAndSubscribe(host, code);

    for (int i = 0; i < 20; i++) {
      ws.command(code, "SET_READY", actionId(), "{\"ready\":%s}".formatted(i % 2 == 0));
    }

    await().atMost(Duration.ofSeconds(5)).until(() -> ws.ofType("ACTION_ACK").size() == 20);
    List<Boolean> values =
        ws.ofType("READY_UPDATED").stream().map(m -> m.payload().get("ready").asBoolean()).toList();
    assertThat(values).hasSize(20);
    for (int i = 0; i < values.size(); i++) {
      assertThat(values.get(i)).isEqualTo(i % 2 == 0);
    }
    assertThat(roomService.getForMember(host.id(), code).members().getFirst().ready()).isFalse();
  }

  @Test
  void settingsChangesAreBroadcastWithBounds() throws Exception {
    Guest host = newGuest();
    String code = newRoom(host).code();
    StompTestClient ws = connectAndSubscribe(host, code);

    roomService.updateSettings(
        host.id(), code, new com.dinoparty.room.UpdateRoomSettingsCommand("telephone", null, 6));

    JsonNode payload = ws.awaitType("ROOM_SETTINGS_UPDATED").payload();
    assertThat(payload.get("gameKey").asString()).isEqualTo("telephone");
    assertThat(payload.get("minPlayers").asInt()).isEqualTo(4);
    assertThat(payload.get("maxPlayers").asInt()).isEqualTo(6);
    assertThat(payload.get("settings").get("promptSeconds").asInt()).isEqualTo(60);
  }

  @Test
  void hostLeavingBroadcastsLeftThenHostChanged() {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code = newRoom(host).code();
    roomService.join(alice.id(), code);
    StompTestClient aliceWs = connectAndSubscribe(alice, code);

    roomService.leave(host.id(), code);

    aliceWs.awaitType("HOST_CHANGED");
    assertThat(aliceWs.ofType("PLAYER_LEFT")).hasSize(1);
    assertThat(aliceWs.ofType("HOST_CHANGED").getFirst().payload().get("hostGuestId").asString())
        .isEqualTo(alice.id().toString());
    assertThat(aliceWs.ofType("HOST_CHANGED").getFirst().seq())
        .isGreaterThan(aliceWs.ofType("PLAYER_LEFT").getFirst().seq());
  }

  @Test
  void requestSnapshotReflectsStateAndSequenceNumbers() throws Exception {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code = newRoom(host).code();
    StompTestClient hostWs = connectAndSubscribe(host, code);
    roomService.join(alice.id(), code);
    roomService.setReady(alice.id(), code, true);
    roomService.setReady(host.id(), code, true);
    await().atMost(Duration.ofSeconds(5)).until(() -> hostWs.ofType("READY_UPDATED").size() == 2);

    String id = actionId();
    hostWs.command(code, "REQUEST_SNAPSHOT", id, "{}");

    StompTestClient.Message snapshot = hostWs.awaitType("ROOM_SNAPSHOT");
    JsonNode payload = snapshot.payload();
    assertThat(snapshot.envelope().get("actionId").asString()).isEqualTo(id);
    // seq.room is the last public event reflected; seq.private is this very envelope.
    assertThat(payload.get("seq").get("room").asLong())
        .isEqualTo(hostWs.onStream("ROOM", code).getLast().seq());
    assertThat(payload.get("seq").get("private").asLong()).isEqualTo(snapshot.seq());
    assertThat(payload.get("game").isNull()).isTrue();
    assertThat(payload.get("serverNow").asString()).endsWith("Z");
    JsonNode room = payload.get("room");
    assertThat(room.get("code").asString()).isEqualTo(code);
    assertThat(room.get("inviteUrl").asString()).isEqualTo("http://app.test/join/" + code);
    assertThat(room.get("players")).hasSize(2);
    for (JsonNode p : room.get("players")) {
      assertThat(p.get("ready").asBoolean()).isTrue();
      assertThat(p.get("connection").asString()).isEqualTo("CONNECTED");
    }
    assertThat(room.get("players").get(0).get("isHost").asBoolean()).isTrue();
  }

  @Test
  void snapshotAppliedToEventsThatFollowIsGapFree() {
    Guest host = newGuest();
    Guest alice = newGuest();
    Guest bob = newGuest();
    String code = newRoom(host).code();
    // Host only listens on the private stream, so it misses the public events...
    StompTestClient ws = connect(host);
    ws.subscribe("prv", "/user/queue/rooms/" + code);
    roomService.join(alice.id(), code);
    roomService.join(bob.id(), code);

    ws.command(code, "REQUEST_SNAPSHOT", actionId(), "{}");
    long reflected = ws.awaitType("ROOM_SNAPSHOT").payload().get("seq").get("room").asLong();
    assertThat(reflected).isEqualTo(2);

    // ...then catches up: the next public event is exactly snapshot.seq + 1 (gap-resync path).
    ws.subscribe("pub", "/topic/rooms/" + code);
    ws.command(code, "PING", null, "{}");
    ws.awaitType("PONG");
    roomService.setReady(bob.id(), code, true);
    StompTestClient.Message next = ws.awaitType("READY_UPDATED");
    assertThat(next.seq()).isEqualTo(reflected + 1);
  }

  @Test
  void restSnapshotMatchesTheStompSnapshot() throws Exception {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code = newRoom(host).code();
    StompTestClient ws = connectAndSubscribe(host, code);
    roomService.join(alice.id(), code);
    roomService.setReady(alice.id(), code, true);
    await().atMost(Duration.ofSeconds(5)).until(() -> ws.ofType("READY_UPDATED").size() == 1);

    ws.command(code, "REQUEST_SNAPSHOT", actionId(), "{}");
    JsonNode viaStomp = ws.awaitType("ROOM_SNAPSHOT").payload();
    JsonNode viaRest =
        json.readTree(
            mvc.perform(
                    get("/api/v1/rooms/" + code + "/snapshot")
                        .header("Authorization", "Bearer " + host.token()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(viaRest.get("room")).isEqualTo(viaStomp.get("room"));
    assertThat(viaRest.get("seq").get("room")).isEqualTo(viaStomp.get("seq").get("room"));
    // REST does not consume a private sequence number, so it reports the WS snapshot's own.
    assertThat(viaRest.get("seq").get("private")).isEqualTo(viaStomp.get("seq").get("private"));
  }

  @Test
  void restReadyEndpointUpdatesStateAndNotifiesTheRoom() throws Exception {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code = newRoom(host).code();
    roomService.join(alice.id(), code);
    StompTestClient hostWs = connectAndSubscribe(host, code);

    mvc.perform(
            post("/api/v1/rooms/" + code + "/ready")
                .header("Authorization", "Bearer " + alice.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ready\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.players[1].ready").value(true))
        .andExpect(jsonPath("$.players[1].connection").value("CONNECTED"));

    JsonNode event = hostWs.awaitType("READY_UPDATED").payload();
    assertThat(event.get("guestId").asString()).isEqualTo(alice.id().toString());
    assertThat(event.get("ready").asBoolean()).isTrue();
  }

  @Test
  void restReadyAndSnapshotAreAuthorized() throws Exception {
    Guest host = newGuest();
    Guest outsider = newGuest();
    String code = newRoom(host).code();

    mvc.perform(get("/api/v1/rooms/" + code + "/snapshot")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/rooms/" + code + "/ready")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ready\":true}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/v1/rooms/" + code + "/snapshot")
                .header("Authorization", "Bearer " + outsider.token()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_IN_ROOM"));
    mvc.perform(
            post("/api/v1/rooms/" + code + "/ready")
                .header("Authorization", "Bearer " + outsider.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ready\":true}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_IN_ROOM"));
    mvc.perform(
            get("/api/v1/rooms/ZZZZZ9/snapshot").header("Authorization", "Bearer " + host.token()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    mvc.perform(
            post("/api/v1/rooms/" + code + "/ready")
                .header("Authorization", "Bearer " + host.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(roomService.getForMember(host.id(), code).members().getFirst().ready()).isFalse();
  }

  @Test
  void eventsOfOtherRoomsAndOtherMembersPrivateStreamsAreNotDelivered() {
    Guest host = newGuest();
    Guest alice = newGuest();
    Guest stranger = newGuest();
    String code = newRoom(host).code();
    String otherCode = newRoom(stranger).code();
    roomService.join(alice.id(), code);
    StompTestClient hostWs = connectAndSubscribe(host, code);
    StompTestClient aliceWs = connectAndSubscribe(alice, code);
    StompTestClient strangerWs = connectAndSubscribe(stranger, otherCode);

    aliceWs.command(code, "REQUEST_SNAPSHOT", actionId(), "{}");
    aliceWs.awaitType("ROOM_SNAPSHOT");
    roomService.setReady(alice.id(), code, true);
    hostWs.awaitType("READY_UPDATED");
    // Make sure everything the server would deliver has been delivered.
    strangerWs.command(otherCode, "PING", null, "{}");
    strangerWs.awaitMessage(m -> m.type().equals("PONG") && m.seq() == 2);

    assertThat(hostWs.ofType("ROOM_SNAPSHOT")).isEmpty(); // alice's private snapshot
    assertThat(strangerWs.messages())
        .extracting(m -> m.envelope().get("roomCode").asString())
        .containsOnly(otherCode);
    assertThat(strangerWs.ofType("READY_UPDATED")).isEmpty();
  }

  @Test
  void aMemberWhoLeftStopsReceivingTheRoomsPublicStream() {
    Guest host = newGuest();
    Guest alice = newGuest();
    Guest carol = newGuest();
    String code = newRoom(host).code();
    roomService.join(alice.id(), code);
    StompTestClient aliceWs = connectAndSubscribe(alice, code);
    StompTestClient hostWs = connectAndSubscribe(host, code);

    roomService.leave(alice.id(), code);
    aliceWs.awaitType("PLAYER_LEFT"); // she still hears her own departure
    roomService.join(carol.id(), code);
    hostWs.awaitType("PLAYER_JOINED");
    roomService.setReady(carol.id(), code, true);
    hostWs.awaitType("READY_UPDATED");

    assertThat(aliceWs.ofType("PLAYER_JOINED")).isEmpty();
    assertThat(aliceWs.ofType("READY_UPDATED")).isEmpty();
    // Her socket is still open but she gets no snapshot of a room she is not in.
    aliceWs.command(code, "REQUEST_SNAPSHOT", actionId(), "{}");
    aliceWs.awaitMessage(
        m -> m.type().equals("ERROR") && m.payload().get("code").asString().equals("NOT_IN_ROOM"));
    assertThat(aliceWs.ofType("ROOM_SNAPSHOT")).isEmpty();
  }

  @Test
  void snapshotsAreConsistentWithTheStateAfterEveryEvent() {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code = newRoom(host).code();
    StompTestClient ws = connectAndSubscribe(host, code);

    roomService.join(alice.id(), code);
    roomService.setReady(alice.id(), code, true);
    SnapshotDto after = snapshots.snapshot(host.id(), code);

    await().atMost(Duration.ofSeconds(5)).until(() -> ws.onStream("ROOM", code).size() == 2);
    assertThat(after.seq().room()).isEqualTo(ws.onStream("ROOM", code).getLast().seq());
    assertThat(after.room().players()).extracting(PlayerDto::ready).containsExactly(false, true);
  }
}
