package com.dinoparty.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
@TestPropertySource(properties = "app.cors.allowed-origins=http://allowed.example")
class SecurityIT {

  @Autowired MockMvc mockMvc;

  @Test
  void unauthenticatedCallToProtectedPathIsProblemJson401() throws Exception {
    mockMvc
        .perform(get("/api/v1/guests/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void unauthenticatedWriteIsDenied() throws Exception {
    mockMvc.perform(post("/api/v1/rooms")).andExpect(status().isUnauthorized());
  }

  @Test
  void actuatorEndpointsOtherThanHealthAreNotPublic() throws Exception {
    mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
  }

  @Test
  void preflightFromAllowedOriginIsAccepted() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/health")
                .header("Origin", "http://allowed.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://allowed.example"));
  }

  @Test
  void preflightFromUnknownOriginIsRejected() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/health")
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }
}
