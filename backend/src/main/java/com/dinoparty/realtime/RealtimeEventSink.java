package com.dinoparty.realtime;

import com.dinoparty.session.SessionEventSink;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Delivers engine events through the sequenced room and private streams. */
@Component
class RealtimeEventSink implements SessionEventSink {

  private final RoomEventPublisher publisher;

  RealtimeEventSink(RoomEventPublisher publisher) {
    this.publisher = publisher;
  }

  @Override
  public void publish(String roomCode, UUID roomId, String type, Map<String, Object> payload) {
    publisher.publish(roomCode, roomId, type, payload);
  }

  @Override
  public void publishPrivate(
      String roomCode, UUID roomId, UUID guestId, String type, Map<String, Object> payload) {
    publisher.publishPrivate(roomCode, roomId, guestId, type, null, payload);
  }
}
