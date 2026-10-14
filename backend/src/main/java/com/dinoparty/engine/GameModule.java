package com.dinoparty.engine;

import java.util.Map;
import java.util.UUID;

/**
 * Behavior of one game. All calls for a session happen under the room mailbox, one at a time, so a
 * module needs no locking and must not start threads, do I/O or keep the context or state outside a
 * call. Time and randomness come only from the {@link GameContext}.
 *
 * <p>{@link #handle} must either throw {@link ActionRejected} having changed nothing, or return a
 * transition. An unexpected exception from {@code handle} is answered {@code SERVER_ERROR}; from
 * any other method it aborts the session (docs/game-engine.md §1.3).
 */
public interface GameModule {

  GameDefinition definition();

  /** First transition of a session; must start the first phase. */
  Transition init(GameContext ctx);

  Transition handle(GameState state, PlayerAction action, GameContext ctx);

  Transition onDeadline(GameState state, DeadlineFired deadline, GameContext ctx);

  Transition onPlayerStatus(GameState state, PlayerStatusChange change, GameContext ctx);

  /** Game-specific part of a viewer's snapshot; private fields only for {@code viewer}. */
  Map<String, Object> view(GameState state, UUID viewer, GameContext ctx);

  /** Called once when the session ends with results. */
  GameOutcome outcome(GameState state, FinishReason reason, GameContext ctx);
}
