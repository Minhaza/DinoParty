package com.dinoparty.common;

import org.springframework.http.HttpStatus;

/** Domain error codes from docs/api-contracts.md §1 together with their HTTP status and title. */
public enum ErrorCode {
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
  FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
  NOT_HOST(HttpStatus.FORBIDDEN, "Only the host can do this"),
  ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "Room not found"),
  ROOM_FULL(HttpStatus.CONFLICT, "Room is full"),
  ROOM_IN_PROGRESS(HttpStatus.CONFLICT, "Room is already in progress"),
  ROOM_CLOSED(HttpStatus.CONFLICT, "Room is closed"),
  ALREADY_IN_ROOM(HttpStatus.CONFLICT, "Already in a room"),
  NOT_IN_ROOM(HttpStatus.FORBIDDEN, "Not a member of this room"),
  GAME_NOT_FOUND(HttpStatus.NOT_FOUND, "Game not found"),
  INVALID_PHASE(HttpStatus.CONFLICT, "Not allowed in the current phase"),
  INVALID_ACTION(HttpStatus.BAD_REQUEST, "Action not supported"),
  DUPLICATE_ACTION(HttpStatus.CONFLICT, "Duplicate action"),
  PAYLOAD_TOO_LARGE(HttpStatus.valueOf(413), "Payload too large"),
  SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error"),
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests");

  private final HttpStatus status;
  private final String title;

  ErrorCode(HttpStatus status, String title) {
    this.status = status;
    this.title = title;
  }

  public HttpStatus status() {
    return status;
  }

  public String title() {
    return title;
  }
}
