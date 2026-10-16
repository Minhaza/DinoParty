package com.dinoparty.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One accepted (or server-filled) entry of a game that keeps its players' work, persisted the
 * moment it exists and never dropped (Telephone, docs/telephone-design.md §12.1, D44). It is
 * content: the engine only hands it to the recorder and never publishes it.
 *
 * @param chainIndex the chain (album) the entry belongs to
 * @param stepNo the 0-based position of the entry in its chain
 * @param text the text of a {@link Kind#TEXT} entry, else null
 * @param strokes the stroke list (§2.1 wire form) of a {@link Kind#DRAWING} entry, else null
 * @param fill how the cell got its entry ({@code NONE|TIMEOUT|LEFT_PLAYER|AUTO_PROMPT})
 * @param entriesPerChain the session's number of entries per chain, repeated on every record
 */
public record SubmissionRecord(
    int chainIndex,
    int stepNo,
    UUID authorGuestId,
    Kind kind,
    String text,
    List<Map<String, Object>> strokes,
    String fill,
    boolean placeholder,
    boolean timedOut,
    int entriesPerChain,
    Instant submittedAt) {

  public enum Kind {
    TEXT,
    DRAWING
  }

  public SubmissionRecord {
    strokes = strokes == null ? null : List.copyOf(strokes);
  }
}
