package com.dinoparty.guest;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.PostgresTestConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Behind Render's proxy (D49) the per-address limits must see the real client, and a client must
 * not pick its own address. Needs a real Tomcat: the RemoteIpValve does not run under MockMvc. The
 * test connects from 127.0.0.1, which the valve trusts as a proxy, like Render's private-range hop.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfig.class)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {"server.forward-headers-strategy=native", "app.guest.create-limit-per-minute=2"})
class ForwardedClientAddressIT {

  private static final String BODY = "{\"nickname\":\"Proxied\",\"avatarKey\":\"dino-rex\"}";

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void limitsApplyPerForwardedClientAndIgnoreSpoofedLeftHops() throws Exception {
    assertThat(createGuest("198.51.100.7")).isEqualTo(201);
    assertThat(createGuest("198.51.100.7")).isEqualTo(201);
    assertThat(createGuest("198.51.100.7")).isEqualTo(429);

    // A client prepends a fake address; the proxy appends the real one, which is what counts.
    assertThat(createGuest("203.0.113.99, 198.51.100.7")).isEqualTo(429);

    // Another real client is not affected by the first one's limit.
    assertThat(createGuest("198.51.100.8")).isEqualTo(201);
  }

  private int createGuest(String forwardedFor) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/guests"))
            .header("Content-Type", "application/json")
            .header("X-Forwarded-For", forwardedFor)
            .POST(HttpRequest.BodyPublishers.ofString(BODY))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }
}
