package com.dinoparty.realtime;

import com.dinoparty.common.Timestamps;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wraps payloads in the contract envelope, assigns gapless per-stream sequence numbers and sends
 * them. Callers hold the room mailbox, and each counter is also locked while sending, so the order
 * of {@code seq} equals the order of publication. Counters are in memory (see the contract for the
 * restart behaviour).
 */
@Component
public class RoomEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(RoomEventPublisher.class);

  private static final class Counter {
    long value;
  }

  private final SimpMessagingTemplate template;
  private final JsonMapper json;
  private final Clock clock;
  private final ConnectionDirectory connections;
  private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

  RoomEventPublisher(
      @Lazy SimpMessagingTemplate template,
      JsonMapper json,
      Clock clock,
      ConnectionDirectory connections) {
    this.template = template;
    this.json = json;
    this.clock = clock;
    this.connections = connections;
  }

  /** Publishes on the room's public stream; returns the assigned {@code seq}. */
  public long publish(String roomCode, UUID roomId, String type, Object payload) {
    Counter counter = counters.computeIfAbsent(roomKey(roomCode), k -> new Counter());
    synchronized (counter) {
      long seq = ++counter.value;
      EventEnvelope envelope =
          new EventEnvelope(
              type,
              roomId,
              roomCode,
              Timestamps.format(clock.instant()),
              seq,
              EventEnvelope.ROOM,
              null,
              payload);
      send("/topic/rooms/" + roomCode, envelope, null);
      return seq;
    }
  }

  /** Publishes on the guest's private stream for the room. */
  public long publishPrivate(
      String roomCode, UUID roomId, UUID guestId, String type, String actionId, Object payload) {
    return publishPrivate(roomCode, roomId, guestId, type, actionId, seq -> payload);
  }

  /**
   * Variant for payloads that embed their own sequence number (snapshots). A guest without a live
   * connection has nobody to receive the event: nothing is sent and no number is consumed (the
   * returned 0 means "not delivered"); on reconnect the client starts from a snapshot anyway.
   */
  public long publishPrivate(
      String roomCode,
      UUID roomId,
      UUID guestId,
      String type,
      String actionId,
      LongFunction<Object> payload) {
    String recipient = connections.current(guestId).map(c -> c.principalName()).orElse(null);
    if (recipient == null) {
      return 0;
    }
    Counter counter = counters.computeIfAbsent(privateKey(roomCode, guestId), k -> new Counter());
    synchronized (counter) {
      long seq = ++counter.value;
      EventEnvelope envelope =
          new EventEnvelope(
              type,
              roomId,
              roomCode,
              Timestamps.format(clock.instant()),
              seq,
              EventEnvelope.PRIVATE,
              actionId,
              payload.apply(seq));
      send("/user/" + recipient + "/queue/rooms/" + roomCode, envelope, null);
      return seq;
    }
  }

  /** Unsequenced error for the guest's {@code /user/queue/errors}; {@code roomCode} may be null. */
  void publishError(UUID guestId, String roomCode, String actionId, Object payload) {
    String recipient = connections.current(guestId).map(c -> c.principalName()).orElse(null);
    if (recipient == null) {
      return;
    }
    EventEnvelope envelope =
        new EventEnvelope(
            "ERROR",
            null,
            roomCode,
            Timestamps.format(clock.instant()),
            0,
            EventEnvelope.PRIVATE,
            actionId,
            payload);
    send("/user/" + recipient + "/queue/errors", envelope, null);
  }

  public long currentRoomSeq(String roomCode) {
    return current(roomKey(roomCode));
  }

  public long currentPrivateSeq(String roomCode, UUID guestId) {
    return current(privateKey(roomCode, guestId));
  }

  void forgetGuest(String roomCode, UUID guestId) {
    counters.remove(privateKey(roomCode, guestId));
  }

  void forgetRoom(String roomCode) {
    counters.remove(roomKey(roomCode));
    String privatePrefix = "P:" + roomCode + ":";
    counters.keySet().removeIf(k -> k.startsWith(privatePrefix));
  }

  int trackedStreams() {
    return counters.size();
  }

  private long current(String key) {
    Counter counter = counters.get(key);
    if (counter == null) {
      return 0;
    }
    synchronized (counter) {
      return counter.value;
    }
  }

  private void send(String destination, EventEnvelope envelope, String wsSessionId) {
    try {
      SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
      headers.setContentType(MediaType.APPLICATION_JSON);
      if (wsSessionId != null) {
        headers.setSessionId(wsSessionId);
      }
      headers.setLeaveMutable(true);
      Message<byte[]> message =
          MessageBuilder.createMessage(
              json.writeValueAsBytes(envelope), headers.getMessageHeaders());
      template.send(destination, message);
    } catch (RuntimeException e) {
      // A failed delivery shows up as a seq gap; the client then resyncs from a snapshot.
      log.warn("Could not publish {} to {}", envelope.type(), destinationKind(destination), e);
    }
  }

  private static String destinationKind(String destination) {
    return destination.startsWith("/topic/") ? "room stream" : "private stream";
  }

  private static String roomKey(String roomCode) {
    return "R:" + roomCode;
  }

  private static String privateKey(String roomCode, UUID guestId) {
    return "P:" + roomCode + ":" + guestId;
  }
}
