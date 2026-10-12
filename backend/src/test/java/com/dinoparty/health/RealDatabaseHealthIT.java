package com.dinoparty.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Exercises the real probe against a real PostgreSQL (no mocks). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class RealDatabaseHealthIT {

  @Autowired MockMvc mockMvc;

  @Test
  void healthReportsDbUpAgainstRealDatabase() throws Exception {
    mockMvc
        .perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.db").value("UP"));
  }
}
