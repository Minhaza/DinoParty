package com.dinoparty.realtime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Viewer-specific authoritative state. {@code game} stays {@code null} until a game engine exists
 * (Phase 4); it is typed {@code Object} so game snapshots can slot in without changing this record.
 */
public record SnapshotDto(RoomDto room, String serverNow, Seq seq, Object game) {

  /** Last sequence number of each stream that the snapshot already reflects. */
  public record Seq(long room, @JsonProperty("private") long privateStream) {}
}
