package com.dinoparty.history;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read side of the history: results of finished, aborted or interrupted sessions and the albums of
 * the games that keep their players' entries (Telephone).
 */
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

  /** What every read of a session's history checks first: where it belongs and how it ended. */
  public record SessionHeader(UUID sessionId, UUID roomId, String gameKey, String status) {}

  /** One album in the list; no content. {@code ownerGuestId} is the author of entry 0. */
  public record AlbumSummary(
      int chainIndex,
      UUID ownerGuestId,
      int entryCount,
      int entriesPerChain,
      boolean hasPlaceholders) {}

  /** One stored entry; {@code strokes} is the stored stroke list of a drawing, else null. */
  public record AlbumEntry(
      int step,
      String kind,
      UUID authorGuestId,
      String authorNickname,
      String text,
      List<Object> strokes,
      String fill,
      boolean placeholder,
      boolean timedOut,
      int entriesPerChain) {}

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final TypeReference<List<Object>> LIST = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> ROSTER = new TypeReference<>() {};

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

  public Optional<SessionHeader> header(UUID sessionId) {
    return jdbc.sql("SELECT room_id, game_key, status FROM game_session WHERE id = :id")
        .param("id", sessionId)
        .query(
            (rs, i) ->
                new SessionHeader(
                    sessionId,
                    rs.getObject("room_id", UUID.class),
                    rs.getString("game_key"),
                    rs.getString("status")))
        .optional();
  }

  /** The albums of a session in chain order, without their content. */
  public List<AlbumSummary> albumSummaries(UUID sessionId) {
    return jdbc.sql(
            """
            SELECT chain_index, count(*) AS entries, max(entries_per_chain) AS per_chain,
                   bool_or(placeholder) AS placeholders,
                   max(author_guest_id::text) FILTER (WHERE step_no = 0) AS owner
            FROM game_submission WHERE session_id = :id
            GROUP BY chain_index ORDER BY chain_index
            """)
        .param("id", sessionId)
        .query(
            (rs, i) ->
                new AlbumSummary(
                    rs.getInt("chain_index"),
                    rs.getString("owner") == null ? null : UUID.fromString(rs.getString("owner")),
                    rs.getInt("entries"),
                    rs.getInt("per_chain"),
                    rs.getBoolean("placeholders")))
        .list();
  }

  /** One album's entries in step order with the authors' nicknames from the session roster. */
  public List<AlbumEntry> album(UUID sessionId, int chainIndex) {
    Map<String, String> nicknames = nicknames(sessionId);
    return jdbc.sql(
            """
            SELECT step_no, kind, author_guest_id, text, payload, fill, placeholder, timed_out,
                   entries_per_chain
            FROM game_submission WHERE session_id = :id AND chain_index = :chain
            ORDER BY step_no
            """)
        .param("id", sessionId)
        .param("chain", chainIndex)
        .query(
            (rs, i) -> {
              UUID author = rs.getObject("author_guest_id", UUID.class);
              String payload = rs.getString("payload");
              return new AlbumEntry(
                  rs.getInt("step_no"),
                  rs.getString("kind"),
                  author,
                  nicknames.get(author.toString()),
                  rs.getString("text"),
                  payload == null ? null : json.readValue(payload, LIST),
                  rs.getString("fill"),
                  rs.getBoolean("placeholder"),
                  rs.getBoolean("timed_out"),
                  rs.getInt("entries_per_chain"));
            })
        .list();
  }

  private Map<String, String> nicknames(UUID sessionId) {
    String roster =
        jdbc.sql("SELECT roster::text FROM game_session WHERE id = :id")
            .param("id", sessionId)
            .query(String.class)
            .single();
    Map<String, String> nicknames = new HashMap<>();
    for (Map<String, Object> player : json.readValue(roster, ROSTER)) {
      nicknames.put(String.valueOf(player.get("guestId")), String.valueOf(player.get("nickname")));
    }
    return nicknames;
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
