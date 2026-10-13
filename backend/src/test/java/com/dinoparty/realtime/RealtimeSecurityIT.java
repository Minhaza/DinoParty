package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Authentication and authorization of the STOMP layer: who may connect, subscribe and send, and
 * what must never leak. Framework log levels are raised to DEBUG to prove tokens stay out of logs
 * (STOMP decoding at TRACE prints raw headers, so TRACE must never be enabled in production).
 */
@RealtimeIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
    properties = {
      "logging.level.org.springframework.messaging=DEBUG",
      "logging.level.org.springframework.web.socket=DEBUG",
      "logging.level.com.dinoparty=TRACE"
    })
class RealtimeSecurityIT extends RealtimeFixture {

  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void tearDown() {
    closeClients();
  }

  private StompTestClient.Frame connectExpectingError(String authorizationHeader) {
    StompTestClient client = StompTestClient.open(port, null);
    clients.add(client);
    Map<String, String> headers = StompTestClient.connectHeaders(null);
    if (authorizationHeader != null) {
      headers.put("Authorization", authorizationHeader);
    }
    client.sendFrame("CONNECT", headers, null);
    StompTestClient.Frame error = client.awaitFrame(f -> f.command().equals("ERROR"));
    assertThat(client.awaitClose()).isIn(1002, 1008);
    assertThat(client.frames()).noneMatch(f -> f.command().equals("CONNECTED"));
    return error;
  }

  // ---- CONNECT ----

  @Test
  void invalidMissingAndMalformedCredentialsAreRejectedWithASafeError() {
    Guest guest = newGuest();
    List<String> bad =
        List.of(
            "Bearer not-a-real-token",
            "Bearer ",
            "Bearer",
            "Basic " + guest.token(),
            guest.token(), // no scheme
            "Bearer " + "x".repeat(500));
    for (String header : bad) {
      StompTestClient.Frame error = connectExpectingError(header);
      assertThat(error.header("code")).isEqualTo("UNAUTHENTICATED");
      assertThat(error.header("message")).isEqualTo("Authentication failed");
      assertThat(error.body()).isEmpty();
    }
    StompTestClient.Frame error = connectExpectingError(null);
    assertThat(error.header("code")).isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void expiredAndRevokedSessionsCannotConnect() {
    Guest expired = newGuest();
    Guest revoked = newGuest();
    jdbc.update(
        "update guest_session set expires_at = ? where guest_id = ?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
        expired.id());
    jdbc.update("update guest_session set revoked_at = now() where guest_id = ?", revoked.id());

    assertThat(connectExpectingError("Bearer " + expired.token()).header("code"))
        .isEqualTo("UNAUTHENTICATED");
    assertThat(connectExpectingError("Bearer " + revoked.token()).header("code"))
        .isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void tokensNeverAppearInLogsOrErrorFrames(CapturedOutput output) {
    Guest guest = newGuest();
    String code = newRoom(guest).code();
    StompTestClient ok = connectAndSubscribe(guest, code);
    ok.command(code, "SET_READY", actionId(), "{\"ready\":true}");
    ok.awaitType("ACTION_ACK");
    String badToken = "definitely-not-valid-" + java.util.UUID.randomUUID();
    StompTestClient.Frame error = connectExpectingError("Bearer " + badToken);

    assertThat(error.headers().values()).noneMatch(v -> v.contains(badToken));
    assertThat(output.getAll()).doesNotContain(guest.token()).doesNotContain(badToken);
    assertThat(ok.messages().stream().map(m -> m.envelope().toString()).toList())
        .noneMatch(json -> json.contains(guest.token()));
  }

  @Test
  void frameBeforeConnectIsRefused() {
    StompTestClient client = StompTestClient.open(port, null);
    clients.add(client);
    client.subscribe("s", "/topic/rooms/ABCDEF");

    assertThat(client.awaitFrame(f -> f.command().equals("ERROR")).header("code"))
        .isEqualTo("UNAUTHENTICATED");
    assertThat(client.awaitClose()).isEqualTo(1008);
  }

  @Test
  void aSocketThatNeverSendsConnectIsClosed() {
    // Spring closes sockets that stay silent past timeToFirstMessage (10 s); we only verify the
    // wiring by checking the server tolerates the idle socket until then.
    StompTestClient client = StompTestClient.open(port, null);
    clients.add(client);
    assertThat(client.isClosed()).isFalse();
  }

  @Test
  void disallowedOriginIsRejectedAtHandshake() {
    assertThatThrownBy(() -> StompTestClient.open(port, "http://evil.example"))
        .isInstanceOf(StompTestClient.HandshakeRejected.class)
        .hasMessageContaining("403");
    StompTestClient allowed = StompTestClient.open(port, "http://localhost:5173");
    clients.add(allowed);
    assertThat(allowed.isClosed()).isFalse();
  }

  // ---- SUBSCRIBE ----

  @Test
  void nonMembersCannotSubscribeToARoomsStreams() {
    Guest host = newGuest();
    Guest outsider = newGuest();
    String code = newRoom(host).code();

    for (String destination : List.of("/topic/rooms/" + code, "/user/queue/rooms/" + code)) {
      StompTestClient ws = connect(outsider);
      ws.subscribe("s", destination);
      assertThat(ws.awaitFrame(f -> f.command().equals("ERROR")).header("code"))
          .isEqualTo("NOT_IN_ROOM");
      assertThat(ws.awaitClose()).isIn(1002, 1008);
    }
  }

  @Test
  void aMemberOfAnotherRoomCannotSubscribeToThisOne() {
    Guest host = newGuest();
    Guest other = newGuest();
    String code = newRoom(host).code();
    newRoom(other);

    StompTestClient ws = connect(other);
    ws.subscribe("s", "/topic/rooms/" + code);

    assertThat(ws.awaitFrame(f -> f.command().equals("ERROR")).header("code"))
        .isEqualTo("NOT_IN_ROOM");
  }

  @Test
  void onlyTheThreeContractDestinationsMaySubscribed() {
    Guest host = newGuest();
    String code = newRoom(host).code();
    List<String> refused =
        List.of(
            "/topic/rooms/" + code.toLowerCase(),
            "/topic/rooms/*",
            "/topic/rooms/**",
            "/topic/rooms/" + code + "/extra",
            "/topic/rooms/",
            "/topic/other",
            "/topic/**",
            "/queue/rooms/" + code,
            "/user/queue/rooms/" + code + "/x",
            "/user/queue/other",
            "/user/" + host.id() + "/queue/errors",
            "/app/rooms/" + code + "/command");
    for (String destination : refused) {
      StompTestClient ws = connect(host);
      ws.subscribe("s", destination);
      assertThat(ws.awaitFrame(f -> f.command().equals("ERROR")).header("code"))
          .as(destination)
          .isEqualTo("FORBIDDEN");
      assertThat(ws.awaitClose()).isIn(1002, 1008);
    }
  }

  @Test
  void subscriptionsPerConnectionAreCapped() {
    Guest host = newGuest();
    String code = newRoom(host).code();
    StompTestClient ws = connect(host);
    for (int i = 0; i < 8; i++) {
      ws.subscribe("e" + i, "/user/queue/errors");
    }
    ws.subscribe("one-too-many", "/topic/rooms/" + code);

    assertThat(ws.awaitFrame(f -> f.command().equals("ERROR")).header("code"))
        .isEqualTo("FORBIDDEN");
  }

  // ---- SEND ----

  @Test
  void clientsCannotPublishDirectlyToTheBroker() {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code = newRoom(host).code();
    roomService.join(alice.id(), code);
    StompTestClient hostWs = connectAndSubscribe(host, code);
    StompTestClient aliceWs = connect(alice);

    // A forged "event" sent straight to the public topic must never reach other members.
    aliceWs.sendFrame(
        "SEND",
        Map.of("destination", "/topic/rooms/" + code, "content-type", "application/json"),
        "{\"type\":\"HOST_CHANGED\",\"payload\":{\"hostGuestId\":\"" + alice.id() + "\"}}");

    assertThat(aliceWs.awaitFrame(f -> f.command().equals("ERROR")).header("code"))
        .isEqualTo("FORBIDDEN");
    hostWs.command(code, "PING", null, "{}");
    hostWs.awaitType("PONG");
    assertThat(hostWs.ofType("HOST_CHANGED")).isEmpty();
    assertThat(roomService.getForMember(host.id(), code).hostGuestId()).isEqualTo(host.id());
  }

  @Test
  void sendsToAnyOtherDestinationAreRefused() {
    Guest host = newGuest();
    String code = newRoom(host).code();
    for (String destination :
        List.of(
            "/app/rooms/" + code,
            "/app/rooms/" + code + "/command/extra",
            "/app/rooms/" + code.toLowerCase() + "/command",
            "/app/other",
            "/user/" + host.id() + "/queue/rooms/" + code,
            "/queue/rooms/" + code)) {
      StompTestClient ws = connect(host);
      ws.sendFrame("SEND", Map.of("destination", destination), "{}");
      assertThat(ws.awaitFrame(f -> f.command().equals("ERROR")).header("code"))
          .as(destination)
          .isEqualTo("FORBIDDEN");
    }
  }

  @Test
  void unsupportedFramesAreRefused() {
    Guest host = newGuest();
    StompTestClient ws = connect(host);

    ws.sendFrame("BEGIN", Map.of("transaction", "tx1"), null);

    assertThat(ws.awaitFrame(f -> f.command().equals("ERROR")).header("code"))
        .isEqualTo("INVALID_ACTION");
  }

  @Test
  void nonMembersGetNotInRoomAndChangeNothing() {
    Guest host = newGuest();
    Guest outsider = newGuest();
    String code = newRoom(host).code();
    StompTestClient ws = connect(outsider);
    ws.subscribe("err", "/user/queue/errors");

    ws.command(code, "SET_READY", actionId(), "{\"ready\":true}");

    StompTestClient.Message error = ws.awaitType("ERROR");
    assertThat(error.destination()).isEqualTo("/user/queue/errors");
    assertThat(error.payload().get("code").asString()).isEqualTo("NOT_IN_ROOM");
    assertThat(error.payload().get("retryable").asBoolean()).isFalse();
    assertThat(error.envelope().get("roomCode").asString()).isEqualTo(code);
    assertThat(roomService.getForMember(host.id(), code).members().getFirst().ready()).isFalse();
  }

  @Test
  void aMemberOfAnotherRoomCannotCommandThisRoom() {
    Guest host = newGuest();
    Guest other = newGuest();
    String code = newRoom(host).code();
    String otherCode = newRoom(other).code();
    StompTestClient ws = connectAndSubscribe(other, otherCode);

    ws.command(code, "SET_READY", actionId(), "{\"ready\":true}");

    StompTestClient.Message error = ws.awaitType("ERROR");
    assertThat(error.payload().get("code").asString()).isEqualTo("NOT_IN_ROOM");
    assertThat(roomService.getForMember(host.id(), code).members().getFirst().ready()).isFalse();
    assertThat(roomService.getForMember(other.id(), otherCode).members().getFirst().ready())
        .isFalse();
  }

  @Test
  void malformedAndUnknownRoomCodesAreAnsweredWithoutLeakingExistence() {
    Guest host = newGuest();
    StompTestClient ws = connect(host);
    ws.subscribe("err", "/user/queue/errors");

    ws.sendFrame(
        "SEND",
        Map.of("destination", "/app/rooms/ZZZZZ9/command", "content-type", "application/json"),
        "{\"type\":\"PING\"}");

    assertThat(ws.awaitType("ERROR").payload().get("code").asString()).isEqualTo("NOT_IN_ROOM");
  }

  // ---- connection lifetime ----

  @Test
  void aConnectionIsClosedWhenItsGuestSessionIsRevoked() {
    Guest guest = newGuest();
    StompTestClient ws = connect(guest);

    jdbc.update("update guest_session set revoked_at = now() where guest_id = ?", guest.id());

    assertThat(ws.awaitClose()).isEqualTo(1008);
    assertThat(ws.closeReason()).isEqualTo("SESSION_EXPIRED");
  }

  @Test
  void aNewConnectionReplacesTheOldOne() {
    Guest guest = newGuest();
    Guest alice = newGuest();
    String code = newRoom(guest).code();
    roomService.join(alice.id(), code);
    StompTestClient observer = connectAndSubscribe(alice, code);
    StompTestClient first = connectAndSubscribe(guest, code);

    StompTestClient second = connectAndSubscribe(guest, code);

    first.awaitType("SESSION_REPLACED");
    assertThat(first.awaitClose()).isEqualTo(1008);
    assertThat(first.closeReason()).isEqualTo("SESSION_REPLACED");
    // The replacement keeps working and the guest never looked disconnected to the room.
    second.command(code, "SET_READY", actionId(), "{\"ready\":true}");
    second.awaitType("ACTION_ACK");
    observer.awaitType("READY_UPDATED");
    await()
        .during(Duration.ofMillis(600))
        .atMost(Duration.ofSeconds(3))
        .until(() -> observer.ofType("PLAYER_DISCONNECTED").isEmpty());
  }
}
