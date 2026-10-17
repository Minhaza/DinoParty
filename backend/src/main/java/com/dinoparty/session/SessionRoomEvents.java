package com.dinoparty.session;

import com.dinoparty.room.RoomEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Keeps the active session consistent with membership. Room events arrive after the room change
 * committed and while the room mailbox is still held, so the roster is updated in commit order. It
 * runs after the realtime bridge (higher order value), so clients see PLAYER_LEFT before any
 * consequence of it.
 */
@Component
class SessionRoomEvents {

  private static final Logger log = LoggerFactory.getLogger(SessionRoomEvents.class);

  private final SessionService sessions;

  SessionRoomEvents(SessionService sessions) {
    this.sessions = sessions;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Order(100)
  void on(RoomEvent event) {
    try {
      switch (event) {
        case RoomEvent.MemberLeft e -> sessions.onMemberLeft(e.roomCode(), e.guestId());
        case RoomEvent.HostChanged e -> sessions.onHostChanged(e.roomCode(), e.hostGuestId());
        case RoomEvent.Closed e -> sessions.onRoomClosed(e.roomCode());
        default -> {}
      }
    } catch (RuntimeException e) {
      log.error("Session could not follow room event {}", event.getClass().getSimpleName(), e);
    }
  }
}
