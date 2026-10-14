package com.dinoparty.session;

import com.dinoparty.room.MemberPresence;
import com.dinoparty.room.RoomDetails;
import com.dinoparty.room.RoomService;
import com.dinoparty.room.RoomStatus;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class RoomServiceLifecycle implements RoomLifecycle {

  private final RoomService rooms;
  private final ObjectProvider<MemberPresence> presence;

  RoomServiceLifecycle(RoomService rooms, ObjectProvider<MemberPresence> presence) {
    this.rooms = rooms;
    this.presence = presence;
  }

  @Override
  public <T> T inRoom(String roomCode, Supplier<T> action) {
    return rooms.inRoom(roomCode, action);
  }

  @Override
  public RoomState state(String roomCode) {
    RoomDetails details = rooms.detailsOf(roomCode);
    MemberPresence view = presence.getIfAvailable();
    List<RoomState.Member> members =
        details.members().stream()
            .map(
                m ->
                    new RoomState.Member(
                        m.guestId(),
                        m.nickname(),
                        m.avatarKey(),
                        m.avatarColor(),
                        view == null || !view.isDisconnected(details.code(), m.guestId())))
            .toList();
    return new RoomState(
        details.id(),
        details.status(),
        details.gameKey(),
        details.hostGuestId(),
        details.settings(),
        members);
  }

  @Override
  public void changeStatus(String roomCode, RoomStatus to, String reason, Instant startsAt) {
    rooms.changeStatus(roomCode, to, reason, startsAt);
  }

  @Override
  public int resetAfterRestart() {
    return rooms.resetInterruptedRooms();
  }
}
