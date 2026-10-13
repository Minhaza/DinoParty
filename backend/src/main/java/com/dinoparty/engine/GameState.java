package com.dinoparty.engine;

/**
 * Marker for a game's private mutable state. The engine stores it and hands it back to the module
 * on every call; it never inspects it, never serializes it and never sends it to a client.
 */
public interface GameState {}
