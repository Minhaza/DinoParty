package com.dinoparty.realtime;

import com.dinoparty.common.ErrorCode;

/**
 * A rejected STOMP frame. The message is written for clients, so it must never contain client
 * input, tokens or internal detail.
 */
final class RealtimeException extends RuntimeException {

  private final ErrorCode code;

  RealtimeException(ErrorCode code, String safeMessage) {
    super(safeMessage, null, false, false);
    this.code = code;
  }

  ErrorCode code() {
    return code;
  }
}
