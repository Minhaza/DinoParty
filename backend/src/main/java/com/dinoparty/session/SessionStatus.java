package com.dinoparty.session;

enum SessionStatus {
  /** Countdown running; the roster is not frozen yet. */
  STARTING,
  RUNNING,
  /** Game over, waiting for rematch votes or the results timeout. */
  FINISHED
}
