package com.dinoparty.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.PostgresTestConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class HealthIT {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean DatabaseProbe probe;

  @Test
  void returns200WithDbUpWithoutAuthentication() throws Exception {
    when(probe.isUp()).thenReturn(true);

    mockMvc
        .perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.db").value("UP"))
        .andExpect(jsonPath("$.version").isNotEmpty())
        .andExpect(jsonPath("$.time").isNotEmpty());
  }

  @Test
  void returns503WithDbDownWhenDatabaseUnreachable() throws Exception {
    when(probe.isUp()).thenReturn(false);

    mockMvc
        .perform(get("/api/v1/health"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("DOWN"))
        .andExpect(jsonPath("$.db").value("DOWN"));
  }

  @Test
  void flywayBaselineCreatesTablesInEmptyDatabase() {
    List<String> tables =
        jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public'",
            String.class);

    assertThat(tables).contains("guest_identity", "guest_session", "game_definition");
  }

  @Test
  void actuatorLivenessAndReadinessAreReachable() throws Exception {
    mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
  }
}
