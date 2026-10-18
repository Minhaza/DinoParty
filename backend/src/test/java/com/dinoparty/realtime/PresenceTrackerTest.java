package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dinoparty.room.LeaveReason;
import com.dinoparty.room.RoomRef;
import com.dinoparty.room.RoomService;
import com.dinoparty.session.RoomSessionInfo;
import com.dinoparty.session.SessionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Presence transitions with a manual clock and a synchronous executor: no sockets, no sleeps. */
class PresenceTrackerTest {

  private static final class ManualClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final RoomService rooms = mock(RoomService.class);
  private final SessionService sessions = mock(SessionService.class);
  private final RoomEventPublisher publisher = mock(RoomEventPublisher.class);
  private final ManualClock clock = new ManualClock();
  private final UUID roomId = UUID.randomUUID();
  private final RoomRef room = new RoomRef(roomId, "ABC234");
  private final UUID guest = UUID.randomUUID();
  private final UUID session = UUID.randomUUID();
  private final java.util.List<Runnable> queued = new java.util.ArrayList<>();
  private boolean deferred;
  private PresenceTracker presence;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    RealtimeProperties properties =
        new RealtimeProperties(
            Duration.ofSeconds(30), null, null, null, null, null, null, null, null, null, null);
    presence =
        new PresenceTracker(
            rooms,
            sessions,
            publisher,
            new ConnectionDirectory(),
            clock,
            properties,
            task -> {
              if (deferred) {
                queued.add(task);
              } else {
                task.run();
              }
            });
    when(rooms.activeRoom(guest)).thenReturn(Optional.of(room));
    when(sessions.info(any())).thenReturn(RoomSessionInfo.NONE);
    when(rooms.inRoom(eq("ABC234"), any()))
        .thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(1)).get());
  }

  private ConnectionDirectory.Connection connection(String wsSessionId) {
    return new ConnectionDirectory.Connection(guest, session, wsSessionId);
  }

  @Test
  void disconnectStartsGraceAndPublishesOnce() {
    presence.connected(connection("ws1"));
    presence.disconnected(guest, "ws1");
    presence.reconcile(guest); // a repeated/late reconcile must not repeat the event

    assertThat(presence.isDisconnected("ABC234", guest)).isTrue();
    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(publisher)
        .publish(eq("ABC234"), eq(roomId), eq("PLAYER_DISCONNECTED"), payload.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) payload.getValue();
    assertThat(body.get("guestId")).isEqualTo(guest);
    assertThat(body.get("graceEndsAt")).isEqualTo("2026-01-01T00:00:30.000Z");
  }

  @Test
  void reconnectWithinGraceCancelsItAndPublishesOnce() {
    presence.connected(connection("ws1"));
    presence.disconnected(guest, "ws1");
    clock.advance(Duration.ofSeconds(10));
    presence.connected(connection("ws2"));
    presence.reconcile(guest);

    assertThat(presence.isDisconnected("ABC234", guest)).isFalse();
    verify(publisher).publish(eq("ABC234"), eq(roomId), eq("PLAYER_RECONNECTED"), any());

    clock.advance(Duration.ofMinutes(5));
    presence.sweep();
    verify(rooms, never()).leave(any(), any(), any());
  }

  @Test
  void aMemberIsLiveWhileConnectedOrInsideTheGraceOnly() {
    assertThat(presence.isLive("ABC234", guest)).as("never connected").isFalse();

    presence.connected(connection("ws1"));
    assertThat(presence.isLive("ABC234", guest)).as("connected").isTrue();

    presence.disconnected(guest, "ws1");
    assertThat(presence.isLive("ABC234", guest)).as("in grace").isTrue();

    clock.advance(Duration.ofSeconds(30));
    presence.sweep();
    assertThat(presence.isLive("ABC234", guest)).as("grace over").isFalse();
  }

  @Test
  void aClosedSocketWaitingForItsReconcileStillCountsAsLive() {
    presence.connected(connection("ws1"));
    deferred = true;

    presence.disconnected(guest, "ws1"); // reconcile queued behind the room mailbox

    assertThat(presence.isDisconnected("ABC234", guest)).isFalse();
    assertThat(presence.isLive("ABC234", guest)).isTrue();
    deferred = false;
    queued.forEach(Runnable::run);
    assertThat(presence.isLive("ABC234", guest)).as("now in grace").isTrue();
    clock.advance(Duration.ofSeconds(30));
    presence.sweep();
    assertThat(presence.isLive("ABC234", guest)).isFalse();
  }

  @Test
  void aReplacedConnectionClosingDoesNotCountAsDisconnect() {
    presence.connected(connection("ws1"));
    Optional<ConnectionDirectory.Connection> replaced = presence.connected(connection("ws2"));
    presence.disconnected(guest, "ws1"); // the old tab finally closes

    assertThat(replaced).map(ConnectionDirectory.Connection::wsSessionId).contains("ws1");
    assertThat(presence.isDisconnected("ABC234", guest)).isFalse();
    verify(publisher, never()).publish(any(), any(), eq("PLAYER_DISCONNECTED"), any());
    assertThat(presence.liveCount()).isEqualTo(1);
  }

  @Test
  void reconnectingWithTheSameSocketIsNotAReplacement() {
    presence.connected(connection("ws1"));
    assertThat(presence.connected(connection("ws1"))).isEmpty();
  }

  @Test
  void graceExpiryRemovesTheMemberWithTimeoutReason() {
    presence.connected(connection("ws1"));
    presence.disconnected(guest, "ws1");

    clock.advance(Duration.ofSeconds(29));
    presence.sweep();
    verify(rooms, never()).leave(any(), any(), any());

    clock.advance(Duration.ofSeconds(1));
    presence.sweep();
    verify(rooms).leave(guest, "ABC234", LeaveReason.TIMEOUT);
    assertThat(presence.isDisconnected("ABC234", guest)).isFalse();
    assertThat(presence.disconnectedCount()).isZero();
  }

  @Test
  void aGuestWhoReconnectedJustBeforeTheSweepIsNotRemoved() {
    presence.connected(connection("ws1"));
    presence.disconnected(guest, "ws1");
    clock.advance(Duration.ofSeconds(31));
    // The socket is back but its reconcile has not run yet: the sweep must not act on stale state.
    deferred = true;
    presence.connected(connection("ws2"));
    presence.sweep();
    verify(rooms, never()).leave(any(), any(), any());

    deferred = false;
    queued.forEach(Runnable::run);
    assertThat(presence.isDisconnected("ABC234", guest)).isFalse();
    verify(publisher).publish(eq("ABC234"), eq(roomId), eq("PLAYER_RECONNECTED"), any());
  }

  @Test
  void leavingTheRoomForgetsTheGraceTimer() {
    presence.connected(connection("ws1"));
    presence.disconnected(guest, "ws1");
    presence.forgetMember("ABC234", guest);

    clock.advance(Duration.ofMinutes(5));
    presence.sweep();
    verify(rooms, never()).leave(any(), any(), any());
  }

  @Test
  void aFailingLeaveIsRetriedOnTheNextSweep() {
    presence.connected(connection("ws1"));
    presence.disconnected(guest, "ws1");
    clock.advance(Duration.ofSeconds(31));
    org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
        .doNothing()
        .when(rooms)
        .leave(guest, "ABC234", LeaveReason.TIMEOUT);

    presence.sweep();
    assertThat(presence.isDisconnected("ABC234", guest)).isTrue();

    presence.sweep();
    assertThat(presence.isDisconnected("ABC234", guest)).isFalse();
  }

  @Test
  void aGuestWhoAlreadyLeftTheRoomProducesNoPresenceEvents() {
    when(rooms.activeRoom(guest)).thenReturn(Optional.empty());
    presence.connected(connection("ws1"));
    presence.disconnected(guest, "ws1");

    verify(publisher, never()).publish(any(), any(), any(), any());
  }

  @Test
  void aGuestWhoMovedRoomsWhileWaitingForTheMailboxIsIgnored() {
    // First lookup (outside the mailbox) says room A; inside the mailbox they are in room B.
    when(rooms.activeRoom(guest))
        .thenReturn(Optional.of(room))
        .thenReturn(Optional.of(new RoomRef(UUID.randomUUID(), "ZZZ999")));
    presence.connected(connection("ws1"));
    presence.disconnected(guest, "ws1");

    assertThat(presence.isDisconnected("ABC234", guest)).isFalse();
    verify(publisher, never()).publish(any(), any(), eq("PLAYER_DISCONNECTED"), any());
  }
}
