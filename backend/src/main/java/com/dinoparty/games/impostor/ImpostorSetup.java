package com.dinoparty.games.impostor;

import com.dinoparty.engine.GameSetup;
import com.dinoparty.words.WordCatalogTypes.CategorizedWord;
import java.util.List;

/**
 * What an impostor session freezes at start ([DI-2]): one distinct word per round it can reach, in
 * play order, and for Sketchy Saurus without {@code impostorKnows} the decoy of each of those
 * rounds ([SS-3]; empty otherwise). Holds secrets: never serialized, and {@link #toString()} prints
 * no word.
 */
record ImpostorSetup(List<CategorizedWord> words, List<CategorizedWord> decoys)
    implements GameSetup {

  ImpostorSetup {
    words = List.copyOf(words);
    decoys = List.copyOf(decoys);
  }

  ImpostorSetup(List<CategorizedWord> words) {
    this(words, List.of());
  }

  @Override
  public String toString() {
    return "ImpostorSetup[words=" + words.size() + ", decoys=" + decoys.size() + "]";
  }
}
