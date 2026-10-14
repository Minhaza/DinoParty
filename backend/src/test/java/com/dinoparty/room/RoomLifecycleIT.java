package com.dinoparty.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.IntegrationTest;
import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.guest.GuestService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The room status machine of docs/game-engine.md §2.1: allowed edges and every forbidden one. */
@IntegrationTest
class RoomLifecycleIT {

  @Autowired RoomService rooms;
  @Autowired GuestService guests;

  private String newRoom() {
    UUID host =
        guests
            .register("L" + UUID.randomUUID().toString().substring(0, 8), "dino-rex", null, "it")
            .guest()
            .id();
    return rooms.create(host, new CreateRoomCommand("test-game", null, null)).code();
  }

  private void move(String code, RoomStatus to) {
    rooms.changeStatus(code, to, "TEST", Instant.now());
  }

  private RoomStatus status(String code) {
    return rooms.detailsOf(code).status();
  }

  @Test
  void theHappyPathWalksTheWholeMachine() {
    String code = newRoom();

    move(code, RoomStatus.STARTING);
    move(code, RoomStatus.PLAYING);
    move(code, RoomStatus.RESULTS);
    move(code, RoomStatus.WAITING);

    assertThat(status(code)).isEqualTo(RoomStatus.WAITING);
  }

  @Test
  void cancelAndAbortEdgesAreAllowed() {
    String cancelled = newRoom();
    move(cancelled, RoomStatus.STARTING);
    move(cancelled, RoomStatus.WAITING);
    String aborted = newRoom();
    move(aborted, RoomStatus.STARTING);
    move(aborted, RoomStatus.PLAYING);
    move(aborted, RoomStatus.WAITING);

    assertThat(status(cancelled)).isEqualTo(RoomStatus.WAITING);
    assertThat(status(aborted)).isEqualTo(RoomStatus.WAITING);
  }

  @Test
  void everyOtherEdgeIsRejectedAndLeavesTheRoomUntouched() {
    List<RoomStatus[]> forbiddenFromWaiting =
        List.of(
            new RoomStatus[] {RoomStatus.PLAYING},
            new RoomStatus[] {RoomStatus.RESULTS},
            new RoomStatus[] {RoomStatus.WAITING});
    String code = newRoom();
    for (RoomStatus[] to : forbiddenFromWaiting) {
      assertThatThrownBy(() -> move(code, to[0]))
          .isInstanceOfSatisfying(
              DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PHASE));
    }
    assertThat(status(code)).isEqualTo(RoomStatus.WAITING);

    move(code, RoomStatus.STARTING);
    assertThatThrownBy(() -> move(code, RoomStatus.RESULTS)).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> move(code, RoomStatus.STARTING)).isInstanceOf(DomainException.class);
    move(code, RoomStatus.PLAYING);
    assertThatThrownBy(() -> move(code, RoomStatus.STARTING)).isInstanceOf(DomainException.class);
    move(code, RoomStatus.RESULTS);
    assertThatThrownBy(() -> move(code, RoomStatus.PLAYING)).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> move(code, RoomStatus.STARTING)).isInstanceOf(DomainException.class);
    assertThat(status(code)).isEqualTo(RoomStatus.RESULTS);
  }

  @Test
  void aJoinDuringAGameIsRefusedAndReadyIsClearedOnReset() {
    String code = newRoom();
    UUID member =
        guests
            .register("M" + UUID.randomUUID().toString().substring(0, 8), "dino-rex", null, "it")
            .guest()
            .id();
    rooms.join(member, code);
    rooms.setReady(member, code, true);
    move(code, RoomStatus.STARTING);
    UUID late =
        guests
            .register("N" + UUID.randomUUID().toString().substring(0, 8), "dino-rex", null, "it")
            .guest()
            .id();

    assertThatThrownBy(() -> rooms.join(late, code))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ROOM_IN_PROGRESS));
    rooms.changeStatus(code, RoomStatus.WAITING, "GAME_ABORTED", null);
    assertThat(rooms.detailsOf(code).members()).noneMatch(RoomMember::ready);
  }
}
