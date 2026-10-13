package com.dinoparty.guest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@TestPropertySource(properties = "app.guest.create-limit-per-minute=3")
class GuestRateLimitIT {

  @Autowired MockMvc mvc;

  @Test
  void guestCreationIsThrottledPerClientAddress() throws Exception {
    String body = "{\"nickname\":\"Limited\",\"avatarKey\":\"dino-rex\"}";
    for (int i = 0; i < 3; i++) {
      mvc.perform(post("/api/v1/guests").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isCreated());
    }

    mvc.perform(post("/api/v1/guests").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }
}
