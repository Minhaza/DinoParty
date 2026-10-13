package com.dinoparty.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Own context: the handshake limit is per client address and would starve other tests. */
@RealtimeIntegrationTest
@TestPropertySource(properties = "app.realtime.connect-limit-per-minute=3")
class RealtimeHandshakeLimitIT extends RealtimeFixture {

  @Test
  void tooManyHandshakesFromOneAddressAreRefusedWith429AndRetryAfter() {
    for (int i = 0; i < 3; i++) {
      StompTestClient.open(port, null).close();
    }

    assertThatThrownBy(() -> StompTestClient.open(port, null))
        .isInstanceOfSatisfying(
            StompTestClient.HandshakeRejected.class,
            e -> {
              assertThat(e.status).isEqualTo(429);
              assertThat(Long.parseLong(e.retryAfter)).isPositive();
            });
  }
}
