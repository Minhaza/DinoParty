package com.dinoparty.history;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Read side of the history: results of finished, aborted or interrupted sessions. */
@Service
public class SessionHistory {

  public record Standing(
      int rank, UUID guestId, String nickname, int score, Map<String, Object> detail) {}

  public record Round(
      int roundNo,
      Instant startedAt,
      Instant endedAt,
      String endReason,
      Map<String, Object> scoreDeltas,
      Map<String, Object> meta) {}

  public record Results(
      UUID sessionId,
      UUID roomId,
      String gameKey,
      String status,
      String endReason,
      Instant startedAt,
      Instant finishedAt,
      List<Standing> standings,
      List<Round> rounds) {}

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final JsonMapper json;

  SessionHistory(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Optional<Results> results(UUID sessionId) {
    return jdbc.sql(
            "SELECT room_id, game_key, status, end_reason, started_at, finished_at"
                + " FROM game_session WHERE id = :id")
        .param("id", sessionId)
        .query(
            (rs, i) ->
                new Results(
                    sessionId,
                    rs.getObject("room_id", UUID.class),
                    rs.getString("game_key"),
                    rs.getString("status"),
                    rs.getString("end_reason"),
                    rs.getTimestamp("started_at").toInstant(),
                    rs.getTimestamp("finished_at") == null
                        ? null
                        : rs.getTimestamp("finished_at").toInstant(),
                    List.of(),
                    List.of()))
        .optional()
        .map(this::withDetails);
  }

  private Results withDetails(Results base) {
    List<Standing> standings =
        jdbc.sql(
                "SELECT guest_id, nickname, rank, score, detail FROM game_result"
                    + " WHERE session_id = :id ORDER BY rank, nickname")
            .param("id", base.sessionId())
            .query(
                (rs, i) ->
                    new Standing(
                        rs.getInt("rank"),
                        rs.getObject("guest_id", UUID.class),
                        rs.getString("nickname"),
                        rs.getInt("score"),
                        readMap(rs.getString("detail"))))
            .list();
    List<Round> rounds =
        jdbc.sql(
                "SELECT round_no, started_at, ended_at, end_reason, score_deltas, meta"
                    + " FROM game_round WHERE session_id = :id ORDER BY round_no")
            .param("id", base.sessionId())
            .query(
                (rs, i) ->
                    new Round(
                        rs.getInt("round_no"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("ended_at").toInstant(),
                        rs.getString("end_reason"),
                        readMap(rs.getString("score_deltas")),
                        readMap(rs.getString("meta"))))
            .list();
    return new Results(
        base.sessionId(),
        base.roomId(),
        base.gameKey(),
        base.status(),
        base.endReason(),
        base.startedAt(),
        base.finishedAt(),
        standings,
        rounds);
  }

  private Map<String, Object> readMap(String value) {
    return value == null ? Map.of() : json.readValue(value, MAP);
  }
}
