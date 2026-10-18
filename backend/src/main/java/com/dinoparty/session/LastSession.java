package com.dinoparty.session;

import java.time.Instant;
import java.util.UUID;

/**
 * The newest session of a room that is no longer running ({@code RoomDto.lastSession}).
 *
 * @param status {@code FINISHED}, {@code ABORTED} or {@code INTERRUPTED} (the stored statuses)
 * @param endReason the finish reason, the abort reason, or {@code SERVER_RESTART}
 */
public record LastSession(UUID sessionId, String status, String endReason, Instant finishedAt) {}
