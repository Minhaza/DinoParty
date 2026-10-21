package com.dinoparty.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Single-origin deploy (D49): the backend serves the frontend build without opening the API. */
@IntegrationTest
@TestPropertySource(
    properties = {"app.spa.enabled=true", "app.spa.location=classpath:/spa-fixture/"})
class SpaIT {

  @Autowired MockMvc mvc;

  @Test
  void rootAndClientRoutesServeIndexWithoutAuthentication() throws Exception {
    for (String path : new String[] {"/", "/games", "/join/ABCD", "/rooms/ABCD/play"}) {
      mvc.perform(get(path))
          .andExpect(status().isOk())
          .andExpect(content().string(containsString("spa-fixture")))
          .andExpect(header().string("Cache-Control", containsString("no-cache")));
    }
  }

  @Test
  void fingerprintedAssetsAreServedAndCachedLong() throws Exception {
    mvc.perform(get("/assets/app-abc123.js"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("fixture")))
        .andExpect(header().string("Cache-Control", containsString("max-age=31536000")));
  }

  @Test
  void missingFilesAreNotFoundInsteadOfIndex() throws Exception {
    mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
    mvc.perform(get("/favicon-missing.svg")).andExpect(status().isNotFound());
  }

  @Test
  void apiStaysProtectedAndNeverFallsBackToIndex() throws Exception {
    mvc.perform(get("/api/v1/rooms/ABCD"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(get("/api/v1/no-such-endpoint")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/health")).andExpect(jsonPath("$.status").exists());
  }
}
