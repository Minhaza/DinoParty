package com.dinoparty.realtime;

import com.dinoparty.common.Timestamps;
import com.dinoparty.room.RoomDetails;
import com.dinoparty.room.RoomProperties;
import com.dinoparty.room.RoomService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Builds viewer-specific snapshots. Every snapshot is assembled inside the room mailbox, so it is
 * exactly the state after the last published event of each stream and its {@code seq} values tell
 * the client which later events to apply.
 */
@Service
public class SnapshotService {

  private final RoomService rooms;
  private final RoomEventPublisher publisher;
  private final PresenceTracker presence;
  private final RoomProperties roomProperties;
  private final Clock clock;

  SnapshotService(
      RoomService rooms,
      RoomEventPublisher publisher,
      PresenceTracker presence,
      RoomProperties roomProperties,
      Clock clock) {
    this.rooms = rooms;
    this.publisher = publisher;
    this.presence = presence;
    this.roomProperties = roomProperties;
    this.clock = clock;
  }

  /** REST variant: reports the current counters without consuming a sequence number. */
  public SnapshotDto snapshot(UUID guestId, String roomCode) {
    return rooms.inRoom(
        roomCode,
        () -> {
          RoomDetails details = rooms.getForMember(guestId, roomCode);
          return build(
              details,
              publisher.currentRoomSeq(details.code()),
              publisher.currentPrivateSeq(details.code(), guestId));
        });
  }

  /** STOMP variant: delivers a private {@code ROOM_SNAPSHOT} whose own {@code seq} it reflects. */
  void sendTo(UUID guestId, String roomCode, String actionId) {
    rooms.inRoom(
        roomCode,
        () -> {
          RoomDetails details = rooms.getForMember(guestId, roomCode);
          long roomSeq = publisher.currentRoomSeq(details.code());
          publisher.publishPrivate(
              details.code(),
              details.id(),
              guestId,
              "ROOM_SNAPSHOT",
              actionId,
              privateSeq -> build(details, roomSeq, privateSeq));
          return null;
        });
  }

  private SnapshotDto build(RoomDetails details, long roomSeq, long privateSeq) {
    return new SnapshotDto(
        RoomDto.from(details, roomProperties.publicUrl(), presence),
        Timestamps.format(clock.instant()),
        new SnapshotDto.Seq(roomSeq, privateSeq),
        null);
  }
}
