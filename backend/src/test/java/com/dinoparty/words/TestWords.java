package com.dinoparty.words;

import com.dinoparty.words.WordCatalogTypes.CatalogWord;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory word providers so the word pool and the game run without a database. */
public final class TestWords {

  private TestWords() {}

  /** A mutable stand-in for the catalog: categories in display order and their words. */
  public static final class Catalog implements BuiltInWordProvider {
    public final List<String> categories = new CopyOnWriteArrayList<>();
    public final List<CatalogWord> entries = new CopyOnWriteArrayList<>();

    public Catalog(String... categories) {
      this.categories.addAll(List.of(categories));
    }

    public Catalog add(String text, String category, WordDifficulty difficulty) {
      entries.add(new CatalogWord(UUID.randomUUID(), "en", text, category, difficulty));
      return this;
    }

    @Override
    public List<String> activeCategoryKeys() {
      return List.copyOf(categories);
    }

    @Override
    public List<CatalogWord> words(List<String> categoryKeys, DifficultyFilter difficulty) {
      return entries.stream()
          .filter(w -> categoryKeys.contains(w.categoryKey()))
          .filter(w -> difficulty.difficulties().contains(w.difficulty()))
          .sorted((a, b) -> a.text().compareTo(b.text()))
          .toList();
    }

    /** Labels by category key; a category without one is labelled with its key. */
    public final Map<String, String> labels = new ConcurrentHashMap<>();

    @Override
    public Map<String, String> categoryLabels() {
      Map<String, String> result = new LinkedHashMap<>();
      categories.forEach(c -> result.put(c, labels.getOrDefault(c, c)));
      return result;
    }
  }

  /**
   * Custom packs held in memory, like WP-03's storage would be. A pack listed in {@code owners} is
   * only available to its owner; every other pack is available to everyone.
   */
  public static final class Packs implements CustomWordProvider {
    public final Map<UUID, List<CustomWord>> packs = new LinkedHashMap<>();
    public final Map<UUID, UUID> owners = new HashMap<>();

    /** Adds a pack of words without metadata and returns its id. */
    public UUID add(String... words) {
      UUID id = UUID.randomUUID();
      List<CustomWord> entries = new CopyOnWriteArrayList<>();
      for (String w : words) {
        entries.add(new CustomWord(w, null, null));
      }
      packs.put(id, entries);
      return id;
    }

    @Override
    public synchronized Map<UUID, List<CustomWord>> packs(UUID requester, List<UUID> packIds) {
      Map<UUID, List<CustomWord>> found = new HashMap<>();
      for (UUID id : packIds) {
        UUID owner = owners.get(id);
        if (packs.containsKey(id) && (owner == null || owner.equals(requester))) {
          found.put(id, List.copyOf(packs.get(id)));
        }
      }
      return found;
    }
  }

  /**
   * The original two resource lists as a catalog (D32 mapping): EASY words are {@code EASY}, HARD
   * words alternate between {@code MEDIUM} and {@code HARD}; categories are spread round-robin over
   * {@code animals}, {@code objects} and {@code nature}.
   */
  public static Catalog legacyCatalog() {
    List<String> categories = List.of("animals", "objects", "nature");
    Catalog catalog = new Catalog(categories.toArray(String[]::new));
    int i = 0;
    for (String w : read("/games/drawguess/words-easy.txt")) {
      catalog.add(w, categories.get(i++ % 3), WordDifficulty.EASY);
    }
    for (String w : read("/games/drawguess/words-hard.txt")) {
      catalog.add(
          w, categories.get(i % 3), i++ % 2 == 0 ? WordDifficulty.MEDIUM : WordDifficulty.HARD);
    }
    return catalog;
  }

  /** A pool service over the legacy catalog and the given packs. */
  public static WordPoolService service(Packs packs) {
    return new WordPoolService(legacyCatalog(), packs);
  }

  public static WordPoolService service() {
    return service(new Packs());
  }

  /** Words of a resource list (one per line, {@code #} comments). */
  public static List<String> read(String resource) {
    InputStream in = TestWords.class.getResourceAsStream(resource);
    if (in == null) {
      throw new IllegalStateException("Missing word list " + resource);
    }
    List<String> words = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String word = line.strip();
        if (!word.isEmpty() && !word.startsWith("#")) {
          words.add(word);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return words;
  }
}
