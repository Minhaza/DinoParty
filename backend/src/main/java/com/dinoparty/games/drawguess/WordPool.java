package com.dinoparty.games.drawguess;

import com.dinoparty.common.RandomSource;
import com.dinoparty.games.drawguess.DrawGuessSettings.Difficulty;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The original word list ([DG-15]): two tiers loaded once at startup from versioned resources, so
 * no game call does I/O. Immutable and shared by all rooms; per-session state (the used set) lives
 * in the game state.
 */
final class WordPool {

  static final String EASY_RESOURCE = "/games/drawguess/words-easy.txt";
  static final String HARD_RESOURCE = "/games/drawguess/words-hard.txt";

  /** Lowercase letters with single inner spaces or hyphens, 3-20 characters ([DG-15]). */
  static final Pattern WORD = Pattern.compile("[a-z]+(?:[ -][a-z]+)*");

  static final int MIN_LENGTH = 3;
  static final int MAX_LENGTH = 20;

  private final List<String> easy;
  private final List<String> hard;
  private final List<String> mixed;

  WordPool(List<String> easy, List<String> hard) {
    this.easy = checked(easy);
    this.hard = checked(hard);
    Set<String> union = new LinkedHashSet<>(this.easy);
    union.addAll(this.hard);
    this.mixed = List.copyOf(union);
  }

  static WordPool load() {
    return new WordPool(read(EASY_RESOURCE), read(HARD_RESOURCE));
  }

  List<String> words(Difficulty difficulty) {
    return switch (difficulty) {
      case EASY -> easy;
      case HARD -> hard;
      case MIXED -> mixed;
    };
  }

  /**
   * Draws {@code count} distinct words uniformly without replacement from the tier minus the {@code
   * used} words. When fewer than {@code count} unused words remain, the whole tier is used again
   * (exhaustion falls back to repeats).
   */
  List<String> draw(Difficulty difficulty, Set<String> used, RandomSource random, int count) {
    List<String> tier = words(difficulty);
    List<String> candidates = new ArrayList<>(tier.size());
    for (String w : tier) {
      if (!used.contains(w)) {
        candidates.add(w);
      }
    }
    if (candidates.size() < count) {
      candidates = new ArrayList<>(tier);
    }
    // Partial Fisher-Yates: the first `count` slots become a uniform sample.
    for (int i = 0; i < count; i++) {
      int j = i + random.nextInt(candidates.size() - i);
      String tmp = candidates.get(i);
      candidates.set(i, candidates.get(j));
      candidates.set(j, tmp);
    }
    return List.copyOf(candidates.subList(0, count));
  }

  private static List<String> checked(List<String> words) {
    Set<String> unique = new LinkedHashSet<>();
    for (String w : words) {
      if (w.length() < MIN_LENGTH || w.length() > MAX_LENGTH || !WORD.matcher(w).matches()) {
        throw new IllegalStateException("Word list entry has an invalid format");
      }
      if (!unique.add(w)) {
        throw new IllegalStateException("Word list has a duplicate entry");
      }
    }
    if (unique.size() < 3) {
      throw new IllegalStateException("A word tier needs at least three words");
    }
    return List.copyOf(unique);
  }

  private static List<String> read(String resource) {
    InputStream in = WordPool.class.getResourceAsStream(resource);
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
