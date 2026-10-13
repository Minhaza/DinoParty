package com.dinoparty.session;

import java.util.Map;
import java.util.UUID;

/**
 * Session facts shown in the room DTO: the live session id and running scores (null if unscored).
 */
public record RoomSessionInfo(UUID sessionId, Map<UUID, Integer> scores) {

  public static final RoomSessionInfo NONE = new RoomSessionInfo(null, null);
}
