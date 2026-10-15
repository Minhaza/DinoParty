package com.dinoparty.games.telephone;

/**
 * Chain rotation of [TP-1]: chain {@code c} is owned by player {@code c} and its entry {@code k} is
 * written by player {@code (c + k) mod n}. Indices are positions in the engine's frozen order; a
 * player who leaves keeps theirs, so the allocation is never recomputed.
 */
final class ChainAllocator {

  private ChainAllocator() {}

  /** The player (order index) holding {@code chain} at {@code step}. */
  static int holder(int chain, int step, int n) {
    return (chain + step) % n;
  }

  /** The chain (index) the player holds at {@code step}. */
  static int chainHeldBy(int player, int step, int n) {
    return Math.floorMod(player - step, n);
  }
}
