package com.dinoparty.realtime;

import java.util.UUID;

/** Every server event on the wire (docs/api-contracts.md §3.2). */
public record EventEnvelope(
    String type,
    UUID roomId,
    String roomCode,
    String ts,
    long seq,
    String stream,
    String actionId,
    Object payload) {

  public static final String ROOM = "ROOM";
  public static final String PRIVATE = "PRIVATE";
}
