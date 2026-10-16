package com.dinoparty.web;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.guest.AuthenticatedGuest;
import com.dinoparty.history.SessionHistory;
import com.dinoparty.room.RoomService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Results and Telephone albums of a game, read from the database only. Access is for current
 * members of the session's room (D45); nothing is served while the session runs, so the reveal
 * cannot be bypassed (D43).
 */
@RestController
@RequestMapping("/api/v1/sessions")
class SessionController {

  /** The only game that keeps albums; any other session has none (api-contracts §2). */
  private static final String ALBUM_GAME = "telephone";

  private final SessionHistory history;
  private final RoomService rooms;

  SessionController(SessionHistory history, RoomService rooms) {
    this.history = history;
    this.rooms = rooms;
  }

  @GetMapping("/{sessionId}/results")
  ResponseEntity<Map<String, Object>> results(
      @AuthenticationPrincipal AuthenticatedGuest caller, @PathVariable UUID sessionId) {
    SessionHistory.Results results =
        history
            .results(sessionId)
            .orElseThrow(
                () -> new DomainException(ErrorCode.SESSION_NOT_FOUND, "No such session."));
    if (!rooms.isMemberOfRoomId(caller.guestId(), results.roomId())) {
      throw new DomainException(ErrorCode.NOT_IN_ROOM, "You are not a member of this room.");
    }
    if ("RUNNING".equals(results.status())) {
      throw new DomainException(ErrorCode.RESULTS_NOT_READY, "The results are not ready yet.");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", results.sessionId());
    body.put("gameKey", results.gameKey());
    body.put("status", results.status());
    body.put("endReason", results.endReason());
    body.put("startedAt", results.startedAt());
    body.put("finishedAt", results.finishedAt());
    body.put("standings", results.standings());
    body.put("rounds", rounds(results.rounds()));
    return ResponseEntity.ok(body);
  }

  @GetMapping("/{sessionId}/albums")
  ResponseEntity<Map<String, Object>> albums(
      @AuthenticationPrincipal AuthenticatedGuest caller, @PathVariable UUID sessionId) {
    SessionHistory.SessionHeader session = albumSession(caller, sessionId);
    boolean finished = "FINISHED".equals(session.status());
    List<SessionHistory.AlbumSummary> summaries =
        finished ? history.albumSummaries(sessionId) : List.of();
    List<Map<String, Object>> albums =
        summaries.stream()
            .map(
                a -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("chainIndex", a.chainIndex());
                  m.put("ownerGuestId", a.ownerGuestId());
                  m.put("entryCount", a.entryCount());
                  m.put("entriesPerChain", a.entriesPerChain());
                  m.put("complete", a.entryCount() == a.entriesPerChain());
                  m.put("hasPlaceholders", a.hasPlaceholders());
                  return m;
                })
            .toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", sessionId);
    body.put(
        "complete",
        !albums.isEmpty() && albums.stream().allMatch(a -> Boolean.TRUE.equals(a.get("complete"))));
    body.put("albums", albums);
    return ResponseEntity.ok(body);
  }

  @GetMapping("/{sessionId}/albums/{chainIndex}")
  ResponseEntity<Map<String, Object>> album(
      @AuthenticationPrincipal AuthenticatedGuest caller,
      @PathVariable UUID sessionId,
      @PathVariable int chainIndex) {
    SessionHistory.SessionHeader session = albumSession(caller, sessionId);
    List<SessionHistory.AlbumEntry> entries =
        "FINISHED".equals(session.status()) && chainIndex >= 0
            ? history.album(sessionId, chainIndex)
            : List.of();
    if (entries.isEmpty()) {
      throw new DomainException(ErrorCode.ALBUM_NOT_FOUND, "No such album.");
    }
    int perChain = entries.getFirst().entriesPerChain();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", sessionId);
    body.put("chainIndex", chainIndex);
    body.put(
        "ownerGuestId", entries.getFirst().step() == 0 ? entries.getFirst().authorGuestId() : null);
    body.put("entriesPerChain", perChain);
    body.put("complete", entries.size() == perChain);
    body.put("entries", entries.stream().map(SessionController::entry).toList());
    return ResponseEntity.ok(body);
  }

  /** The session if it is a Telephone session the caller may read and it no longer runs. */
  private SessionHistory.SessionHeader albumSession(AuthenticatedGuest caller, UUID sessionId) {
    SessionHistory.SessionHeader session =
        history
            .header(sessionId)
            .filter(h -> ALBUM_GAME.equals(h.gameKey()))
            .orElseThrow(
                () -> new DomainException(ErrorCode.SESSION_NOT_FOUND, "No such session."));
    if (!rooms.isMemberOfRoomId(caller.guestId(), session.roomId())) {
      throw new DomainException(ErrorCode.NOT_IN_ROOM, "You are not a member of this room.");
    }
    if ("RUNNING".equals(session.status())) {
      throw new DomainException(ErrorCode.RESULTS_NOT_READY, "The albums are not ready yet.");
    }
    return session;
  }

  /** {@code AlbumEntryDto}: text for text entries, strokes for drawings (never null). */
  private static Map<String, Object> entry(SessionHistory.AlbumEntry e) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("step", e.step());
    m.put("kind", e.kind());
    m.put("authorGuestId", e.authorGuestId());
    m.put("authorNickname", e.authorNickname());
    if ("DRAWING".equals(e.kind())) {
      m.put("strokes", e.strokes() == null ? List.of() : e.strokes());
    } else {
      m.put("text", e.text());
    }
    m.put("fill", e.fill());
    m.put("timedOut", e.timedOut());
    m.put("placeholder", e.placeholder());
    return m;
  }

  private static List<Map<String, Object>> rounds(List<SessionHistory.Round> rounds) {
    return rounds.stream()
        .map(
            r -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("roundNo", r.roundNo());
              m.put("startedAt", r.startedAt());
              m.put("endedAt", r.endedAt());
              m.put("endReason", r.endReason());
              m.put("scoreDeltas", r.scoreDeltas());
              m.put("meta", r.meta());
              return m;
            })
        .toList();
  }
}
