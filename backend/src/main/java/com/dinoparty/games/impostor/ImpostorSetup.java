package com.dinoparty.games.impostor;

import com.dinoparty.engine.GameSetup;
import com.dinoparty.words.WordCatalogTypes.CategorizedWord;
import java.util.List;

/**
 * What an impostor session freezes at start ([DI-2]): one distinct word per round it can reach, in
 * play order. Holds secrets: never serialized, and {@link #toString()} prints no word.
 */
record ImpostorSetup(List<CategorizedWord> words) implements GameSetup {

  ImpostorSetup {
    words = List.copyOf(words);
  }

  @Override
  public String toString() {
    return "ImpostorSetup[words=" + words.size() + "]";
  }
}
