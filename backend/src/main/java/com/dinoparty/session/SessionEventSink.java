package com.dinoparty.session;

import java.util.Map;
import java.util.UUID;

/** Port to the realtime module: delivery of engine and game events, in call order. */
public interface SessionEventSink {

  void publish(String roomCode, UUID roomId, String type, Map<String, Object> payload);

  void publishPrivate(
      String roomCode, UUID roomId, UUID guestId, String type, Map<String, Object> payload);
}
