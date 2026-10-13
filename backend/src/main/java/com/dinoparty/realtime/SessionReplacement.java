package com.dinoparty.realtime;

import com.dinoparty.room.RoomService;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tells a guest's existing connection that a newer one is taking over. It runs while the new
 * connection is still being authenticated, i.e. before that connection can have subscribed to
 * anything, so the user-destination message can only reach the old connection's subscription.
 */
@Component
class SessionReplacement {

  private final PresenceTracker presence;
  private final RoomService rooms;
  private final RoomEventPublisher publisher;

  SessionReplacement(PresenceTracker presence, RoomService rooms, RoomEventPublisher publisher) {
    this.presence = presence;
    this.rooms = rooms;
    this.publisher = publisher;
  }

  void announce(UUID guestId) {
    if (!presence.hasLiveConnection(guestId)) {
      return;
    }
    rooms
        .activeRoom(guestId)
        .ifPresent(
            room ->
                rooms.inRoom(
                    room.code(),
                    () ->
                        publisher.publishPrivate(
                            room.code(), room.id(), guestId, "SESSION_REPLACED", null, Map.of())));
  }
}
