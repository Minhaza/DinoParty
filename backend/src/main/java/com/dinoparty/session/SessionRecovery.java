package com.dinoparty.session;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Marks games lost to a restart as interrupted and unsticks their rooms. */
@Component
class SessionRecovery {

  private static final Logger log = LoggerFactory.getLogger(SessionRecovery.class);

  private final SessionRecorder recorder;
  private final RoomLifecycle rooms;
  private final Clock clock;

  SessionRecovery(SessionRecorder recorder, RoomLifecycle rooms, Clock clock) {
    this.recorder = recorder;
    this.rooms = rooms;
    this.clock = clock;
  }

  void recover() {
    int sessions = recorder.interruptRunning(clock.instant());
    int resetRooms = rooms.resetAfterRestart();
    if (sessions > 0 || resetRooms > 0) {
      log.warn(
          "Restart recovery: {} session(s) marked INTERRUPTED, {} room(s) returned to WAITING",
          sessions,
          resetRooms);
    }
  }
}
