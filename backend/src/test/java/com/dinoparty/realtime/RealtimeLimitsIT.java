package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Rate limits on handshakes, failed authentication and commands, with tight limits. */
@RealtimeIntegrationTest
@TestPropertySource(
    properties = {
      "app.realtime.command-limit-per-10s=5",
      "app.realtime.connect-limit-per-minute=40",
      "app.realtime.auth-failure-limit-per-minute=3"
    })
class RealtimeLimitsIT extends RealtimeFixture {

  @AfterEach
  void tearDown() {
    closeClients();
  }

  @Test
  void commandFloodsAreRateLimitedAndNotExecuted() {
    Guest host = newGuest();
    String code = newRoom(host).code();
    StompTestClient ws = connect(host);
    ws.subscribeRoom(code);

    // 12 commands against a limit of 5 per window; the first 5 are accepted.
    for (int i = 0; i < 12; i++) {
      ws.command(code, "SET_READY", actionId(), "{\"ready\":%s}".formatted(i % 2 == 0));
    }

    await().atMost(Duration.ofSeconds(5)).until(() -> ws.ofType("ERROR").size() == 7);
    assertThat(ws.ofType("ACTION_ACK")).hasSize(5);
    StompTestClient.Message limited = ws.ofType("ERROR").getFirst();
    assertThat(limited.destination()).isEqualTo("/user/queue/errors");
    assertThat(limited.payload().get("code").asString()).isEqualTo("RATE_LIMITED");
    assertThat(limited.payload().get("retryable").asBoolean()).isTrue();
    // Five alternating commands (true,false,true,false,true) were applied; the rest were not.
    assertThat(roomService.getForMember(host.id(), code).members().getFirst().ready()).isTrue();
    assertThat(ws.ofType("READY_UPDATED")).hasSize(5);
  }

  @Test
  void rateLimitsArePerGuest() {
    Guest alice = newGuest();
    Guest bob = newGuest();
    String code = newRoom(alice).code();
    roomService.join(bob.id(), code);
    StompTestClient aliceWs = connect(alice);
    aliceWs.subscribeRoom(code);
    StompTestClient bobWs = connect(bob);
    bobWs.subscribeRoom(code);
    for (int i = 0; i < 10; i++) {
      aliceWs.command(code, "SET_READY", actionId(), "{\"ready\":true}");
    }
    await().atMost(Duration.ofSeconds(5)).until(() -> !aliceWs.ofType("ERROR").isEmpty());

    bobWs.command(code, "SET_READY", actionId(), "{\"ready\":true}");

    bobWs.awaitType("ACTION_ACK");
    assertThat(bobWs.ofType("ERROR")).isEmpty();
  }

  @Test
  void repeatedAuthenticationFailuresAreThrottled() {
    String lastCode = null;
    for (int i = 0; i < 5; i++) {
      StompTestClient client = StompTestClient.open(port, null);
      clients.add(client);
      client.sendFrame("CONNECT", StompTestClient.connectHeaders("garbage-" + i), null);
      lastCode = client.awaitFrame(f -> f.command().equals("ERROR")).header("code");
      client.awaitClose();
    }

    assertThat(lastCode).isEqualTo("RATE_LIMITED");
    // A valid credential still works while failures are being throttled.
    StompTestClient ok = connect(newGuest());
    assertThat(ok.frames()).anyMatch(f -> f.command().equals("CONNECTED"));
  }
}
