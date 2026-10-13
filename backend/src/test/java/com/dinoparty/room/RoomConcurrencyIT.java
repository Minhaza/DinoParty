package com.dinoparty.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.IntegrationTest;
import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.guest.GuestService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Races against the real service and database; the mailbox plus DB constraints must hold. */
@IntegrationTest
class RoomConcurrencyIT {

  @Autowired GuestService guests;
  @Autowired RoomService rooms;

  private UUID newGuest(String prefix) {
    return guests
        .register(prefix + UUID.randomUUID().toString().substring(0, 6), "dino-rex", null, "it")
        .guest()
        .id();
  }

  private static <T> List<Object> runAll(List<Callable<T>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Object>> futures = new ArrayList<>();
      for (Callable<T> task : tasks) {
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  try {
                    return task.call();
                  } catch (DomainException e) {
                    return e;
                  }
                }));
      }
      go.countDown();
      List<Object> results = new ArrayList<>();
      for (Future<Object> f : futures) {
        results.add(f.get());
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentJoinsNeverExceedCapacity() throws Exception {
    UUID host = newGuest("Host");
    RoomDetails room = rooms.create(host, new CreateRoomCommand("telephone", null, 5));
    List<Callable<RoomDetails>> joins = new ArrayList<>();
    for (int i = 0; i < 24; i++) {
      UUID guest = newGuest("J" + i);
      joins.add(() -> rooms.join(guest, room.code()));
    }

    List<Object> results = runAll(joins);

    long succeeded = results.stream().filter(r -> r instanceof RoomDetails).count();
    long full =
        results.stream()
            .filter(r -> r instanceof DomainException e && e.code() == ErrorCode.ROOM_FULL)
            .count();
    assertThat(succeeded).isEqualTo(4); // 5 seats, the host already holds one
    assertThat(full).isEqualTo(20);
    RoomDetails after = (RoomDetails) rooms.get(host, room.code());
    assertThat(after.members()).hasSize(5);
    assertThat(after.members().stream().map(RoomMember::guestId).collect(Collectors.toSet()))
        .hasSize(5);
  }

  @Test
  void sameGuestJoiningConcurrentlyYieldsOneMembership() throws Exception {
    UUID host = newGuest("Host");
    UUID friend = newGuest("Friend");
    RoomDetails room = rooms.create(host, new CreateRoomCommand("draw-guess", null, null));
    List<Callable<RoomDetails>> joins = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      joins.add(() -> rooms.join(friend, room.code()));
    }

    List<Object> results = runAll(joins);

    assertThat(results).allMatch(r -> r instanceof RoomDetails);
    assertThat(((RoomDetails) rooms.get(host, room.code())).members()).hasSize(2);
  }

  @Test
  void aGuestRacingIntoTwoRoomsEndsUpInExactlyOne() throws Exception {
    UUID hostA = newGuest("HostA");
    UUID hostB = newGuest("HostB");
    UUID racer = newGuest("Racer");
    RoomDetails roomA = rooms.create(hostA, new CreateRoomCommand("draw-guess", null, null));
    RoomDetails roomB = rooms.create(hostB, new CreateRoomCommand("draw-guess", null, null));

    List<Object> results =
        runAll(
            List.of(() -> rooms.join(racer, roomA.code()), () -> rooms.join(racer, roomB.code())));

    assertThat(results.stream().filter(r -> r instanceof RoomDetails).count()).isEqualTo(1);
    assertThat(
            results.stream()
                .filter(
                    r -> r instanceof DomainException e && e.code() == ErrorCode.ALREADY_IN_ROOM)
                .count())
        .isEqualTo(1);
  }

  @Test
  void aGuestCreatingRoomsConcurrentlyGetsOneRoom() throws Exception {
    UUID creator = newGuest("Creator");
    List<Callable<RoomDetails>> creates = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      creates.add(() -> rooms.create(creator, new CreateRoomCommand("draw-guess", null, null)));
    }

    List<Object> results = runAll(creates);

    assertThat(results.stream().filter(r -> r instanceof RoomDetails).count()).isEqualTo(1);
    assertThat(results.stream().filter(r -> r instanceof DomainException).count()).isEqualTo(5);
  }

  @Test
  void concurrentLeavesTransferHostExactlyOnceAndCloseWhenEmpty() throws Exception {
    UUID host = newGuest("Host");
    RoomDetails room = rooms.create(host, new CreateRoomCommand("draw-guess", null, null));
    List<UUID> members = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      UUID guest = newGuest("M" + i);
      members.add(guest);
      rooms.join(guest, room.code());
    }
    List<Callable<Object>> leaves = new ArrayList<>();
    leaves.add(
        () -> {
          rooms.leave(host, room.code());
          return null;
        });
    for (UUID member : members.subList(0, 2)) {
      leaves.add(
          () -> {
            rooms.leave(member, room.code());
            return null;
          });
    }

    runAll(leaves);

    RoomDetails after = (RoomDetails) rooms.get(members.get(4), room.code());
    assertThat(after.members()).hasSize(3);
    assertThat(after.members().stream().map(RoomMember::guestId))
        .contains(after.hostGuestId())
        .doesNotContain(host);

    for (UUID member : members.subList(2, 5)) {
      rooms.leave(member, room.code());
    }
    assertThat(((RoomPreview) rooms.get(host, room.code())).status()).isEqualTo(RoomStatus.CLOSED);
  }
}
