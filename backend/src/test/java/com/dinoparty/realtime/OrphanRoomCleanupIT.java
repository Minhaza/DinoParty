package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dinoparty.room.CreateRoomCommand;
import com.dinoparty.room.OrphanRoomCleanup;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The idle-room sweep (game-engine.md §9.3): a {@code WAITING} room in which nobody has a live
 * connection and nothing happened for {@code app.room-orphan-ttl} is closed and its members freed.
 * Rooms are aged by moving their timestamps back, not by moving the clock.
 */
@RealtimeIntegrationTest
@TestPropertySource(properties = "app.realtime.orphan-sweep-interval=200ms")
class OrphanRoomCleanupIT extends RealtimeFixture {

  private static final Duration OLD = Duration.ofHours(2);

  @Autowired OrphanRoomCleanup cleanup;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void tearDown() {
    closeClients();
  }

  /** Makes the room, its memberships and its members' sessions look two hours old. */
  private void age(String code) {
    Timestamp old = Timestamp.from(Instant.now().minus(OLD));
    jdbc.update("UPDATE room SET created_at = ? WHERE code = ?", old, code);
    jdbc.update(
        "UPDATE room_participant SET joined_at = ? WHERE room_id = (SELECT id FROM room WHERE code = ?)",
        old,
        code);
    jdbc.update(
        "UPDATE guest_session SET last_seen_at = ? WHERE guest_id IN (SELECT guest_id FROM"
            + " room_participant WHERE room_id = (SELECT id FROM room WHERE code = ?))",
        old,
        code);
  }

  private String status(String code) {
    return jdbc.queryForObject("SELECT status FROM room WHERE code = ?", String.class, code);
  }

  private int activeMembers(String code) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM room_participant p JOIN room r ON r.id = p.room_id"
                + " WHERE r.code = ? AND p.left_at IS NULL",
            Integer.class,
            code);
    return n == null ? 0 : n;
  }

  @Test
  void aStaleRoomNobodyIsConnectedToIsClosedAndItsMembersAreFreed() {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code = newRoom(host).code();
    roomService.join(alice.id(), code);
    age(code);

    cleanup.closeIdleRooms();

    assertThat(status(code)).isEqualTo("CLOSED");
    assertThat(activeMembers(code)).isZero();
    // Freed: both may play elsewhere now instead of getting ALREADY_IN_ROOM.
    roomService.create(host.id(), new CreateRoomCommand("draw-guess", null, null));
    roomService.create(alice.id(), new CreateRoomCommand("draw-guess", null, null));
  }

  @Test
  void repeatedSweepsChangeNothingMore() {
    Guest host = newGuest();
    String code = newRoom(host).code();
    age(code);
    cleanup.closeIdleRooms();
    Timestamp closedAt =
        jdbc.queryForObject("SELECT closed_at FROM room WHERE code = ?", Timestamp.class, code);

    cleanup.closeIdleRooms();
    cleanup.closeIdleRooms();

    assertThat(status(code)).isEqualTo("CLOSED");
    assertThat(
            jdbc.queryForObject("SELECT closed_at FROM room WHERE code = ?", Timestamp.class, code))
        .isEqualTo(closedAt);
  }

  @Test
  void aRecentRoomIsKept() {
    Guest host = newGuest();
    String code = newRoom(host).code();

    cleanup.closeIdleRooms();

    assertThat(status(code)).isEqualTo("WAITING");
    assertThat(activeMembers(code)).isEqualTo(1);
  }

  @Test
  void aRoomWithAConnectedMemberIsKept() {
    Guest host = newGuest();
    Guest alice = newGuest();
    String code = newRoom(host).code();
    roomService.join(alice.id(), code);
    connectAndSubscribe(alice, code);
    age(code);

    cleanup.closeIdleRooms();

    assertThat(status(code)).isEqualTo("WAITING");
    assertThat(activeMembers(code)).isEqualTo(2);
  }

  @Test
  void aMemberWhoDroppedAndCameBackIsNotAnOrphan() {
    Guest host = newGuest();
    String code = newRoom(host).code();
    StompTestClient first = connectAndSubscribe(host, code);
    age(code);

    first.drop(); // inside the disconnect grace
    cleanup.closeIdleRooms();
    assertThat(status(code)).isEqualTo("WAITING");

    connectAndSubscribe(host, code); // reconnected
    cleanup.closeIdleRooms();
    assertThat(status(code)).isEqualTo("WAITING");
    assertThat(activeMembers(code)).isEqualTo(1);
  }

  @Test
  void aMemberSeenRecentlyKeepsTheRoom() {
    Guest host = newGuest();
    String code = newRoom(host).code();
    age(code);
    jdbc.update(
        "UPDATE guest_session SET last_seen_at = ? WHERE guest_id = ?",
        Timestamp.from(Instant.now()),
        host.id());

    cleanup.closeIdleRooms();

    assertThat(status(code)).isEqualTo("WAITING");
  }

  @Test
  void aRoomWithAGameIsNeverAnOrphan() {
    Guest host = newGuest();
    String code = newRoom(host).code();
    for (String active : new String[] {"STARTING", "PLAYING", "RESULTS"}) {
      jdbc.update("UPDATE room SET status = ? WHERE code = ?", active, code);
      age(code);

      cleanup.closeIdleRooms();

      assertThat(status(code)).isEqualTo(active);
    }
    jdbc.update("UPDATE room SET status = 'WAITING' WHERE code = ?", code);
  }

  @Test
  void theMaintenanceSweepRunsTheCleanupByItself() {
    Guest host = newGuest();
    String code = newRoom(host).code();
    age(code);

    await().atMost(Duration.ofSeconds(10)).until(() -> "CLOSED".equals(status(code)));
  }
}
