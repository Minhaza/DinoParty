package com.dinoparty.engine;

import java.util.Map;
import java.util.UUID;

/**
 * Behavior of one game. All calls for a session happen under the room mailbox, one at a time, so a
 * module needs no locking and must not start threads, do I/O or keep the context or state outside a
 * call. Time comes only from the {@link GameContext}, and so does randomness, except for secret
 * values that must not be derivable from the stored session seed: those use an injected {@code
 * RandomSource} (Draw & Guess word choices, design §19).
 *
 * <p>{@link #handle} must either throw {@link ActionRejected} having changed nothing, or return a
 * transition. An unexpected exception from {@code handle} is answered {@code SERVER_ERROR}; from
 * any other method it aborts the session (docs/game-engine.md §1.3).
 *
 * <p>{@link #prepare} is the one exception to the no-I/O rule: it runs before a session exists.
 */
public interface GameModule {

  GameDefinition definition();

  /**
   * Called once when the host starts, inside the room mailbox and before anything of the session
   * exists (no countdown, no status change). It may read reference data (e.g. word lists) and must
   * throw a {@code DomainException} to reject the start, which then leaves the room untouched. The
   * returned setup is frozen for the session and reaches every call as {@link GameContext#setup}.
   *
   * @param settings the room's validated settings, frozen for the session from this moment
   * @param host the authenticated guest who starts the game
   */
  default GameSetup prepare(Map<String, Object> settings, UUID host) {
    return GameSetup.NONE;
  }

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
