package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Sequence numbers are the contract's ordering device, so their invariants get their own tests. */
class RoomEventPublisherTest {

  private record Sent(String destination, JsonNode envelope) {}

  private final JsonMapper json = JsonMapper.builder().build();
  private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
  private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
  private final Clock clock =
      Clock.fixed(Instant.parse("2026-01-02T03:04:05.678Z"), ZoneOffset.UTC);
  private final ConnectionDirectory connections = new ConnectionDirectory();
  private final RoomEventPublisher publisher =
      new RoomEventPublisher(template, json, clock, connections);
  private final UUID roomId = UUID.randomUUID();
  private final UUID alice = UUID.randomUUID();

  RoomEventPublisherTest() {
    connections.register(new ConnectionDirectory.Connection(alice, UUID.randomUUID(), "ws-alice"));
    doAnswer(
            inv -> {
              Message<?> message = inv.getArgument(1);
              sent.add(new Sent(inv.getArgument(0), json.readTree((byte[]) message.getPayload())));
              return null;
            })
        .when(template)
        .send(anyString(), any(Message.class));
  }

  @Test
  void envelopeCarriesTypeRoomTimestampStreamAndPayload() {
    long seq = publisher.publish("ABC234", roomId, "HOST_CHANGED", Map.of("hostGuestId", alice));

    assertThat(seq).isEqualTo(1);
    Sent event = sent.getFirst();
    assertThat(event.destination()).isEqualTo("/topic/rooms/ABC234");
    JsonNode e = event.envelope();
    assertThat(e.get("type").asString()).isEqualTo("HOST_CHANGED");
    assertThat(e.get("roomId").asString()).isEqualTo(roomId.toString());
    assertThat(e.get("roomCode").asString()).isEqualTo("ABC234");
    assertThat(e.get("ts").asString()).isEqualTo("2026-01-02T03:04:05.678Z");
    assertThat(e.get("seq").asLong()).isEqualTo(1);
    assertThat(e.get("stream").asString()).isEqualTo("ROOM");
    assertThat(e.get("actionId").isNull()).isTrue();
    assertThat(e.get("payload").get("hostGuestId").asString()).isEqualTo(alice.toString());
  }

  @Test
  void streamsCountIndependentlyFromOne() {
    UUID bob = UUID.randomUUID();
    connections.register(new ConnectionDirectory.Connection(bob, UUID.randomUUID(), "ws-bob"));
    publisher.publish("ABC234", roomId, "A", Map.of());
    publisher.publish("ABC234", roomId, "B", Map.of());
    long aliceFirst = publisher.publishPrivate("ABC234", roomId, alice, "P", "act-1", Map.of());
    long bobFirst = publisher.publishPrivate("ABC234", roomId, bob, "P", null, Map.of());
    long otherRoom = publisher.publish("XYZ789", roomId, "A", Map.of());

    assertThat(publisher.currentRoomSeq("ABC234")).isEqualTo(2);
    assertThat(aliceFirst).isEqualTo(1);
    assertThat(bobFirst).isEqualTo(1);
    assertThat(otherRoom).isEqualTo(1);
    assertThat(publisher.currentPrivateSeq("ABC234", alice)).isEqualTo(1);
    assertThat(publisher.currentRoomSeq("NOPE22")).isZero();
  }

  @Test
  void privateEventsGoToTheUserQueueAndEchoTheActionId() {
    publisher.publishPrivate(
        "ABC234", roomId, alice, "ACTION_ACK", "act-1", Map.of("accepted", true));

    Sent event = sent.getFirst();
    assertThat(event.destination()).isEqualTo("/user/" + alice + "_ws-alice/queue/rooms/ABC234");
    assertThat(event.envelope().get("stream").asString()).isEqualTo("PRIVATE");
    assertThat(event.envelope().get("actionId").asString()).isEqualTo("act-1");
  }

  @Test
  void payloadBuilderSeesTheSequenceNumberItIsPublishedWith() {
    publisher.publishPrivate("ABC234", roomId, alice, "X", null, Map.of());
    publisher.publishPrivate(
        "ABC234", roomId, alice, "ROOM_SNAPSHOT", null, seq -> Map.of("embedded", seq));

    JsonNode second = sent.get(1).envelope();
    assertThat(second.get("seq").asLong()).isEqualTo(2);
    assertThat(second.get("payload").get("embedded").asLong()).isEqualTo(2);
  }

  @Test
  void errorsAreUnsequencedAndMayHaveNoRoom() {
    publisher.publishError(alice, null, null, Map.of("code", "NOT_IN_ROOM"));

    Sent event = sent.getFirst();
    assertThat(event.destination()).isEqualTo("/user/" + alice + "_ws-alice/queue/errors");
    assertThat(event.envelope().get("type").asString()).isEqualTo("ERROR");
    assertThat(event.envelope().get("seq").asLong()).isZero();
    assertThat(event.envelope().get("roomCode").isNull()).isTrue();
    assertThat(event.envelope().get("roomId").isNull()).isTrue();
  }

  @Test
  void concurrentPublishersProduceGaplessOrderedSequence() throws Exception {
    int threads = 16;
    int perThread = 200;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    try {
      for (int t = 0; t < threads; t++) {
        pool.submit(
            () -> {
              go.await();
              for (int i = 0; i < perThread; i++) {
                publisher.publish("ABC234", roomId, "TICK", Map.of());
              }
              return null;
            });
      }
      go.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    List<Long> seqs;
    synchronized (sent) {
      seqs = sent.stream().map(s -> s.envelope().get("seq").asLong()).toList();
    }
    assertThat(seqs).hasSize(threads * perThread);
    // Sending happens under the counter lock, so arrival order equals sequence order.
    for (int i = 0; i < seqs.size(); i++) {
      assertThat(seqs.get(i)).isEqualTo(i + 1);
    }
  }

  @Test
  void aGuestWithoutAConnectionReceivesNothingAndConsumesNoSequenceNumber() {
    UUID offline = UUID.randomUUID();

    long seq = publisher.publishPrivate("ABC234", roomId, offline, "ACTION_ACK", "a", Map.of());
    publisher.publishError(offline, "ABC234", null, Map.of());

    assertThat(seq).isZero();
    assertThat(sent).isEmpty();
    assertThat(publisher.currentPrivateSeq("ABC234", offline)).isZero();
  }

  @Test
  void privateEventsFollowTheGuestsCurrentConnection() {
    connections.register(new ConnectionDirectory.Connection(alice, UUID.randomUUID(), "ws-new"));

    publisher.publishPrivate("ABC234", roomId, alice, "X", null, Map.of());

    assertThat(sent.getFirst().destination())
        .isEqualTo("/user/" + alice + "_ws-new/queue/rooms/ABC234");
  }

  @Test
  void forgettingFreesCountersAndRestartsFromOne() {
    publisher.publish("ABC234", roomId, "A", Map.of());
    publisher.publishPrivate("ABC234", roomId, alice, "P", null, Map.of());
    assertThat(publisher.trackedStreams()).isEqualTo(2);

    publisher.forgetGuest("ABC234", alice);
    assertThat(publisher.trackedStreams()).isEqualTo(1);

    publisher.forgetRoom("ABC234");
    assertThat(publisher.trackedStreams()).isZero();
    assertThat(publisher.publish("ABC234", roomId, "A", Map.of())).isEqualTo(1);
  }

  @Test
  void aFailingSendDoesNotThrowIntoTheRoomOperation() {
    doAnswer(
            inv -> {
              throw new IllegalStateException("broker down");
            })
        .when(template)
        .send(anyString(), any(Message.class));

    long seq = publisher.publish("ABC234", roomId, "A", Map.of());

    // The number is consumed, so the client sees a gap and resyncs from a snapshot.
    assertThat(seq).isEqualTo(1);
    assertThat(publisher.currentRoomSeq("ABC234")).isEqualTo(1);
  }
}
