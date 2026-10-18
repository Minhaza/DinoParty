package com.dinoparty.session;

import java.util.Map;
import java.util.UUID;

/**
 * Session facts shown in the room DTO: the live session id, running scores (null if unscored) and
 * the newest session that is no longer running.
 */
public record RoomSessionInfo(UUID sessionId, Map<UUID, Integer> scores, LastSession lastSession) {

  public static final RoomSessionInfo NONE = new RoomSessionInfo(null, null, null);
}
