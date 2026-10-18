package com.dinoparty.history;

import com.dinoparty.engine.PlayerInfo;
import com.dinoparty.engine.RoundRecord;
import com.dinoparty.engine.SubmissionRecord;
import com.dinoparty.session.SessionRecorder;
import com.dinoparty.session.SessionResult;
import com.dinoparty.session.SessionStart;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Durable record of sessions, rounds and results.
 *
 * <p>The RUNNING row is inserted synchronously (everything else references it and a start without a
 * durable id is refused). Everything else is queued in memory and written by one background thread
 * with retry and backoff, so a database outage never blocks a live room. Round records may be
 * dropped (and counted) when the queue is full; submissions, results and end markers never are. One
 * writer in queue order means a session's submissions are stored before its FINISHED status.
 */
@Component
class JdbcSessionRecorder implements SessionRecorder {

  private static final Logger log = LoggerFactory.getLogger(JdbcSessionRecorder.class);

  private record Task(String name, boolean critical, Runnable write) {}

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final HistoryProperties properties;
  private final MeterRegistry meters;
  private final TransactionTemplate tx;
  private final List<Task> queue = new ArrayList<>();
  private final Thread writer;
  private boolean stopping;

  JdbcSessionRecorder(
      JdbcClient jdbc,
      JsonMapper json,
      HistoryProperties properties,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.json = json;
    this.properties = properties;
    this.meters = meters;
    this.tx = new TransactionTemplate(transactionManager);
    meters.gauge(
        "dinoparty.history.queue.size",
        queue,
        q -> {
          synchronized (q) {
            return q.size();
          }
        });
    this.writer = Thread.ofPlatform().name("history-writer").daemon(true).start(this::drainLoop);
  }

  @Override
  public void sessionStarted(SessionStart start) {
    jdbc.sql(
            """
            INSERT INTO game_session (id, room_id, game_key, session_number, status, settings,
                                      roster, seed, started_at)
            VALUES (:id, :room, :game,
                    (SELECT COALESCE(MAX(session_number), 0) + 1 FROM game_session WHERE room_id = :room),
                    'RUNNING', CAST(:settings AS jsonb), CAST(:roster AS jsonb), :seed, :startedAt)
            """)
        .param("id", start.sessionId())
        .param("room", start.roomId())
        .param("game", start.gameKey())
        .param("settings", toJson(start.settings()))
        .param("roster", toJson(roster(start.roster())))
        .param("seed", start.seed())
        .param("startedAt", java.sql.Timestamp.from(start.startedAt()))
        .update();
  }

  @Override
  public void roundCompleted(UUID sessionId, RoundRecord round) {
    Map<String, Integer> deltas = new LinkedHashMap<>();
    round.scoreDeltas().forEach((g, p) -> deltas.put(g.toString(), p));
    enqueue(
        new Task(
            "round",
            false,
            () ->
                jdbc.sql(
                        """
                        INSERT INTO game_round (id, session_id, round_no, started_at, ended_at,
                                                end_reason, score_deltas, meta)
                        VALUES (:id, :session, :no, :startedAt, :endedAt, :reason,
                                CAST(:deltas AS jsonb), CAST(:meta AS jsonb))
                        ON CONFLICT (session_id, round_no) DO NOTHING
                        """)
                    .param("id", UUID.randomUUID())
                    .param("session", sessionId)
                    .param("no", round.roundNo())
                    .param("startedAt", java.sql.Timestamp.from(round.startedAt()))
                    .param("endedAt", java.sql.Timestamp.from(round.endedAt()))
                    .param("reason", round.endReason())
                    .param("deltas", toJson(deltas))
                    .param("meta", toJson(round.meta()))
                    .update()));
  }

  /** Critical like results: an entry is part of an album, so it is retried and never dropped. */
  @Override
  public void submissionAccepted(UUID sessionId, SubmissionRecord submission) {
    String payload = submission.strokes() == null ? null : toJson(submission.strokes());
    enqueue(
        new Task(
            "submission",
            true,
            () ->
                jdbc.sql(
                        """
                        INSERT INTO game_submission (id, session_id, chain_index, step_no,
                                                     author_guest_id, kind, text, payload, fill,
                                                     placeholder, timed_out, entries_per_chain,
                                                     submitted_at)
                        VALUES (:id, :session, :chain, :step, :author, :kind, :text,
                                CAST(:payload AS jsonb), :fill, :placeholder, :timedOut, :entries,
                                :at)
                        ON CONFLICT (session_id, chain_index, step_no) DO NOTHING
                        """)
                    .param("id", UUID.randomUUID())
                    .param("session", sessionId)
                    .param("chain", submission.chainIndex())
                    .param("step", submission.stepNo())
                    .param("author", submission.authorGuestId())
                    .param("kind", submission.kind().name())
                    .param("text", submission.text())
                    .param("payload", payload)
                    .param("fill", submission.fill())
                    .param("placeholder", submission.placeholder())
                    .param("timedOut", submission.timedOut())
                    .param("entries", submission.entriesPerChain())
                    .param("at", java.sql.Timestamp.from(submission.submittedAt()))
                    .update()));
  }

  @Override
  public void sessionFinished(SessionResult result) {
    enqueue(
        new Task(
            "result",
            true,
            () ->
                tx.executeWithoutResult(
                    status -> {
                      for (SessionResult.Standing row : result.standings()) {
                        jdbc.sql(
                                """
                                INSERT INTO game_result (id, session_id, guest_id, nickname, rank,
                                                         score, detail)
                                VALUES (:id, :session, :guest, :nickname, :rank, :score,
                                        CAST(:detail AS jsonb))
                                ON CONFLICT (session_id, guest_id) DO NOTHING
                                """)
                            .param("id", UUID.randomUUID())
                            .param("session", result.sessionId())
                            .param("guest", row.guestId())
                            .param("nickname", row.nickname())
                            .param("rank", row.rank())
                            .param("score", row.score())
                            .param("detail", toJson(row.detail()))
                            .update();
                      }
                      jdbc.sql(
                              """
                              UPDATE game_session SET status = 'FINISHED', end_reason = :reason,
                                     finished_at = :at
                              WHERE id = :id AND status = 'RUNNING'
                              """)
                          .param("id", result.sessionId())
                          .param("reason", result.endReason())
                          .param("at", java.sql.Timestamp.from(result.finishedAt()))
                          .update();
                    })));
  }

  @Override
  public void sessionAborted(UUID sessionId, String reason, Instant at) {
    enqueue(
        new Task(
            "abort",
            true,
            () ->
                jdbc.sql(
                        """
                        UPDATE game_session SET status = 'ABORTED', end_reason = :reason,
                               finished_at = :at
                        WHERE id = :id AND status = 'RUNNING'
                        """)
                    .param("id", sessionId)
                    .param("reason", reason)
                    .param("at", java.sql.Timestamp.from(at))
                    .update()));
  }

  @Override
  public int interruptRunning(Instant at) {
    return jdbc.sql(
            """
            UPDATE game_session SET status = 'INTERRUPTED', end_reason = 'SERVER_RESTART',
                   finished_at = :at
            WHERE status = 'RUNNING'
            """)
        .param("at", java.sql.Timestamp.from(at))
        .update();
  }

  /** Number of writes still waiting; tests poll it. */
  int pending() {
    synchronized (queue) {
      return queue.size();
    }
  }

  private void enqueue(Task task) {
    synchronized (queue) {
      if (!task.critical() && queue.size() >= properties.queueCapacity()) {
        // Drop the oldest non-critical write to make room; results are never dropped.
        Iterator<Task> it = queue.iterator();
        while (it.hasNext()) {
          if (!it.next().critical()) {
            it.remove();
            meters.counter("dinoparty.history.dropped").increment();
            log.error("History queue full; dropped an old {} record", task.name());
            break;
          }
        }
      }
      queue.add(task);
      queue.notifyAll();
    }
  }

  private void drainLoop() {
    while (true) {
      Task task;
      synchronized (queue) {
        while (queue.isEmpty()) {
          if (stopping) {
            return;
          }
          try {
            queue.wait(500);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
        }
        task = queue.getFirst();
      }
      write(task);
      synchronized (queue) {
        queue.remove(task);
        queue.notifyAll();
      }
    }
  }

  private void write(Task task) {
    long backoff = properties.retryInitial().toMillis();
    for (int attempt = 1; ; attempt++) {
      try {
        task.write().run();
        return;
      } catch (RuntimeException e) {
        boolean giveUp = !task.critical() && attempt >= properties.maxAttempts();
        if (giveUp || (stopping && attempt >= 2)) {
          meters.counter("dinoparty.history.dropped").increment();
          log.error("Giving up on history write {} after {} attempts", task.name(), attempt, e);
          return;
        }
        log.warn("History write {} failed (attempt {}); retrying", task.name(), attempt);
        try {
          Thread.sleep(backoff);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
        backoff = Math.min(backoff * 2, properties.retryMax().toMillis());
      }
    }
  }

  /** Lets queued writes finish (bounded) before the application exits. */
  @PreDestroy
  void drainOnShutdown() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    synchronized (queue) {
      stopping = true;
      queue.notifyAll();
      while (!queue.isEmpty() && System.nanoTime() < deadline) {
        queue.wait(200);
      }
    }
  }

  private List<Map<String, String>> roster(List<PlayerInfo> players) {
    List<Map<String, String>> out = new ArrayList<>();
    for (PlayerInfo p : players) {
      Map<String, String> m = new LinkedHashMap<>();
      m.put("guestId", p.guestId().toString());
      m.put("nickname", p.nickname());
      m.put("avatarKey", p.avatarKey());
      m.put("avatarColor", p.avatarColor());
      out.add(m);
    }
    return out;
  }

  private String toJson(Object value) {
    return json.writeValueAsString(value);
  }
}
