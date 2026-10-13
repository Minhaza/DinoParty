package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dinoparty.common.DomainException;
import com.dinoparty.room.CreateRoomCommand;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Races between room mutations, commands and event publication. The invariant under test: the
 * public stream is gapless and ordered, and replaying it reproduces the authoritative state.
 */
@RealtimeIntegrationTest
class RealtimeConcurrencyIT extends RealtimeFixture {

  @Autowired SnapshotService snapshots;

  @AfterEach
  void tearDown() {
    closeClients();
  }

  private static <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
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

  private static void assertGaplessFromOne(List<StompTestClient.Message> events) {
    for (int i = 0; i < events.size(); i++) {
      assertThat(events.get(i).seq()).as("event %d", i).isEqualTo(i + 1L);
    }
  }

  @Test
  void concurrentJoinsPublishEachJoinOnceInAGaplessSequence() throws Exception {
    Guest host = newGuest();
    String code =
        roomService.create(host.id(), new CreateRoomCommand("draw-guess", null, 10)).code();
    StompTestClient hostWs = connectAndSubscribe(host, code);
    List<Guest> joiners = new ArrayList<>();
    List<Callable<Object>> joins = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      Guest g = newGuest();
      joiners.add(g);
      joins.add(() -> roomService.join(g.id(), code));
    }

    runConcurrently(joins);

    await().atMost(Duration.ofSeconds(10)).until(() -> hostWs.ofType("PLAYER_JOINED").size() == 9);
    List<StompTestClient.Message> events = hostWs.onStream("ROOM", code);
    assertGaplessFromOne(events);
    assertThat(
            hostWs.ofType("PLAYER_JOINED").stream()
                .map(m -> m.payload().get("player").get("guestId").asString())
                .toList())
        .containsExactlyInAnyOrderElementsOf(joiners.stream().map(g -> g.id().toString()).toList());
  }

  @Test
  void overfillingARoomPublishesOnlyTheJoinsThatSucceeded() throws Exception {
    Guest host = newGuest();
    String code =
        roomService.create(host.id(), new CreateRoomCommand("draw-guess", null, 4)).code();
    StompTestClient hostWs = connectAndSubscribe(host, code);
    List<Callable<Object>> joins = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      Guest g = newGuest();
      joins.add(() -> roomService.join(g.id(), code));
    }

    List<Object> results = runConcurrently(joins);

    long succeeded = results.stream().filter(r -> !(r instanceof DomainException)).count();
    assertThat(succeeded).isEqualTo(3);
    await().atMost(Duration.ofSeconds(10)).until(() -> hostWs.ofType("PLAYER_JOINED").size() == 3);
    Thread.sleep(300);
    assertThat(hostWs.ofType("PLAYER_JOINED")).hasSize(3);
    assertGaplessFromOne(hostWs.onStream("ROOM", code));
  }

  @Test
  void replayingTheEventStreamReproducesTheAuthoritativeState() throws Exception {
    Guest host = newGuest();
    String code =
        roomService.create(host.id(), new CreateRoomCommand("draw-guess", null, 10)).code();
    StompTestClient observer = connectAndSubscribe(host, code);
    List<Guest> members = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      Guest g = newGuest();
      roomService.join(g.id(), code);
      members.add(g);
    }
    // Everybody toggles ready from their own thread while one member leaves.
    List<Callable<Object>> work = new ArrayList<>();
    for (Guest g : members) {
      work.add(
          () -> {
            for (int i = 0; i < 25; i++) {
              roomService.setReady(g.id(), code, i % 3 != 0);
            }
            return null;
          });
    }
    Guest leaver = members.get(0);
    work.add(
        () -> {
          roomService.leave(leaver.id(), code);
          return null;
        });
    runConcurrently(work);
    // A member who left may still have had setReady calls rejected; that is part of the race.

    SnapshotDto state = snapshots.snapshot(host.id(), code);
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                !observer.onStream("ROOM", code).isEmpty()
                    && observer.onStream("ROOM", code).getLast().seq() == state.seq().room());
    List<StompTestClient.Message> events = observer.onStream("ROOM", code);
    assertGaplessFromOne(events);

    // Replay: start from "everyone not ready" and apply the events in order.
    Map<String, Boolean> ready = new HashMap<>();
    ready.put(host.id().toString(), false);
    for (StompTestClient.Message m : events) {
      JsonNode p = m.payload();
      switch (m.type()) {
        case "PLAYER_JOINED" -> ready.put(p.get("player").get("guestId").asString(), false);
        case "READY_UPDATED" -> ready.put(p.get("guestId").asString(), p.get("ready").asBoolean());
        case "PLAYER_LEFT" -> ready.remove(p.get("guestId").asString());
        default -> {}
      }
    }
    Map<String, Boolean> authoritative = new HashMap<>();
    state.room().players().forEach(pl -> authoritative.put(pl.guestId().toString(), pl.ready()));
    assertThat(ready).isEqualTo(authoritative);
    assertThat(authoritative).hasSize(6); // host + 5 remaining
    // READY_UPDATED is only emitted on a real change, so it alternates per member.
    Map<String, Boolean> last = new HashMap<>();
    for (StompTestClient.Message m : observer.ofType("READY_UPDATED")) {
      String who = m.payload().get("guestId").asString();
      boolean value = m.payload().get("ready").asBoolean();
      assertThat(last.getOrDefault(who, false))
          .as("redundant event for %s", who)
          .isNotEqualTo(value);
      last.put(who, value);
    }
  }

  @Test
  void concurrentHostLeaveAndJoinKeepStreamAndHostConsistent() throws Exception {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code =
        roomService.create(host.id(), new CreateRoomCommand("draw-guess", null, 10)).code();
    roomService.join(alice.id(), code);
    StompTestClient aliceWs = connectAndSubscribe(alice, code);
    List<Callable<Object>> work = new ArrayList<>();
    work.add(
        () -> {
          roomService.leave(host.id(), code);
          return null;
        });
    for (int i = 0; i < 5; i++) {
      Guest g = newGuest();
      work.add(() -> roomService.join(g.id(), code));
    }

    runConcurrently(work);

    await().atMost(Duration.ofSeconds(10)).until(() -> aliceWs.ofType("HOST_CHANGED").size() == 1);
    assertThat(roomService.getForMember(alice.id(), code).hostGuestId()).isEqualTo(alice.id());
    // Alice's own join (seq 1) predates her subscription; everything after it is gapless.
    List<StompTestClient.Message> seen = aliceWs.onStream("ROOM", code);
    for (int i = 0; i < seen.size(); i++) {
      assertThat(seen.get(i).seq()).isEqualTo(i + 2L);
    }
  }

  @Test
  void manyConnectionsCommandingAtOnceAllGetOneAckPerCommand() throws Exception {
    Guest host = newGuest();
    String code =
        roomService.create(host.id(), new CreateRoomCommand("draw-guess", null, 10)).code();
    List<Guest> guests = new ArrayList<>();
    List<StompTestClient> sockets = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      Guest g = i == 0 ? host : newGuest();
      if (i > 0) {
        roomService.join(g.id(), code);
      }
      guests.add(g);
      sockets.add(connectAndSubscribe(g, code));
    }
    List<Callable<Object>> work = new ArrayList<>();
    for (int i = 0; i < sockets.size(); i++) {
      StompTestClient ws = sockets.get(i);
      work.add(
          () -> {
            for (int n = 0; n < 10; n++) {
              ws.command(
                  code,
                  "SET_READY",
                  UUID.randomUUID().toString(),
                  "{\"ready\":%s}".formatted(n % 2 == 0));
            }
            return null;
          });
    }

    runConcurrently(work);

    for (StompTestClient ws : sockets) {
      await().atMost(Duration.ofSeconds(10)).until(() -> ws.ofType("ACTION_ACK").size() == 10);
      assertThat(ws.ofType("GAME_ERROR")).isEmpty();
    }
    // Each socket ended on ready=false (n=9), and every member's final state agrees.
    assertThat(snapshots.snapshot(host.id(), code).room().players()).allMatch(p -> !p.ready());
  }
}
