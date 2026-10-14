package com.dinoparty.room;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Facts about committed room changes, published by {@link RoomService} after the transaction
 * commits and while the room mailbox is still held, so listeners observe them in commit order. They
 * carry no wire format; the realtime module maps them to client events.
 */
public sealed interface RoomEvent {

  UUID roomId();

  String roomCode();

  record MemberJoined(UUID roomId, String roomCode, RoomMember member, UUID hostGuestId)
      implements RoomEvent {}

  record MemberLeft(UUID roomId, String roomCode, UUID guestId, LeaveReason reason)
      implements RoomEvent {}

  record ReadyChanged(UUID roomId, String roomCode, UUID guestId, boolean ready)
      implements RoomEvent {}

  record HostChanged(UUID roomId, String roomCode, UUID hostGuestId) implements RoomEvent {}

  record SettingsChanged(
      UUID roomId,
      String roomCode,
      String gameKey,
      Map<String, Object> settings,
      int minPlayers,
      int maxPlayers)
      implements RoomEvent {}

  record Closed(UUID roomId, String roomCode, String reason) implements RoomEvent {}

  /** Room status moved because of the game lifecycle (start, countdown, results, reset). */
  record StatusChanged(
      UUID roomId, String roomCode, RoomStatus status, String reason, Instant startsAt)
      implements RoomEvent {}
}
