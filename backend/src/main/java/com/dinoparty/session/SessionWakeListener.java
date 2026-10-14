package com.dinoparty.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Runs a scheduler wake-up inside the room mailbox. */
@Component
class SessionWakeListener {

  private static final Logger log = LoggerFactory.getLogger(SessionWakeListener.class);

  private final SessionService sessions;

  SessionWakeListener(SessionService sessions) {
    this.sessions = sessions;
  }

  @EventListener
  void on(DeadlineWake wake) {
    try {
      sessions.wake(wake.roomCode(), wake.sessionInstance());
    } catch (RuntimeException e) {
      log.error("Deadline wake-up failed for room {}", wake.roomCode(), e);
    }
  }
}
