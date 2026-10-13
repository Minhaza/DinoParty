package com.dinoparty.room;

/** Why a member is no longer in a room; mirrors {@code PLAYER_LEFT.reason} in the contract. */
public enum LeaveReason {
  LEFT,
  KICKED,
  TIMEOUT
}
