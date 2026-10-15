package com.dinoparty.games.drawguess;

import com.dinoparty.common.RandomSource;
import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.words.TestWords;
import com.dinoparty.words.WordPoolService;

/** Draw & Guess modules over in-memory words, for tests without Spring or a database. */
public final class DrawGuessTestModules {

  private DrawGuessTestModules() {}

  /** The original word lists as the built-in catalog, no custom packs, seeded word draws. */
  public static DrawGuessModule module() {
    return module(TestWords.service());
  }

  public static DrawGuessModule module(WordPoolService words) {
    return module(words, new SeededRandomSource(11));
  }

  public static DrawGuessModule module(WordPoolService words, RandomSource wordRandom) {
    return new DrawGuessModule(words, wordRandom);
  }
}
