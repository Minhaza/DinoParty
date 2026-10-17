package com.dinoparty.session;

import com.dinoparty.engine.RoundRecord;
import com.dinoparty.engine.SubmissionRecord;
import java.time.Instant;
import java.util.UUID;

/**
 * Port to durable history. {@link #sessionStarted} is synchronous because every other record
 * references its row; everything else is handed over in memory and written later, so a database
 * hiccup never stalls a live room.
 */
public interface SessionRecorder {

  /** Inserts the RUNNING row; throws if it cannot be stored (the start then fails). */
  void sessionStarted(SessionStart start);

  void roundCompleted(UUID sessionId, RoundRecord round);

  /**
   * Stores one accepted entry. Never dropped (retried like results) and written before any later
   * {@link #sessionFinished} of the same session, so a FINISHED session always has its entries.
   */
  void submissionAccepted(UUID sessionId, SubmissionRecord submission);

  void sessionFinished(SessionResult result);

  /** Marks the session ABORTED with a reason (no results). */
  void sessionAborted(UUID sessionId, String reason, Instant at);

  /** Marks every RUNNING session INTERRUPTED (restart recovery); returns how many. */
  int interruptRunning(Instant at);
}
