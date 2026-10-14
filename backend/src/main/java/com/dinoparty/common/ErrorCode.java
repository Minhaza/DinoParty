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
  NOT_ENOUGH_PLAYERS(HttpStatus.CONFLICT, "Not enough players"),
  WORD_POOL_TOO_SMALL(HttpStatus.CONFLICT, "Not enough words"),
  GAME_NOT_FOUND(HttpStatus.NOT_FOUND, "Game not found"),
  SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "Session not found"),
  RESULTS_NOT_READY(HttpStatus.CONFLICT, "Results are not ready"),
  STALE_ACTION(HttpStatus.CONFLICT, "Stale action"),
  INVALID_PHASE(HttpStatus.CONFLICT, "Not allowed in the current phase"),
  INVALID_ACTION(HttpStatus.BAD_REQUEST, "Action not supported"),
  DUPLICATE_ACTION(HttpStatus.CONFLICT, "Duplicate action"),
  NOT_DRAWER(HttpStatus.FORBIDDEN, "Only the drawer can do this"),
  IS_DRAWER(HttpStatus.FORBIDDEN, "The drawer cannot do this"),
  WORD_PACK_NOT_FOUND(HttpStatus.NOT_FOUND, "Word pack not found"),
  WORD_PACK_LIMIT_REACHED(HttpStatus.CONFLICT, "Word pack limit reached"),
  IMPORT_STALE(HttpStatus.CONFLICT, "Import preview is out of date"),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported file type"),
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
