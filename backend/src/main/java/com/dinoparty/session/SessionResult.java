package com.dinoparty.session;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SessionResult(
    UUID sessionId, String endReason, Instant finishedAt, List<Standing> standings) {

  /** Nickname is a snapshot: guests may rename or be purged later. */
  public record Standing(
      int rank, UUID guestId, String nickname, int score, Map<String, Object> detail) {}
}
