package com.dinoparty.games.impostor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImpostorVotesTest {

  private final List<UUID> players = new ArrayList<>();
  private final ImpostorVotes votes = new ImpostorVotes();

  ImpostorVotesTest() {
    for (int i = 0; i < 5; i++) {
      players.add(UUID.randomUUID());
    }
  }

  private UUID p(int i) {
    return players.get(i);
  }

  private static ErrorCode rejected(Runnable action) {
    try {
      action.run();
    } catch (DomainException e) {
      return e.code();
    }
    throw new AssertionError("expected a rejection");
  }

  @Test
  void DI9_aVoteCanBeChangedAndWithdrawn() {
    votes.cast(p(0), p(1), players);
    votes.cast(p(0), p(2), players);
    assertThat(votes.voteOf(p(0))).isEqualTo(p(2));
    assertThat(votes.voted(players)).containsExactly(p(0));

    votes.cast(p(0), null, players);
    assertThat(votes.voteOf(p(0))).isNull();
    assertThat(votes.voted(players)).isEmpty();
  }

  @Test
  void DI9_aSelfVoteIsValidationFailedAndChangesNothing() {
    votes.cast(p(0), p(1), players);

    assertThat(rejected(() -> votes.cast(p(0), p(0), players)))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(votes.voteOf(p(0))).isEqualTo(p(1));
  }

  @Test
  void DI9_aVoteForAPlayerWhoLeftOrIsNotOnTheRosterIsValidationFailed() {
    List<UUID> eligible = players.subList(0, 4); // p(4) has left
    assertThat(rejected(() -> votes.cast(p(0), p(4), eligible)))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> votes.cast(p(0), UUID.randomUUID(), eligible)))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(votes.voted(players)).isEmpty();
  }

  @Test
  void DI9_whenTheTargetLeavesEveryVoteForThemIsDropped() {
    votes.cast(p(0), p(3), players);
    votes.cast(p(1), p(3), players);
    votes.cast(p(2), p(0), players);

    votes.drop(p(3));

    assertThat(votes.voted(players)).containsExactly(p(2));
    assertThat(votes.snapshot()).containsOnlyKeys(p(2));
  }

  @Test
  void DI9_whenTheVoterLeavesTheirVoteIsDropped() {
    votes.cast(p(0), p(3), players);
    votes.cast(p(1), p(3), players);

    votes.drop(p(0));

    assertThat(votes.snapshot()).containsOnlyKeys(p(1));
  }

  @Test
  void DI9_allInOnlyWhenEveryEligiblePlayerHasAVote() {
    for (int i = 0; i < 4; i++) {
      votes.cast(p(i), p((i + 1) % 5), players);
    }
    assertThat(votes.allIn(players)).isFalse();
    assertThat(votes.allIn(players.subList(0, 4))).isTrue();
  }

  @Test
  void DI10_aStrictTopThatIsTheImpostorIsCaught() {
    votes.cast(p(0), p(4), players);
    votes.cast(p(1), p(4), players);
    votes.cast(p(2), p(3), players);

    assertThat(votes.result(p(4))).isEqualTo(new ImpostorVotes.Result(p(4), true));
  }

  @Test
  void DI10_aStrictTopThatIsAnArtistMeansTheImpostorEscaped() {
    votes.cast(p(0), p(3), players);
    votes.cast(p(1), p(3), players);
    votes.cast(p(2), p(4), players);

    assertThat(votes.result(p(4))).isEqualTo(new ImpostorVotes.Result(p(3), false));
  }

  @Test
  void DI10_aTieAtTheTopMeansEscaped() {
    votes.cast(p(0), p(4), players);
    votes.cast(p(1), p(4), players);
    votes.cast(p(2), p(3), players);
    votes.cast(p(4), p(3), players);

    assertThat(votes.result(p(4))).isEqualTo(new ImpostorVotes.Result(null, false));
  }

  @Test
  void DI10_noVotesAtAllMeansEscaped() {
    assertThat(votes.result(p(4))).isEqualTo(new ImpostorVotes.Result(null, false));
  }

  @Test
  void toStringNeverPrintsTargets() {
    votes.cast(p(0), p(1), players);
    assertThat(votes.toString()).doesNotContain(p(1).toString());
    assertThatThrownBy(() -> votes.cast(p(0), p(0), players)).isInstanceOf(DomainException.class);
  }
}
