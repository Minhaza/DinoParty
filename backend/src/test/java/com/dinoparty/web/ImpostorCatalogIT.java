package com.dinoparty.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Migration V11 (P9-02): both impostor games exist in the catalog, disabled, with the defaults of
 * api-contracts §2.2. Sketchy Saurus has no module before P9-04, so its row is read directly.
 */
@IntegrationTest
class ImpostorCatalogIT {

  @Autowired JdbcTemplate jdbc;

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
  void dinoImpostorRowIsDisabledWithTheContractDefaults() {
    Map<String, Object> row = row("dino-impostor");
    assertThat(row)
        .containsEntry("name", "Dino Impostor")
        .containsEntry("min_players", 4)
        .containsEntry("max_players", 10)
        .containsEntry("enabled", false);
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
  void sketchySaurusRowIsDisabledWithTheContractDefaults() {
    Map<String, Object> row = row("sketchy-saurus");
    assertThat(row)
        .containsEntry("name", "Sketchy Saurus")
        .containsEntry("min_players", 4)
        .containsEntry("max_players", 10)
        .containsEntry("enabled", false);
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
}
