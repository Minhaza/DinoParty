package com.dinoparty.words.persistence;

import com.dinoparty.words.WordDifficulty;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WordCatalogEntryRepository extends JpaRepository<WordCatalogEntryEntity, UUID> {

  /** Number of active words per category and difficulty; combinations with none are absent. */
  interface CountRow {
    String getCategoryKey();

    WordDifficulty getDifficulty();

    long getWords();
  }

  boolean existsByLocaleAndActiveTrue(String locale);

  @Query(
      "select e.categoryKey as categoryKey, e.difficulty as difficulty, count(e) as words"
          + " from WordCatalogEntryEntity e"
          + " where e.locale = :locale and e.active = true"
          + " and e.categoryKey in :categories and e.difficulty in :difficulties"
          + " group by e.categoryKey, e.difficulty")
  List<CountRow> countActive(
      @Param("locale") String locale,
      @Param("categories") Collection<String> categories,
      @Param("difficulties") Collection<WordDifficulty> difficulties);

  List<WordCatalogEntryEntity>
      findByLocaleAndActiveTrueAndCategoryKeyInAndDifficultyInOrderByDisplayTextAsc(
          String locale, Collection<String> categories, Collection<WordDifficulty> difficulties);
}
