package com.dinoparty.words;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dinoparty.ApiTestSupport;
import com.dinoparty.ApiTestSupport.TestGuest;
import com.dinoparty.IntegrationTest;
import com.dinoparty.words.WordCatalogTypes.CatalogWord;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Read-only word catalog API (api-contracts §2.3) and the catalog service's filtering. Every test
 * runs in a rolled-back transaction (MockMvc runs on the test thread), so the extra locales and
 * deactivated rows some tests insert never leak into other tests.
 */
@IntegrationTest
@Transactional
class WordCatalogApiIT {

  private static final String BASE = "/api/v1/word-catalog";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired WordCatalogService catalog;

  ApiTestSupport api;
  TestGuest guest;

  @BeforeEach
  void setUp() throws Exception {
    api = new ApiTestSupport(mvc);
    guest = api.createGuest(ApiTestSupport.unique("Words"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/categories", "/difficulties", "/counts"})
  void requiresAGuestToken(String path) throws Exception {
    mvc.perform(get(BASE + path))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void listsCategoriesInOrderWithTheRandomMixPreset() throws Exception {
    api.request(get(BASE + "/categories"), guest, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.locale").value("en"))
        .andExpect(jsonPath("$.categories.length()").value(9))
        .andExpect(jsonPath("$.categories[0].key").value("animals"))
        .andExpect(jsonPath("$.categories[0].label").value("Animals"))
        .andExpect(jsonPath("$.categories[1].label").value("Food & Drinks"))
        .andExpect(jsonPath("$.categories[5].key").value("movies-games"))
        .andExpect(jsonPath("$.categories[8].key").value("actions"))
        .andExpect(jsonPath("$.presets.length()").value(1))
        .andExpect(jsonPath("$.presets[0].key").value("random-mix"))
        .andExpect(jsonPath("$.presets[0].label").value("Random Mix"))
        .andExpect(jsonPath("$.presets[0].includes.length()").value(9));
  }

  @Test
  void listsDifficultiesWithMixedAsAPreset() throws Exception {
    api.request(get(BASE + "/difficulties"), guest, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.levels[*].key", contains("EASY", "MEDIUM", "HARD")))
        .andExpect(jsonPath("$.levels[*].label", contains("Easy", "Medium", "Hard")))
        .andExpect(jsonPath("$.presets.length()").value(1))
        .andExpect(jsonPath("$.presets[0].key").value("MIXED"))
        .andExpect(jsonPath("$.presets[0].label").value("Mixed"))
        .andExpect(jsonPath("$.presets[0].includes", contains("EASY", "MEDIUM", "HARD")));
  }

  @Test
  void countsEverythingByDefault() throws Exception {
    JsonNode body =
        api.parse(api.request(get(BASE + "/counts"), guest, null).andExpect(status().isOk()));

    assertThat(body.get("locale").asString()).isEqualTo("en");
    assertThat(body.get("category").asString()).isEqualTo("random-mix");
    assertThat(body.get("difficulty").asString()).isEqualTo("MIXED");
    for (String d : List.of("EASY", "MEDIUM", "HARD")) {
      assertThat(body.get("byDifficulty").get(d).asLong()).as(d).isEqualTo(dbCount(null, d));
    }
    assertThat(body.get("total").asLong()).isEqualTo(dbCount(null, null));
    assertThat(body.get("byCategory")).hasSize(9);
    long sum = 0;
    for (JsonNode c : body.get("byCategory")) {
      String key = c.get("key").asString();
      assertThat(c.get("total").asLong()).as(key).isEqualTo(dbCount(key, null));
      sum += c.get("total").asLong();
    }
    assertThat(sum).isEqualTo(body.get("total").asLong());
  }

  @ParameterizedTest
  @CsvSource({"animals,easy", "technology,HARD", "actions,Medium", "random-mix,hard"})
  void filtersByCategoryAndDifficulty(String category, String difficulty) throws Exception {
    JsonNode body =
        api.parse(
            api.request(
                    get(BASE + "/counts")
                        .param("category", category)
                        .param("difficulty", difficulty),
                    guest,
                    null)
                .andExpect(status().isOk()));
    String level = difficulty.toUpperCase();
    String key = "random-mix".equals(category) ? null : category;

    assertThat(body.get("difficulty").asString()).isEqualTo(level);
    assertThat(body.get("category").asString()).isEqualTo(category);
    assertThat(body.get("byDifficulty").propertyNames()).containsExactly(level);
    assertThat(body.get("total").asLong()).isEqualTo(dbCount(key, level)).isPositive();
    assertThat(body.get("byCategory")).hasSize(key == null ? 9 : 1);
  }

  @Test
  void serviceWordsHonourTheSameFilters() {
    List<CatalogWord> easyAnimals = catalog.words("en", "animals", "easy");
    assertThat(easyAnimals)
        .hasSize((int) dbCount("animals", "EASY"))
        .allSatisfy(
            w -> {
              assertThat(w.categoryKey()).isEqualTo("animals");
              assertThat(w.difficulty()).isEqualTo(WordDifficulty.EASY);
              assertThat(w.locale()).isEqualTo("en");
            })
        .extracting(CatalogWord::text)
        .contains("cat", "giraffe")
        .doesNotContain("hippopotamus", "apple")
        .isSorted();

    assertThat(catalog.words("en", null, "MIXED")).hasSize((int) dbCount(null, null));
    assertThat(catalog.words("en", "random-mix", "medium"))
        .extracting(CatalogWord::difficulty)
        .containsOnly(WordDifficulty.MEDIUM);
  }

  @Test
  void responsesNeverContainWords() throws Exception {
    List<String> bodies = new ArrayList<>();
    for (String path : List.of("/categories", "/difficulties", "/counts")) {
      bodies.add(
          api.request(get(BASE + path), guest, null)
              .andReturn()
              .getResponse()
              .getContentAsString());
    }
    List<String> words =
        jdbc.queryForList(
            "select display_text from word_catalog_entry where length(display_text) >= 6",
            String.class);
    for (String body : bodies) {
      for (String w : words) {
        assertThat(body).as(w).doesNotContain("\"" + w + "\"");
      }
    }
  }

  @ParameterizedTest
  @CsvSource({
    "locale,x",
    "locale,english",
    "locale,en-USA",
    "category,Animals",
    "category,unknown",
    "category,../animals",
    "difficulty,extreme",
    "difficulty,random-mix"
  })
  void rejectsInvalidSelections(String field, String value) throws Exception {
    api.request(get(BASE + "/counts").param(field, value), guest, null)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value(field));
  }

  @Test
  void anUnknownRegionFallsBackToTheLanguageAndThenToEnglish() throws Exception {
    for (String locale : List.of("en-US", "EN_gb", "de", "pt-BR")) {
      api.request(get(BASE + "/counts").param("locale", locale), guest, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.locale").value("en"))
          .andExpect(jsonPath("$.total").value(dbCount(null, null)));
      api.request(get(BASE + "/categories").param("locale", locale), guest, null)
          .andExpect(jsonPath("$.locale").value("en"))
          .andExpect(jsonPath("$.categories[0].label").value("Animals"));
    }
  }

  @Test
  void aLocalizedCatalogIsServedWithPerCategoryLabelFallback() throws Exception {
    jdbc.update(
        "insert into word_category_label (category_key, locale, label) values"
            + " ('animals', 'fr', 'Animaux'), ('food-drinks', 'fr', 'Nourriture & boissons')");
    jdbc.update(
        "insert into word_catalog_entry (id, locale, display_text, category_key, difficulty)"
            + " values (gen_random_uuid(), 'fr', 'chat', 'animals', 'EASY'),"
            + " (gen_random_uuid(), 'fr', 'éléphant', 'animals', 'MEDIUM'),"
            + " (gen_random_uuid(), 'fr', 'crêpe', 'food-drinks', 'EASY')");

    api.request(get(BASE + "/categories").param("locale", "fr-CA"), guest, null)
        .andExpect(jsonPath("$.locale").value("fr"))
        .andExpect(jsonPath("$.categories[0].label").value("Animaux"))
        .andExpect(jsonPath("$.categories[1].label").value("Nourriture & boissons"))
        .andExpect(jsonPath("$.categories[2].label").value("Nature"));

    api.request(get(BASE + "/counts").param("locale", "FR"), guest, null)
        .andExpect(jsonPath("$.locale").value("fr"))
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.byDifficulty.EASY").value(2))
        .andExpect(jsonPath("$.byDifficulty.MEDIUM").value(1))
        .andExpect(jsonPath("$.byDifficulty.HARD").value(0));

    // English is unaffected by the French rows.
    api.request(get(BASE + "/counts").param("locale", "en"), guest, null)
        .andExpect(jsonPath("$.total").value(dbCount(null, null)));
  }

  @Test
  void inactiveWordsAndCategoriesAreExcluded() throws Exception {
    long easyAnimals = dbCount("animals", "EASY");
    jdbc.update(
        "update word_catalog_entry set active = false where locale = 'en' and display_text = 'cat'");
    jdbc.update("update word_category set active = false where key = 'sports'");

    api.request(get(BASE + "/counts").param("category", "animals"), guest, null)
        .andExpect(jsonPath("$.byDifficulty.EASY").value(easyAnimals - 1));
    api.request(get(BASE + "/categories"), guest, null)
        .andExpect(jsonPath("$.categories.length()").value(8))
        .andExpect(jsonPath("$.presets[0].includes.length()").value(8));
    api.request(get(BASE + "/counts").param("category", "sports"), guest, null)
        .andExpect(status().isBadRequest());
    api.request(get(BASE + "/counts"), guest, null)
        .andExpect(jsonPath("$.byCategory.length()").value(8));
  }

  /** Active English words, optionally restricted to one category and/or one difficulty. */
  private long dbCount(String category, String difficulty) {
    return jdbc.queryForObject(
        "select count(*) from word_catalog_entry e join word_category c on c.key = e.category_key"
            + " where e.locale = 'en' and e.active and c.active"
            + " and (?::text is null or e.category_key = ?) and (?::text is null or e.difficulty = ?)",
        Long.class,
        category,
        category,
        difficulty,
        difficulty);
  }
}
