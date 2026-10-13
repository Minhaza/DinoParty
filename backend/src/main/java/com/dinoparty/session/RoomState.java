package com.dinoparty.session;

import com.dinoparty.room.RoomStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the session module needs to know about a room right now. */
public record RoomState(
    UUID roomId,
    RoomStatus status,
    String gameKey,
    UUID hostGuestId,
    Map<String, Object> settings,
    List<Member> members) {

  public record Member(
      UUID guestId, String nickname, String avatarKey, String avatarColor, boolean connected) {}

  public boolean hasMember(UUID guestId) {
    return members.stream().anyMatch(m -> m.guestId().equals(guestId));
  }

  public long connectedCount() {
    return members.stream().filter(Member::connected).count();
  }
}
