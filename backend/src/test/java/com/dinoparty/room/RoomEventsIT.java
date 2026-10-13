package com.dinoparty.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.IntegrationTest;
import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.guest.GuestService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** The facts RoomService publishes for the realtime layer, and the ready-state rules. */
@IntegrationTest
@RecordApplicationEvents
class RoomEventsIT {

  @Autowired GuestService guests;
  @Autowired RoomService rooms;
  @Autowired ApplicationEvents events;

  private UUID newGuest() {
    return guests
        .register("E" + UUID.randomUUID().toString().substring(0, 7), "dino-rex", null, "it")
        .guest()
        .id();
  }

  private String newRoom(UUID host) {
    return rooms.create(host, new CreateRoomCommand("draw-guess", null, null)).code();
  }

  private List<RoomEvent> roomEvents(String code) {
    return events.stream(RoomEvent.class).filter(e -> e.roomCode().equals(code)).toList();
  }

  @Test
  void joinPublishesMemberJoinedOnceAndRejoinPublishesNothing() {
    UUID host = newGuest();
    UUID alice = newGuest();
    String code = newRoom(host);

    rooms.join(alice, code);
    rooms.join(alice, code);

    assertThat(roomEvents(code)).hasSize(1);
    RoomEvent.MemberJoined joined = (RoomEvent.MemberJoined) roomEvents(code).getFirst();
    assertThat(joined.member().guestId()).isEqualTo(alice);
    assertThat(joined.member().ready()).isFalse();
    assertThat(joined.hostGuestId()).isEqualTo(host);
  }

  @Test
  void aRejectedJoinPublishesNothing() {
    UUID host = newGuest();
    String code = rooms.create(host, new CreateRoomCommand("draw-guess", null, 2)).code();
    rooms.join(newGuest(), code);
    long before = roomEvents(code).size();

    assertThatThrownBy(() -> rooms.join(newGuest(), code))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ROOM_FULL));

    assertThat(roomEvents(code)).hasSize((int) before);
  }

  @Test
  void readyIsPublishedOnlyOnRealChangesAndResetOnRejoin() {
    UUID host = newGuest();
    UUID alice = newGuest();
    String code = newRoom(host);
    rooms.join(alice, code);

    rooms.setReady(alice, code, false); // no change
    rooms.setReady(alice, code, true);
    rooms.setReady(alice, code, true); // no change
    assertThat(roomEvents(code).stream().filter(e -> e instanceof RoomEvent.ReadyChanged))
        .hasSize(1);
    assertThat(rooms.getForMember(alice, code).members().get(1).ready()).isTrue();

    rooms.leave(alice, code);
    rooms.join(alice, code);
    assertThat(rooms.getForMember(alice, code).members().get(1).ready()).isFalse();
  }

  @Test
  void onlyMembersMayChangeReady() {
    UUID host = newGuest();
    UUID outsider = newGuest();
    String code = newRoom(host);

    assertThatThrownBy(() -> rooms.setReady(outsider, code, true))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_IN_ROOM));
    assertThatThrownBy(() -> rooms.setReady(host, "ZZZZZ9", true))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ROOM_NOT_FOUND));
    assertThat(rooms.getForMember(host, code).members().getFirst().ready()).isFalse();
  }

  @Test
  void leavingPublishesLeftThenHostChangedThenClosed() {
    UUID host = newGuest();
    UUID alice = newGuest();
    String code = newRoom(host);
    rooms.join(alice, code);

    rooms.leave(host, code, LeaveReason.LEFT);
    rooms.leave(alice, code, LeaveReason.TIMEOUT);

    List<RoomEvent> published = roomEvents(code);
    assertThat(published.subList(1, published.size()))
        .satisfiesExactly(
            e -> assertThat(e).isInstanceOf(RoomEvent.MemberLeft.class),
            e -> assertThat(((RoomEvent.HostChanged) e).hostGuestId()).isEqualTo(alice),
            e -> {
              RoomEvent.MemberLeft left = (RoomEvent.MemberLeft) e;
              assertThat(left.guestId()).isEqualTo(alice);
              assertThat(left.reason()).isEqualTo(LeaveReason.TIMEOUT);
            },
            e -> assertThat(((RoomEvent.Closed) e).reason()).isEqualTo("EMPTY"));
  }

  @Test
  void leavingTwiceOrAsAnOutsiderPublishesNothing() {
    UUID host = newGuest();
    UUID alice = newGuest();
    String code = newRoom(host);
    rooms.join(alice, code);
    rooms.leave(alice, code);
    long before = roomEvents(code).size();

    rooms.leave(alice, code);
    rooms.leave(newGuest(), code);

    assertThat(roomEvents(code)).hasSize((int) before);
  }

  @Test
  void settingsChangesArePublishedWithTheNewBounds() {
    UUID host = newGuest();
    String code = newRoom(host);

    rooms.updateSettings(host, code, new UpdateRoomSettingsCommand("telephone", null, 5));

    RoomEvent.SettingsChanged changed = (RoomEvent.SettingsChanged) roomEvents(code).getLast();
    assertThat(changed.gameKey()).isEqualTo("telephone");
    assertThat(changed.minPlayers()).isEqualTo(4);
    assertThat(changed.maxPlayers()).isEqualTo(5);
  }

  @Test
  void membershipLookups() {
    UUID host = newGuest();
    UUID outsider = newGuest();
    String code = newRoom(host);

    assertThat(rooms.isMember(host, code)).isTrue();
    assertThat(rooms.isMember(host, code.toLowerCase())).isTrue();
    assertThat(rooms.isMember(outsider, code)).isFalse();
    assertThat(rooms.isMember(host, "nope")).isFalse();
    assertThat(rooms.activeRoom(host)).map(RoomRef::code).contains(code);
    assertThat(rooms.activeRoom(outsider)).isEmpty();
    assertThat(rooms.find(code)).isPresent();
    assertThat(rooms.find("ZZZZZ9")).isEmpty();
  }
}
