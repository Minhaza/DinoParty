package com.dinoparty.realtime;

import com.dinoparty.common.Timestamps;
import com.dinoparty.session.LastSession;
import java.util.UUID;

/** Wire shape of {@code RoomDto.lastSession} (api-contracts §2.1). */
public record LastSessionDto(UUID sessionId, String status, String endReason, String finishedAt) {

  static LastSessionDto from(LastSession last) {
    return last == null
        ? null
        : new LastSessionDto(
            last.sessionId(),
            last.status(),
            last.endReason(),
            last.finishedAt() == null ? null : Timestamps.format(last.finishedAt()));
  }
}
