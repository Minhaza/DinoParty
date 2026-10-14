package com.dinoparty.realtime;

import com.dinoparty.common.Timestamps;
import com.dinoparty.room.RoomEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns committed room changes into client events. It runs after the transaction commits but still
 * inside the room mailbox, so events reach the stream in commit order and a snapshot taken in the
 * mailbox always matches the last published {@code seq}. It contains no business rules.
 */
@Component
class RoomEventBridge {

  private static final Logger log = LoggerFactory.getLogger(RoomEventBridge.class);

  private final RoomEventPublisher publisher;
  private final SubscriptionRegistry subscriptions;
  private final PresenceTracker presence;
  private final ActionDeduper deduper;

  RoomEventBridge(
      RoomEventPublisher publisher,
      SubscriptionRegistry subscriptions,
      PresenceTracker presence,
      ActionDeduper deduper) {
    this.publisher = publisher;
    this.subscriptions = subscriptions;
    this.presence = presence;
    this.deduper = deduper;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Order(0)
  void on(RoomEvent event) {
    try {
      switch (event) {
        case RoomEvent.MemberJoined e ->
            publisher.publish(
                e.roomCode(),
                e.roomId(),
                "PLAYER_JOINED",
                Map.of("player", PlayerDto.from(e.member(), e.hostGuestId(), false)));
        case RoomEvent.MemberLeft e -> {
          publisher.publish(
              e.roomCode(),
              e.roomId(),
              "PLAYER_LEFT",
              Map.of("guestId", e.guestId(), "reason", e.reason().name()));
          // After the event: the leaver still receives their own PLAYER_LEFT, nothing after it.
          subscriptions.revokeMember(e.guestId(), e.roomCode());
          presence.forgetMember(e.roomCode(), e.guestId());
          deduper.forgetMember(e.roomCode(), e.guestId());
          publisher.forgetGuest(e.roomCode(), e.guestId());
        }
        case RoomEvent.ReadyChanged e ->
            publisher.publish(
                e.roomCode(),
                e.roomId(),
                "READY_UPDATED",
                Map.of("guestId", e.guestId(), "ready", e.ready()));
        case RoomEvent.HostChanged e ->
            publisher.publish(
                e.roomCode(), e.roomId(), "HOST_CHANGED", Map.of("hostGuestId", e.hostGuestId()));
        case RoomEvent.SettingsChanged e ->
            publisher.publish(
                e.roomCode(),
                e.roomId(),
                "ROOM_SETTINGS_UPDATED",
                Map.of(
                    "gameKey", e.gameKey(),
                    "settings", e.settings(),
                    "minPlayers", e.minPlayers(),
                    "maxPlayers", e.maxPlayers()));
        case RoomEvent.StatusChanged e -> {
          Map<String, Object> payload = new LinkedHashMap<>();
          payload.put("status", e.status().name());
          payload.put("reason", e.reason());
          if (e.startsAt() != null) {
            payload.put("startsAt", Timestamps.format(e.startsAt()));
          }
          publisher.publish(e.roomCode(), e.roomId(), "ROOM_STATUS_CHANGED", payload);
        }
        case RoomEvent.Closed e -> {
          publisher.publish(e.roomCode(), e.roomId(), "ROOM_CLOSED", Map.of("reason", e.reason()));
          subscriptions.revokeRoom(e.roomCode());
          presence.forgetRoom(e.roomCode());
          deduper.forgetRoom(e.roomCode());
          publisher.forgetRoom(e.roomCode());
        }
      }
    } catch (RuntimeException e) {
      // The state change is already committed; clients repair any gap with a snapshot.
      log.warn("Could not publish room event {}", event.getClass().getSimpleName(), e);
    }
  }
}
