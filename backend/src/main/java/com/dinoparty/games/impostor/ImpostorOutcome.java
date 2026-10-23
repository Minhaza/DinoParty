package com.dinoparty.games.impostor;

/** How a round ended ([DI-10]..[DI-13]); the name is the wire value of {@code outcome}. */
enum ImpostorOutcome {
  /** Not caught: no strict top, or the top was not the impostor. */
  ESCAPED,
  /** Caught, then guessed the word. */
  GUESSED,
  /** Caught and wrong, or caught with no guess. */
  CAUGHT,
  IMPOSTOR_LEFT;

  /** Whether the vote caught the impostor. */
  boolean caught() {
    return this == GUESSED || this == CAUGHT;
  }
}
