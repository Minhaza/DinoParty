package com.dinoparty.engine;

/**
 * Game-specific data a module resolves once, when the host starts ({@link GameModule#prepare}),
 * e.g. the Draw & Guess word pool. The engine keeps it for the whole session and hands the same
 * instance back in every {@link GameContext}; implementations must be immutable so later room or
 * reference-data changes cannot reach a running game. Never serialized or sent to clients.
 */
public interface GameSetup {

  /** The setup of a game that needs none. */
  GameSetup NONE = new GameSetup() {};
}
