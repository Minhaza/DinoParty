package com.dinoparty.words;

import com.dinoparty.common.DomainException;
import com.dinoparty.words.WordCatalogTypes.CatalogWord;
import com.dinoparty.words.WordCatalogTypes.Categories;
import com.dinoparty.words.WordCatalogTypes.CategoryCount;
import com.dinoparty.words.WordCatalogTypes.WordCategory;
import com.dinoparty.words.WordCatalogTypes.WordCounts;
import com.dinoparty.words.persistence.WordCatalogEntryEntity;
import com.dinoparty.words.persistence.WordCatalogEntryRepository;
import com.dinoparty.words.persistence.WordCategoryEntity;
import com.dinoparty.words.persistence.WordCategoryLabelEntity;
import com.dinoparty.words.persistence.WordCategoryLabelRepository;
import com.dinoparty.words.persistence.WordCategoryRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only access to the built-in word catalog (categories, difficulties, counts, words).
 *
 * <p>Locales resolve along {@link CatalogLocales#fallbackChain}: the first locale of the chain that
 * has active words is the catalog locale of the answer; category labels use the same chain.
 */
@Service
@Transactional(readOnly = true)
public class WordCatalogService implements BuiltInWordProvider {

  private static final Pattern CATEGORY_KEY = Pattern.compile("[a-z][a-z0-9-]{0,39}");

  private final WordCategoryRepository categories;
  private final WordCategoryLabelRepository labels;
  private final WordCatalogEntryRepository entries;

  WordCatalogService(
      WordCategoryRepository categories,
      WordCategoryLabelRepository labels,
      WordCatalogEntryRepository entries) {
    this.categories = categories;
    this.labels = labels;
    this.entries = entries;
  }

  /** Active categories in display order, labelled in the resolved locale. */
  public Categories categories(String requestedLocale) {
    String requested = CatalogLocales.normalize(requestedLocale);
    String locale = resolve(requested);
    Map<String, String> labelByKey = labels(requested);
    List<WordCategory> list =
        categories.findByActiveTrueOrderBySortOrderAsc().stream()
            .map(c -> new WordCategory(c.getKey(), labelByKey.getOrDefault(c.getKey(), c.getKey())))
            .toList();
    return new Categories(locale, list);
  }

  /**
   * Active word counts for a selection. {@code category} is a category key or {@link
   * WordCatalogTypes#RANDOM_MIX} (blank means Random Mix); {@code difficulty} blank means MIXED.
   */
  public WordCounts counts(String requestedLocale, String category, String difficulty) {
    String locale = resolve(CatalogLocales.normalize(requestedLocale));
    List<String> selected = selectCategories(category);
    DifficultyFilter filter = DifficultyFilter.parse(difficulty);
    List<WordDifficulty> levels = levels(filter);

    Map<String, Map<WordDifficulty, Long>> matrix = new HashMap<>();
    for (WordCatalogEntryRepository.CountRow row :
        entries.countActive(locale, selected, filter.difficulties())) {
      matrix
          .computeIfAbsent(row.getCategoryKey(), k -> new EnumMap<>(WordDifficulty.class))
          .put(row.getDifficulty(), row.getWords());
    }

    Map<WordDifficulty, Long> totals = zeros(levels);
    List<CategoryCount> byCategory = new ArrayList<>(selected.size());
    for (String key : selected) {
      Map<WordDifficulty, Long> counts = zeros(levels);
      matrix.getOrDefault(key, Map.of()).forEach(counts::put);
      counts.forEach((d, n) -> totals.merge(d, n, Long::sum));
      byCategory.add(new CategoryCount(key, Collections.unmodifiableMap(counts), sum(counts)));
    }
    return new WordCounts(
        locale,
        categoryName(category),
        filter,
        sum(totals),
        Collections.unmodifiableMap(totals),
        List.copyOf(byCategory));
  }

  /**
   * Active words of a selection (same arguments as {@link #counts}), ordered by text. For
   * server-side word drawing only; the REST API never lists words.
   */
  public List<CatalogWord> words(String requestedLocale, String category, String difficulty) {
    String locale = resolve(CatalogLocales.normalize(requestedLocale));
    List<String> selected = selectCategories(category);
    DifficultyFilter filter = DifficultyFilter.parse(difficulty);
    return entries
        .findByLocaleAndActiveTrueAndCategoryKeyInAndDifficultyInOrderByDisplayTextAsc(
            locale, selected, filter.difficulties())
        .stream()
        .map(WordCatalogService::toWord)
        .toList();
  }

  @Override
  public List<String> activeCategoryKeys() {
    return categories.findByActiveTrueOrderBySortOrderAsc().stream()
        .map(WordCategoryEntity::getKey)
        .toList();
  }

  /** Game words come from the default catalog locale ({@code en}, the only built-in one). */
  @Override
  public List<CatalogWord> words(List<String> categoryKeys, DifficultyFilter difficulty) {
    if (categoryKeys.isEmpty()) {
      return List.of();
    }
    return entries
        .findByLocaleAndActiveTrueAndCategoryKeyInAndDifficultyInOrderByDisplayTextAsc(
            resolve(CatalogLocales.DEFAULT), categoryKeys, difficulty.difficulties())
        .stream()
        .map(WordCatalogService::toWord)
        .toList();
  }

  /** Labels in the game words' locale ({@link CatalogLocales#DEFAULT}, as {@link #words}). */
  @Override
  public Map<String, String> categoryLabels() {
    Map<String, String> labelByKey = labels(CatalogLocales.DEFAULT);
    Map<String, String> result = new LinkedHashMap<>();
    for (String key : activeCategoryKeys()) {
      result.put(key, labelByKey.getOrDefault(key, key));
    }
    return result;
  }

  private String resolve(String requested) {
    for (String candidate : CatalogLocales.fallbackChain(requested)) {
      if (entries.existsByLocaleAndActiveTrue(candidate)) {
        return candidate;
      }
    }
    return CatalogLocales.DEFAULT;
  }

  /** Label per category key: the most specific locale of the chain that has one. */
  private Map<String, String> labels(String requested) {
    List<String> chain = CatalogLocales.fallbackChain(requested);
    Map<String, String> best = new HashMap<>();
    Map<String, Integer> rank = new HashMap<>();
    for (WordCategoryLabelEntity l : labels.findByIdLocaleIn(chain)) {
      int r = chain.indexOf(l.getLocale());
      Integer current = rank.get(l.getCategoryKey());
      if (current == null || r < current) {
        rank.put(l.getCategoryKey(), r);
        best.put(l.getCategoryKey(), l.getLabel());
      }
    }
    return best;
  }

  private List<String> selectCategories(String category) {
    List<String> active = activeCategoryKeys();
    String name = categoryName(category);
    if (WordCatalogTypes.RANDOM_MIX.equals(name)) {
      return active;
    }
    if (!CATEGORY_KEY.matcher(name).matches() || !active.contains(name)) {
      throw DomainException.invalid("category", "must be an active category key or random-mix");
    }
    return List.of(name);
  }

  private static String categoryName(String category) {
    return category == null || category.isBlank() ? WordCatalogTypes.RANDOM_MIX : category.strip();
  }

  private static List<WordDifficulty> levels(DifficultyFilter filter) {
    List<WordDifficulty> levels = new ArrayList<>();
    for (WordDifficulty d : WordDifficulty.values()) {
      if (filter.difficulties().contains(d)) {
        levels.add(d);
      }
    }
    return levels;
  }

  private static Map<WordDifficulty, Long> zeros(List<WordDifficulty> levels) {
    Map<WordDifficulty, Long> m = new EnumMap<>(WordDifficulty.class);
    levels.forEach(d -> m.put(d, 0L));
    return m;
  }

  private static long sum(Map<WordDifficulty, Long> counts) {
    return counts.values().stream().mapToLong(Long::longValue).sum();
  }

  private static CatalogWord toWord(WordCatalogEntryEntity e) {
    return new CatalogWord(
        e.getId(), e.getLocale(), e.getDisplayText(), e.getCategoryKey(), e.getDifficulty());
  }
}
