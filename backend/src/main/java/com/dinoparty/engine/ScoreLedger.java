package com.dinoparty.engine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Integer scores per roster member. Only ScoreDeltas change it, so totals always equal the sum of
 * deltas; a player who left keeps their score. Overflow is an error instead of wrapping.
 */
public final class ScoreLedger {

  private final Map<UUID, Integer> totals = new LinkedHashMap<>();

  public ScoreLedger(Collection<UUID> roster) {
    roster.forEach(g -> totals.put(g, 0));
  }

  /** Checks a delta without applying it; used to validate a whole transition first. */
  public void check(ScoreDelta delta) {
    Integer current = totals.get(delta.guestId());
    if (current == null) {
      throw new IllegalArgumentException("Score for a guest outside the roster");
    }
    Math.addExact(current, delta.points());
  }

  public void apply(ScoreDelta delta) {
    check(delta);
    totals.merge(delta.guestId(), delta.points(), Math::addExact);
  }

  /** Totals in roster order. */
  public Map<UUID, Integer> totals() {
    return new LinkedHashMap<>(totals);
  }
}
