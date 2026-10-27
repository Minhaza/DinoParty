package com.dinoparty.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.ApiTestSupport;
import com.dinoparty.ApiTestSupport.TestGuest;
import com.dinoparty.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Migrations V11 (P9-02) and V12 (P9-08): both impostor games exist in the catalog, enabled, with
 * the defaults of api-contracts §2.2.
 */
@IntegrationTest
class ImpostorCatalogIT {

  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  private Map<String, Object> row(String key) {
    return jdbc.queryForMap(
        "SELECT name, min_players, max_players, enabled, default_settings::text AS settings"
            + " FROM game_definition WHERE key = ?",
        key);
  }

  private static Map<String, Object> settings(Map<String, Object> row) {
    return JsonMapper.builder()
        .build()
        .readValue((String) row.get("settings"), new TypeReference<Map<String, Object>>() {});
  }

  @Test
  void dinoImpostorRowIsEnabledWithTheContractDefaults() {
    Map<String, Object> row = row("dino-impostor");
    assertThat(row)
        .containsEntry("name", "Dino Impostor")
        .containsEntry("min_players", 4)
        .containsEntry("max_players", 10)
        .containsEntry("enabled", true);
    assertThat(settings(row))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "rounds", 0,
                "laps", 2,
                "turnSeconds", 25,
                "voteSeconds", 60,
                "guessSeconds", 30,
                "difficulty", "mixed"));
  }

  @Test
  void sketchySaurusRowIsEnabledWithTheContractDefaults() {
    Map<String, Object> row = row("sketchy-saurus");
    assertThat(row)
        .containsEntry("name", "Sketchy Saurus")
        .containsEntry("min_players", 4)
        .containsEntry("max_players", 10)
        .containsEntry("enabled", true);
    assertThat(settings(row))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "rounds", 0,
                "voteSeconds", 60,
                "guessSeconds", 30,
                "difficulty", "mixed",
                "sketchSeconds", 60,
                "impostorKnows", true,
                "unawareGuess", false));
  }

  /** The module owns the defaults; the catalog row keeps a copy that must not drift. */
  @Test
  void dinoImpostorIsListedEnabledAndItsModuleDefaultsEqualTheRow() throws Exception {
    mvc.perform(get("/api/v1/games/dino-impostor"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.defaultSettings.rounds").value(0))
        .andExpect(jsonPath("$.defaultSettings.laps").value(2))
        .andExpect(jsonPath("$.defaultSettings.turnSeconds").value(25))
        .andExpect(jsonPath("$.defaultSettings.voteSeconds").value(60))
        .andExpect(jsonPath("$.defaultSettings.guessSeconds").value(30))
        .andExpect(jsonPath("$.defaultSettings.difficulty").value("mixed"));
    // Same values as the row, key for key.
    mvc.perform(get("/api/v1/games/dino-impostor"))
        .andExpect(
            jsonPath("$.defaultSettings.length()").value(settings(row("dino-impostor")).size()));
  }

  @Test
  void anEnabledImpostorGameCanHostARoom() throws Exception {
    ApiTestSupport api = new ApiTestSupport(mvc);
    TestGuest host = api.createGuest(ApiTestSupport.unique("Imp"));
    api.request(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/rooms"),
            host,
            "{\"gameKey\":\"dino-impostor\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.gameKey").value("dino-impostor"));
  }
}
