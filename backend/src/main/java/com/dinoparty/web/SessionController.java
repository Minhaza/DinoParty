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

/** Results of a finished game, read from the database only. */
@RestController
@RequestMapping("/api/v1/sessions")
class SessionController {

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
