package com.dinoparty.words;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.IntegrationTest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flyway V5/V6 against a real PostgreSQL: schema, constraints and the seeded catalog (WP-01).
 * Constraint tests insert outside a transaction; every insert that would succeed is cleaned up.
 */
@IntegrationTest
class WordCatalogSeedIT {

  static final List<String> CATEGORIES =
      List.of(
          "animals",
          "food-drinks",
          "nature",
          "objects",
          "places",
          "movies-games",
          "sports",
          "technology",
          "actions");

  /** Same tokens as the [DG-15] blocklist of the legacy word lists. */
  private static final Set<String> BLOCKLIST =
      Set.of(
          "kill",
          "gun",
          "drug",
          "drugs",
          "beer",
          "wine",
          "vodka",
          "sex",
          "naked",
          "blood",
          "bomb",
          "die",
          "dead",
          "hell",
          "damn",
          "weed",
          "knife",
          "murder",
          "drunk",
          "cigarette",
          "nazi",
          "slave",
          "suicide",
          "poop");

  @Autowired JdbcTemplate jdbc;

  @Test
  void migrationsV5AndV6AreApplied() {
    List<String> applied =
        jdbc.queryForList(
            "select version from flyway_schema_history where success order by installed_rank",
            String.class);
    assertThat(applied).contains("5", "6");
    assertThat(
            jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class))
        .contains("word_category", "word_category_label", "word_catalog_entry");
  }

  @Test
  void seedsTheNineCategoriesWithEnglishLabelsInOrder() {
    assertThat(
            jdbc.queryForList(
                "select key from word_category where active order by sort_order", String.class))
        .containsExactlyElementsOf(CATEGORIES);
    Map<String, String> labels = new HashMap<>();
    jdbc.query(
        "select category_key, label from word_category_label where locale = 'en'",
        rs -> {
          labels.put(rs.getString(1), rs.getString(2));
        });
    assertThat(labels)
        .containsOnlyKeys(CATEGORIES)
        .containsEntry("food-drinks", "Food & Drinks")
        .containsEntry("movies-games", "Movies & Games");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from word_category where key like '%mix%'", Long.class))
        .as("Random Mix is a preset, not a category")
        .isZero();
  }

  @Test
  void seedMeetsTheTierSizesAndCoversEveryCategory() {
    Map<String, Long> byDifficulty = new HashMap<>();
    jdbc.query(
        "select difficulty, count(*) from word_catalog_entry where locale = 'en' and active"
            + " group by difficulty",
        rs -> {
          byDifficulty.put(rs.getString(1), rs.getLong(2));
        });
    // D31 kept (D32): at least 150 EASY and 150 MEDIUM; HARD is a smaller curated tier.
    assertThat(byDifficulty.get("EASY")).isGreaterThanOrEqualTo(150);
    assertThat(byDifficulty.get("MEDIUM")).isGreaterThanOrEqualTo(150);
    assertThat(byDifficulty.get("HARD")).isGreaterThanOrEqualTo(100);
    assertThat(byDifficulty).containsOnlyKeys("EASY", "MEDIUM", "HARD");

    List<Map<String, Object>> cells =
        jdbc.queryForList(
            "select category_key, difficulty, count(*) as n from word_catalog_entry"
                + " where locale = 'en' and active group by category_key, difficulty");
    assertThat(cells).hasSize(CATEGORIES.size() * 3);
    assertThat(cells).allSatisfy(c -> assertThat((Long) c.get("n")).isGreaterThanOrEqualTo(8));
  }

  @Test
  void seedWordsAreWellFormedCleanAndFreeOfNearDuplicates() {
    List<String> words =
        jdbc.queryForList(
            "select display_text from word_catalog_entry where locale = 'en'", String.class);
    Set<String> normalized = new HashSet<>();
    for (String w : words) {
      assertThat(w).hasSizeBetween(3, 20).matches("[a-z]+(?:[ -][a-z]+)*");
      for (String token : w.split("[ -]")) {
        assertThat(BLOCKLIST).as(w).doesNotContain(token);
      }
      assertThat(normalized.add(w.replaceAll("[ -]", ""))).as("duplicate %s", w).isTrue();
    }
    for (String n : normalized) {
      assertThat(normalized).as("plural of %s", n).doesNotContain(n + "s", n + "es");
    }
  }

  @Test
  void idsAreStableAndDerivedFromLocaleAndText() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from word_catalog_entry"
                    + " where id <> md5('dinoparty-word:' || locale || ':' || display_text)::uuid",
                Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select id::text from word_catalog_entry where locale = 'en' and display_text ="
                    + " 'cat'",
                String.class))
        .isEqualTo(stableCatId());
  }

  /** md5("dinoparty-word:en:cat") as a uuid, computed in Java so the test is independent. */
  private static String stableCatId() {
    try {
      byte[] d =
          java.security.MessageDigest.getInstance("MD5")
              .digest("dinoparty-word:en:cat".getBytes(StandardCharsets.UTF_8));
      String hex = java.util.HexFormat.of().formatHex(d);
      return hex.substring(0, 8)
          + "-"
          + hex.substring(8, 12)
          + "-"
          + hex.substring(12, 16)
          + "-"
          + hex.substring(16, 20)
          + "-"
          + hex.substring(20);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void everyLegacyGameWordIsInTheCatalogWithAConsistentTier() throws IOException {
    Map<String, String> catalog = new HashMap<>();
    jdbc.query(
        "select display_text, difficulty from word_catalog_entry where locale = 'en' and active",
        rs -> {
          catalog.put(rs.getString(1), rs.getString(2));
        });
    // D32: legacy EASY -> EASY; legacy HARD -> MEDIUM or HARD.
    for (String w : legacy("/games/drawguess/words-easy.txt")) {
      assertThat(catalog.get(w)).as(w).isEqualTo("EASY");
    }
    for (String w : legacy("/games/drawguess/words-hard.txt")) {
      assertThat(catalog.get(w)).as(w).isIn("MEDIUM", "HARD");
    }
  }

  @Test
  void rejectsADuplicateWithinALocaleIgnoringSpacesAndHyphens() {
    for (String dup : List.of("cat", "ice-cream", "icecream", "hotdog")) {
      assertThatThrownBy(() -> insert("dup-" + dup, "en", dup, "animals", "EASY"))
          .as(dup)
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  @Test
  void theSameTextMayExistInAnotherLocale() {
    String id = "00000000-0000-0000-0000-00000000c001";
    try {
      insert(id, "en-GB", "cat", "animals", "EASY");
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from word_catalog_entry where display_text = 'cat'", Long.class))
          .isEqualTo(2);
    } finally {
      jdbc.update("delete from word_catalog_entry where id = ?::uuid", id);
    }
  }

  @Test
  void rejectsInvalidRows() {
    String id = "00000000-0000-0000-0000-00000000c002";
    List<String[]> bad =
        List.of(
            new String[] {"en", "mixedword", "animals", "MIXED"}, // MIXED is not persisted
            new String[] {"en", "zzword", "random-mix", "EASY"}, // no such category
            new String[] {"en", "ab", "animals", "EASY"}, // too short
            new String[] {"en", "Capital", "animals", "EASY"},
            new String[] {"en", "two  spaces", "animals", "EASY"},
            new String[] {"en", " lead", "animals", "EASY"},
            new String[] {"en", "café", "animals", "EASY"}, // English entries are ASCII
            new String[] {"EN", "zzword", "animals", "EASY"}, // locale format
            new String[] {"en_US", "zzword", "animals", "EASY"});
    for (String[] row : bad) {
      assertThatThrownBy(() -> insert(id, row[0], row[1], row[2], row[3]))
          .as(String.join("|", row))
          .isInstanceOf(DataIntegrityViolationException.class);
    }
    // Accented letters are fine outside English (future localization).
    try {
      insert(id, "fr", "café", "food-drinks", "EASY");
    } finally {
      jdbc.update("delete from word_catalog_entry where id = ?::uuid", id);
    }
  }

  private void insert(String id, String locale, String text, String category, String difficulty) {
    String uuid = id.startsWith("dup-") ? java.util.UUID.randomUUID().toString() : id;
    jdbc.update(
        "insert into word_catalog_entry (id, locale, display_text, category_key, difficulty)"
            + " values (?::uuid, ?, ?, ?, ?)",
        uuid,
        locale,
        text,
        category,
        difficulty);
  }

  private static List<String> legacy(String resource) throws IOException {
    try (InputStream in = WordCatalogSeedIT.class.getResourceAsStream(resource);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      return r.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
    }
  }
}
