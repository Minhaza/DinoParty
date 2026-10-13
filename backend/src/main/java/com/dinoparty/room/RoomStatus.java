package com.dinoparty.room;

/** Room lifecycle from docs/architecture.md §3.3. Only WAITING and CLOSED occur before Phase 4. */
public enum RoomStatus {
  WAITING,
  STARTING,
  PLAYING,
  RESULTS,
  CLOSED
}
