package com.dinoparty.web;

import com.dinoparty.words.DifficultyFilter;
import com.dinoparty.words.WordCatalogService;
import com.dinoparty.words.WordCatalogTypes;
import com.dinoparty.words.WordCatalogTypes.Categories;
import com.dinoparty.words.WordCatalogTypes.WordCounts;
import com.dinoparty.words.WordDifficulty;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only word catalog metadata (api-contracts §2.3). Never lists words, so nothing here can
 * reveal a word of a game in progress.
 */
@RestController
@RequestMapping("/api/v1/word-catalog")
class WordCatalogController {

  record OptionDto(String key, String label) {}

  record PresetDto(String key, String label, List<String> includes) {}

  record CategoriesDto(String locale, List<OptionDto> categories, List<PresetDto> presets) {}

  record DifficultiesDto(List<OptionDto> levels, List<PresetDto> presets) {}

  record CategoryCountDto(String key, long total, Map<WordDifficulty, Long> byDifficulty) {}

  record WordCountsDto(
      String locale,
      String category,
      DifficultyFilter difficulty,
      long total,
      Map<WordDifficulty, Long> byDifficulty,
      List<CategoryCountDto> byCategory) {}

  private final WordCatalogService catalog;

  WordCatalogController(WordCatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/categories")
  CategoriesDto categories(@RequestParam(required = false) String locale) {
    Categories result = catalog.categories(locale);
    List<OptionDto> categories =
        result.categories().stream().map(c -> new OptionDto(c.key(), c.label())).toList();
    PresetDto randomMix =
        new PresetDto(
            WordCatalogTypes.RANDOM_MIX,
            WordCatalogTypes.RANDOM_MIX_LABEL,
            categories.stream().map(OptionDto::key).toList());
    return new CategoriesDto(result.locale(), categories, List.of(randomMix));
  }

  @GetMapping("/difficulties")
  DifficultiesDto difficulties() {
    List<OptionDto> levels =
        Arrays.stream(WordDifficulty.values())
            .map(d -> new OptionDto(d.name(), d.label()))
            .toList();
    List<String> all = levels.stream().map(OptionDto::key).toList();
    return new DifficultiesDto(
        levels,
        List.of(new PresetDto(DifficultyFilter.MIXED.name(), DifficultyFilter.MIXED.label(), all)));
  }

  @GetMapping("/counts")
  WordCountsDto counts(
      @RequestParam(required = false) String locale,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String difficulty) {
    WordCounts counts = catalog.counts(locale, category, difficulty);
    return new WordCountsDto(
        counts.locale(),
        counts.category(),
        counts.difficulty(),
        counts.total(),
        counts.byDifficulty(),
        counts.byCategory().stream()
            .map(c -> new CategoryCountDto(c.key(), c.total(), c.byDifficulty()))
            .toList());
  }
}
