package com.dinoparty.games.impostor;

import com.dinoparty.common.ErrorCode;
import com.dinoparty.engine.ActionRejected;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The votes of one round ([DI-9], [DI-10]). Pure: it never sees a clock or an event. A vote names
 * another player who has not left; a player who leaves loses their vote and every vote for them.
 * Who voted for whom stays secret until the vote closes (R35), so {@link #toString()} prints
 * nothing of it.
 */
final class ImpostorVotes {

  /** The strict top of the tally, or {@code null}, and whether it is the impostor. */
  record Result(UUID accused, boolean caught) {}

  private final Map<UUID, UUID> votes = new LinkedHashMap<>();

  /**
   * Records, replaces or (with a {@code null} target) withdraws the vote of {@code voter}.
   *
   * @param eligible the players not {@code LEFT}
   * @throws ActionRejected {@code VALIDATION_FAILED} for a self-vote or a target who is not
   *     eligible
   */
  void cast(UUID voter, UUID target, Collection<UUID> eligible) {
    if (target == null) {
      votes.remove(voter);
      return;
    }
    if (target.equals(voter)) {
      throw new ActionRejected(ErrorCode.VALIDATION_FAILED, "payload.targetGuestId cannot be you");
    }
    if (!eligible.contains(target)) {
      throw new ActionRejected(
          ErrorCode.VALIDATION_FAILED, "payload.targetGuestId must be a player still in the game");
    }
    votes.put(voter, target);
  }

  /** Removes the player's own vote and every vote for them. */
  void drop(UUID guest) {
    votes.remove(guest);
    votes.values().removeIf(guest::equals);
  }

  /** Who has a vote in, in {@code order}; never the targets. */
  List<UUID> voted(List<UUID> order) {
    List<UUID> voted = new ArrayList<>();
    for (UUID g : order) {
      if (votes.containsKey(g)) {
        voted.add(g);
      }
    }
    return voted;
  }

  boolean allIn(Collection<UUID> eligible) {
    return votes.keySet().containsAll(eligible);
  }

  UUID voteOf(UUID voter) {
    return votes.get(voter);
  }

  /** A copy of every vote, voter to target. */
  Map<UUID, UUID> snapshot() {
    return new LinkedHashMap<>(votes);
  }

  /** [DI-10]: caught only with strictly more votes than any other player. */
  Result result(UUID impostor) {
    Map<UUID, Integer> tally = new HashMap<>();
    votes.values().forEach(t -> tally.merge(t, 1, Integer::sum));
    int top = tally.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    List<UUID> leaders =
        tally.entrySet().stream().filter(e -> e.getValue() == top).map(Map.Entry::getKey).toList();
    UUID accused = leaders.size() == 1 ? leaders.getFirst() : null;
    return new Result(accused, impostor.equals(accused));
  }

  @Override
  public String toString() {
    return "ImpostorVotes[" + votes.size() + "]";
  }
}
