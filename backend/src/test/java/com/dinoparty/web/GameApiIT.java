package com.dinoparty.web;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.ApiTestSupport;
import com.dinoparty.ApiTestSupport.TestGuest;
import com.dinoparty.IntegrationTest;
import com.dinoparty.games.testgame.TestGameModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Public game discovery, generated from the registered game modules (api-contracts §2.6). */
@IntegrationTest
class GameApiIT {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void enableEveryGame() {
    jdbc.update("UPDATE game_definition SET enabled = true");
  }

  @Test
  void listsEveryRegisteredGameInKeyOrderWithoutAuthentication() throws Exception {
    mvc.perform(get("/api/v1/games"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].key").value("draw-guess"))
        .andExpect(jsonPath("$[0].name").value("Draw & Guess"))
        .andExpect(jsonPath("$[0].tagline").value("Easy to learn • High energy"))
        .andExpect(jsonPath("$[0].minPlayers").value(2))
        .andExpect(jsonPath("$[0].maxPlayers").value(10))
        .andExpect(jsonPath("$[0].iconKey").value("draw-guess"))
        .andExpect(jsonPath("$[0].enabled").value(true))
        .andExpect(jsonPath("$[0].settingsSchema").doesNotExist())
        .andExpect(jsonPath("$[1].key").value("telephone"))
        .andExpect(jsonPath("$[1].name").value("Dino Telephone"))
        .andExpect(jsonPath("$[1].minPlayers").value(4))
        .andExpect(jsonPath("$[1].maxPlayers").value(8))
        .andExpect(jsonPath("$[1].enabled").value(true));
  }

  /**
   * The extensibility check of P7-01: the test-only game exists only as a module (test scope) and
   * its availability row, yet it is discovered with its metadata like the shipped games.
   */
  @Test
  void aGameRegistersItselfThroughItsModuleAlone() throws Exception {
    mvc.perform(get("/api/v1/games"))
        .andExpect(jsonPath("$[2].key").value(TestGameModule.KEY))
        .andExpect(jsonPath("$[2].name").value("Test Game"))
        .andExpect(jsonPath("$[2].minPlayers").value(2))
        .andExpect(jsonPath("$[2].maxPlayers").value(6));
    mvc.perform(get("/api/v1/games/" + TestGameModule.KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settingsSchema.length()").value(0))
        .andExpect(jsonPath("$.howToPlay[0]").value("Score points until the last round ends."));
  }

  @Test
  void drawGuessDetailCarriesRulesSettingsAndDefaults() throws Exception {
    mvc.perform(get("/api/v1/games/draw-guess"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.key").value("draw-guess"))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.description").isNotEmpty())
        .andExpect(jsonPath("$.howToPlay.length()").value(4))
        .andExpect(jsonPath("$.settingsSchema.length()").value(8))
        .andExpect(jsonPath("$.settingsSchema[0].key").value("cycles"))
        .andExpect(jsonPath("$.settingsSchema[0].label").value("Turns per player"))
        .andExpect(jsonPath("$.settingsSchema[0].type").value("int"))
        .andExpect(jsonPath("$.settingsSchema[0].min").value(1))
        .andExpect(jsonPath("$.settingsSchema[0].max").value(5))
        .andExpect(jsonPath("$.settingsSchema[0].default").value(2))
        .andExpect(jsonPath("$.settingsSchema[0].autoValue").doesNotExist())
        .andExpect(jsonPath("$.settingsSchema[0].options").doesNotExist())
        .andExpect(jsonPath("$.settingsSchema[3].key").value("hintReveal"))
        .andExpect(jsonPath("$.settingsSchema[3].type").value("bool"))
        .andExpect(jsonPath("$.settingsSchema[3].options").doesNotExist())
        .andExpect(jsonPath("$.settingsSchema[4].key").value("difficulty"))
        .andExpect(jsonPath("$.settingsSchema[4].type").value("enum"))
        .andExpect(jsonPath("$.settingsSchema[4].options[0]").value("easy"))
        .andExpect(jsonPath("$.settingsSchema[4].default").value("mixed"))
        .andExpect(jsonPath("$.settingsSchema[6].key").value("wordCategories"))
        .andExpect(jsonPath("$.settingsSchema[6].type").value("list"))
        .andExpect(jsonPath("$.settingsSchema[6].itemFormat").value("category-key"))
        .andExpect(jsonPath("$.settingsSchema[6].maxItems").value(20))
        .andExpect(jsonPath("$.settingsSchema[6].exclusiveOption").value("random-mix"))
        .andExpect(jsonPath("$.settingsSchema[6].default[0]").value("random-mix"))
        .andExpect(jsonPath("$.settingsSchema[7].key").value("wordPackIds"))
        .andExpect(jsonPath("$.settingsSchema[7].itemFormat").value("uuid"))
        .andExpect(jsonPath("$.settingsSchema[7].maxItems").value(10))
        .andExpect(jsonPath("$.settingsSchema[7].exclusiveOption").doesNotExist())
        .andExpect(jsonPath("$.defaultSettings.cycles").value(2))
        .andExpect(jsonPath("$.defaultSettings.drawSeconds").value(80))
        .andExpect(jsonPath("$.defaultSettings.wordSource").value("built-in"))
        .andExpect(jsonPath("$.defaultSettings.wordPackIds.length()").value(0));
  }

  @Test
  void telephoneDetailDescribesTheAutoAndTheOnlyTrueSettings() throws Exception {
    mvc.perform(get("/api/v1/games/telephone"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settingsSchema.length()").value(5))
        .andExpect(jsonPath("$.settingsSchema[*].key", not(hasItem("cycles"))))
        .andExpect(jsonPath("$.settingsSchema[3].key").value("entriesPerChain"))
        .andExpect(jsonPath("$.settingsSchema[3].min").value(4))
        .andExpect(jsonPath("$.settingsSchema[3].max").value(8))
        .andExpect(jsonPath("$.settingsSchema[3].autoValue").value(0))
        .andExpect(jsonPath("$.settingsSchema[3].default").value(0))
        .andExpect(jsonPath("$.settingsSchema[4].key").value("allowSkipTimeout"))
        .andExpect(jsonPath("$.settingsSchema[4].type").value("bool"))
        .andExpect(jsonPath("$.settingsSchema[4].options.length()").value(1))
        .andExpect(jsonPath("$.settingsSchema[4].options[0]").value(true))
        .andExpect(jsonPath("$.defaultSettings.promptSeconds").value(60))
        .andExpect(jsonPath("$.defaultSettings.entriesPerChain").value(0))
        .andExpect(jsonPath("$.defaultSettings.allowSkipTimeout").value(true));
  }

  @Test
  void anUnknownGameIsGameNotFound() throws Exception {
    mvc.perform(get("/api/v1/games/chess"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
  }

  @Test
  void aDisabledGameIsListedAsDisabledButCannotHostARoom() throws Exception {
    jdbc.update("UPDATE game_definition SET enabled = false WHERE key = 'telephone'");

    mvc.perform(get("/api/v1/games"))
        .andExpect(jsonPath("$[1].key").value("telephone"))
        .andExpect(jsonPath("$[1].enabled").value(false))
        .andExpect(jsonPath("$[0].enabled").value(true));
    mvc.perform(get("/api/v1/games/telephone"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false));

    ApiTestSupport api = new ApiTestSupport(mvc);
    TestGuest host = api.createGuest(ApiTestSupport.unique("Off"));
    api.request(post("/api/v1/rooms"), host, "{\"gameKey\":\"telephone\"}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"));
  }
}
