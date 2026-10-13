package com.dinoparty.realtime;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.room.CreateRoomCommand;
import com.dinoparty.session.SessionService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Shared setup of the engine integration tests: tables of connected players of the test game. */
abstract class GameEngineSupport extends RealtimeFixture {

  @Autowired protected MockMvc mvc;
  @Autowired protected SessionService sessions;
  @Autowired protected JdbcTemplate jdbc;

  @AfterEach
  void tearDown() {
    closeClients();
  }

  /** A room of the test game with {@code players} connected members; index 0 is the host. */
  protected final class Table {
    final List<Guest> guests = new ArrayList<>();
    final List<StompTestClient> sockets = new ArrayList<>();
    final String code;

    Table(int players) {
      Guest host = newGuest();
      code = roomService.create(host.id(), new CreateRoomCommand("test-game", null, null)).code();
      guests.add(host);
      for (int i = 1; i < players; i++) {
        Guest guest = newGuest();
        roomService.join(guest.id(), code);
        guests.add(guest);
      }
      guests.forEach(g -> sockets.add(connectAndSubscribe(g, code)));
    }

    StompTestClient host() {
      return sockets.get(0);
    }

    int indexOf(String guestId) {
      for (int i = 0; i < guests.size(); i++) {
        if (guests.get(i).id().toString().equals(guestId)) {
          return i;
        }
      }
      throw new AssertionError("unknown guest " + guestId);
    }

    void start() {
      host().command(code, "START_GAME", actionId(), "{}");
      host().awaitType("ACTION_ACK");
    }

    void awaitStatus(StompTestClient ws, String status, String reason) {
      ws.awaitMessage(
          m ->
              m.type().equals("ROOM_STATUS_CHANGED")
                  && m.payload().get("status").asString().equals(status)
                  && m.payload().get("reason").asString().equals(reason));
    }

    String phaseId(StompTestClient ws) {
      return ws.ofType("PHASE_STARTED").getLast().payload().get("phaseId").asString();
    }

    void send(StompTestClient ws, String type, String phaseId, String payload, String actionId) {
      String phase = phaseId == null ? "null" : "\"" + phaseId + "\"";
      ws.command(
          code,
          "{\"type\":\"%s\",\"actionId\":\"%s\",\"phaseId\":%s,\"payload\":%s}"
              .formatted(type, actionId, phase, payload));
    }

    StompTestClient.Message snapshotFor(StompTestClient ws) {
      int before = ws.ofType("ROOM_SNAPSHOT").size();
      ws.command(code, "REQUEST_SNAPSHOT", actionId(), "{}");
      await().atMost(Duration.ofSeconds(5)).until(() -> ws.ofType("ROOM_SNAPSHOT").size() > before);
      return ws.ofType("ROOM_SNAPSHOT").getLast();
    }
  }

  protected static String bearer(Guest guest) {
    return "Bearer " + guest.token();
  }

  protected static String code(StompTestClient.Message error) {
    return error.payload().get("code").asString();
  }
}
