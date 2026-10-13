package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.room.RoomDetails;
import com.dinoparty.room.RoomPreview;
import com.dinoparty.room.RoomStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Disconnect grace period, reconnection and host transfer with real sockets. The test profile
 * shortens the grace period to 1.5 s and the sweep to 100 ms.
 */
@RealtimeIntegrationTest
@TestPropertySource(properties = "app.realtime.disconnect-grace=1500ms")
class RealtimePresenceIT extends RealtimeFixture {

  private static final Duration GRACE = Duration.ofMillis(1500);

  @Autowired SnapshotService snapshots;

  @AfterEach
  void tearDown() {
    closeClients();
  }

  private boolean isMember(Guest guest, String code) {
    return roomService.isMember(guest.id(), code);
  }

  private PlayerDto player(Guest viewer, Guest who, String code) {
    return snapshots.snapshot(viewer.id(), code).room().players().stream()
        .filter(p -> p.guestId().equals(who.id()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void reconnectingWithinTheGraceKeepsTheMember() {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code = newRoom(host).code();
    roomService.join(alice.id(), code);
    StompTestClient hostWs = connectAndSubscribe(host, code);
    StompTestClient aliceWs = connectAndSubscribe(alice, code);

    aliceWs.drop();

    StompTestClient.Message disconnected = hostWs.awaitType("PLAYER_DISCONNECTED");
    assertThat(disconnected.payload().get("guestId").asString()).isEqualTo(alice.id().toString());
    Instant graceEnds = Instant.parse(disconnected.payload().get("graceEndsAt").asString());
    assertThat(graceEnds).isAfter(Instant.now().minusSeconds(1));
    assertThat(player(host, alice, code).connection()).isEqualTo("DISCONNECTED");

    StompTestClient again = connectAndSubscribe(alice, code);
    StompTestClient.Message reconnected = hostWs.awaitType("PLAYER_RECONNECTED");
    assertThat(reconnected.payload().get("guestId").asString()).isEqualTo(alice.id().toString());
    assertThat(player(host, alice, code).connection()).isEqualTo("CONNECTED");
    assertThat(reconnected.seq()).isGreaterThan(disconnected.seq());

    // Outliving the original grace period changes nothing.
    await()
        .during(GRACE.plusMillis(800))
        .atMost(GRACE.plusSeconds(5))
        .until(() -> isMember(alice, code) && hostWs.ofType("PLAYER_LEFT").isEmpty());

    // The returning client resynchronises from a snapshot.
    again.command(code, "REQUEST_SNAPSHOT", actionId(), "{}");
    StompTestClient.Message snapshot = again.awaitType("ROOM_SNAPSHOT");
    assertThat(snapshot.payload().get("room").get("players")).hasSize(2);
    assertThat(snapshot.payload().get("seq").get("room").asLong())
        .isEqualTo(hostWs.onStream("ROOM", code).getLast().seq());
  }

  @Test
  void missingTheGraceRemovesThePlayerWithTimeoutReason() {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code = newRoom(host).code();
    roomService.join(alice.id(), code);
    StompTestClient hostWs = connectAndSubscribe(host, code);
    StompTestClient aliceWs = connectAndSubscribe(alice, code);

    aliceWs.drop();

    StompTestClient.Message left = hostWs.awaitType("PLAYER_LEFT");
    assertThat(left.payload().get("guestId").asString()).isEqualTo(alice.id().toString());
    assertThat(left.payload().get("reason").asString()).isEqualTo("TIMEOUT");
    assertThat(hostWs.ofType("PLAYER_DISCONNECTED").getFirst().seq()).isLessThan(left.seq());
    assertThat(isMember(alice, code)).isFalse();
    assertThat(roomService.getForMember(host.id(), code).members()).hasSize(1);
    // They were freed from the room, so they can join another one.
    assertThat(roomService.activeRoom(alice.id())).isEmpty();
  }

  @Test
  void aTimedOutHostHandsTheRoomToTheLongestStandingConnectedPlayer() {
    Guest host = newGuest();
    Guest alice = newGuest();
    Guest bob = newGuest();
    String code = newRoom(host).code();
    roomService.join(alice.id(), code);
    roomService.join(bob.id(), code);
    StompTestClient hostWs = connectAndSubscribe(host, code);
    StompTestClient aliceWs = connectAndSubscribe(alice, code);
    connectAndSubscribe(bob, code);

    hostWs.drop();

    StompTestClient.Message changed = aliceWs.awaitType("HOST_CHANGED");
    assertThat(changed.payload().get("hostGuestId").asString()).isEqualTo(alice.id().toString());
    List<String> order =
        aliceWs.onStream("ROOM", code).stream()
            .map(StompTestClient.Message::type)
            .filter(t -> !t.equals("PLAYER_DISCONNECTED"))
            .toList();
    assertThat(order).containsSubsequence("PLAYER_LEFT", "HOST_CHANGED");
    assertThat(roomService.getForMember(alice.id(), code).hostGuestId()).isEqualTo(alice.id());
    assertThat(player(alice, alice, code).isHost()).isTrue();
  }

  @Test
  void hostTransferSkipsAPlayerWhoIsCurrentlyDisconnected() {
    Guest host = newGuest();
    Guest alice = newGuest();
    Guest bob = newGuest();
    String code = newRoom(host).code();
    roomService.join(alice.id(), code);
    roomService.join(bob.id(), code);
    StompTestClient hostWs = connectAndSubscribe(host, code);
    StompTestClient aliceWs = connectAndSubscribe(alice, code);
    StompTestClient bobWs = connectAndSubscribe(bob, code);

    aliceWs.drop(); // longest-standing candidate, but inside her grace period
    hostWs.awaitType("PLAYER_DISCONNECTED");
    roomService.leave(host.id(), code);

    StompTestClient.Message changed = bobWs.awaitType("HOST_CHANGED");
    assertThat(changed.payload().get("hostGuestId").asString()).isEqualTo(bob.id().toString());
  }

  @Test
  void ifEveryoneLeftIsDisconnectedTheLongestStandingBecomesHost() {
    Guest host = newGuest();
    Guest alice = newGuest();
    Guest bob = newGuest();
    String code = newRoom(host).code();
    roomService.join(alice.id(), code);
    roomService.join(bob.id(), code);
    StompTestClient hostWs = connectAndSubscribe(host, code);
    StompTestClient aliceWs = connectAndSubscribe(alice, code);
    StompTestClient bobWs = connectAndSubscribe(bob, code);
    aliceWs.drop();
    bobWs.drop();
    hostWs.awaitMessage(
        m ->
            m.type().equals("PLAYER_DISCONNECTED")
                && m.payload().get("guestId").asString().equals(bob.id().toString()));

    roomService.leave(host.id(), code);

    RoomDetails room = roomService.getForMember(alice.id(), code);
    assertThat(room.hostGuestId()).isEqualTo(alice.id());
  }

  @Test
  void theLastPlayerTimingOutClosesTheRoom() {
    Guest host = newGuest();
    String code = newRoom(host).code();
    StompTestClient hostWs = connectAndSubscribe(host, code);

    hostWs.drop();

    // Closed rooms stay readable, but the guest is no longer a member, so they get the preview.
    await()
        .atMost(Duration.ofSeconds(8))
        .until(
            () ->
                roomService.get(host.id(), code) instanceof RoomPreview preview
                    && preview.status() == RoomStatus.CLOSED);
    assertThat(roomService.activeRoom(host.id())).isEmpty();
  }

  @Test
  void restOnlyMembersAreNeverTimedOut() throws Exception {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code = newRoom(host).code();
    roomService.join(alice.id(), code); // never opens a socket

    Thread.sleep(GRACE.plusMillis(1000).toMillis());

    assertThat(isMember(alice, code)).isTrue();
    assertThat(isMember(host, code)).isTrue();
  }

  @Test
  void flappingConnectionsProduceAlternatingTransitions() {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code = newRoom(host).code();
    roomService.join(alice.id(), code);
    StompTestClient hostWs = connectAndSubscribe(host, code);
    StompTestClient current = connectAndSubscribe(alice, code);

    for (int i = 0; i < 6; i++) {
      current.drop();
      int expectedDisconnects = i + 1;
      await()
          .atMost(Duration.ofSeconds(3))
          .until(() -> countOf(hostWs, "PLAYER_DISCONNECTED") >= expectedDisconnects);
      current = connectAndSubscribe(alice, code);
      await()
          .atMost(Duration.ofSeconds(3))
          .until(() -> countOf(hostWs, "PLAYER_RECONNECTED") >= expectedDisconnects);
    }

    List<String> transitions = new ArrayList<>();
    for (StompTestClient.Message m : hostWs.onStream("ROOM", code)) {
      if (m.type().equals("PLAYER_DISCONNECTED") || m.type().equals("PLAYER_RECONNECTED")) {
        transitions.add(m.type());
      }
    }
    assertThat(transitions).hasSize(12);
    for (int i = 0; i < transitions.size(); i++) {
      assertThat(transitions.get(i))
          .isEqualTo(i % 2 == 0 ? "PLAYER_DISCONNECTED" : "PLAYER_RECONNECTED");
    }
    assertThat(isMember(alice, code)).isTrue();
    assertThat(player(host, alice, code).connection()).isEqualTo("CONNECTED");
  }

  @Test
  void aSimultaneousReconnectStormEndsConsistent() throws Exception {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code = newRoom(host).code();
    roomService.join(alice.id(), code);
    StompTestClient hostWs = connectAndSubscribe(host, code);
    List<StompTestClient> burst = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      burst.add(connect(alice)); // each replaces the previous one
    }

    // Exactly one live connection remains and it works; nobody observed an inconsistent state.
    StompTestClient last = burst.getLast();
    last.subscribeRoom(code);
    last.command(code, "SET_READY", actionId(), "{\"ready\":true}");
    last.awaitType("ACTION_ACK");
    hostWs.awaitType("READY_UPDATED");
    Thread.sleep(GRACE.plusMillis(500).toMillis());
    assertThat(isMember(alice, code)).isTrue();
    assertThat(hostWs.ofType("PLAYER_LEFT")).isEmpty();
    assertThat(player(host, alice, code).connection()).isEqualTo("CONNECTED");
  }

  @Test
  void aTimedOutPlayerCannotCommandAnymore() {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code = newRoom(host).code();
    roomService.join(alice.id(), code);
    StompTestClient hostWs = connectAndSubscribe(host, code);
    StompTestClient aliceWs = connectAndSubscribe(alice, code);
    aliceWs.drop();
    hostWs.awaitType("PLAYER_LEFT");

    StompTestClient back = connect(alice);
    back.subscribe("err", "/user/queue/errors");
    back.command(code, "SET_READY", actionId(), "{\"ready\":true}");

    assertThat(back.awaitType("ERROR").payload().get("code").asString()).isEqualTo("NOT_IN_ROOM");
    assertThatThrownBy(() -> roomService.setReady(alice.id(), code, true))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_IN_ROOM));
  }

  private static long countOf(StompTestClient client, String type) {
    return client.ofType(type).size();
  }
}
