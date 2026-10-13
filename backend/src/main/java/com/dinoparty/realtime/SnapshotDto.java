package com.dinoparty.realtime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Viewer-specific authoritative state. {@code game} is {@code null} unless a session is running or
 * finished; it is typed {@code Object} because its game-specific fields vary.
 */
public record SnapshotDto(RoomDto room, String serverNow, Seq seq, Object game) {

  /** Last sequence number of each stream that the snapshot already reflects. */
  public record Seq(long room, @JsonProperty("private") long privateStream) {}
}
