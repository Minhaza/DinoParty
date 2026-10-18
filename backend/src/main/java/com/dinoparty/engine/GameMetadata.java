package com.dinoparty.engine;

import java.util.List;

/**
 * How a game presents itself to players (game API, api-contracts §2.6). Declared by the module next
 * to its rules, so no other layer keeps a copy.
 *
 * @param iconKey stable asset id the frontend maps to its artwork
 * @param howToPlay short steps in play order
 */
public record GameMetadata(
    String name, String tagline, String description, List<String> howToPlay, String iconKey) {

  public GameMetadata {
    for (String text : new String[] {name, tagline, description, iconKey}) {
      if (text == null || text.isBlank()) {
        throw new IllegalArgumentException("Game metadata needs a name, tagline, text and icon");
      }
    }
    howToPlay = List.copyOf(howToPlay);
    if (howToPlay.isEmpty()) {
      throw new IllegalArgumentException("Game metadata needs at least one how-to-play step");
    }
  }
}
