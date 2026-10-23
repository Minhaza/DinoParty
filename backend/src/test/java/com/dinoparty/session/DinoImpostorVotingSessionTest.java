package com.dinoparty.session;

import static com.dinoparty.session.ImpostorDriver.GUESS;
import static com.dinoparty.session.ImpostorDriver.REVEAL;
import static com.dinoparty.session.ImpostorDriver.ROUND_END;
import static com.dinoparty.session.ImpostorDriver.VOTE;
import static com.dinoparty.session.ImpostorDriver.rejected;
import static com.dinoparty.session.SessionFixture.CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.engine.RoundRecord;
import com.dinoparty.games.impostor.DinoImpostorModule;
import com.dinoparty.room.RoomStatus;
import com.dinoparty.session.SessionFixture.Published;
import com.dinoparty.words.TestWords;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Dino Impostor through the real engine with a manual clock (P9-03): chat and voting with their
 * timing, the guess, scoring and the reveal, results, an impostor or artist who leaves in every
 * phase, and the contract fix fields G1, G2 and G4.
 */
class DinoImpostorVotingSessionTest {

  private SessionFixture f = fixture(1);
  private ImpostorDriver d = new ImpostorDriver(f);

  private static SessionFixture fixture(long secret) {
    return new SessionFixture(
        new DinoImpostorModule(TestWords.service(), new SeededRandomSource(secret)));
  }

  /** 5 players, 3 rounds, one lap: the first ROLE_REVEAL is running. */
  private void start5() {
    d.start(5, Map.of("rounds", 3, "laps", 1));
  }

  private UUID artist(int i) {
    return d.artists().get(i);
  }

  @SuppressWarnings("unchecked")
  private <T> T field(Map<String, Object> m, String key) {
    return (T) m.get(key);
  }

  private static Object get(Map<String, Object> m, String key) {
    return m.get(key);
  }

  private Map<String, Object> last(String type) {
    return d.lastEvent(type);
  }

  private Map<UUID, Integer> scores() {
    return f.sessions.info(CODE).scores();
  }

  private void toGuess() {
    d.toVote();
    d.everyoneVotes(d.impostor());
    d.advance(Duration.ofSeconds(15));
    assertThat(d.phase()).isEqualTo("GUESS");
  }

  // ------------------------------------------------------------------ voting timing

  @Test
  void DI9_everyoneVotingAt5sKeepsTheVoteOpenUntilTheMarkAt15s() {
    start5();
    d.toVote();
    d.advance(Duration.ofSeconds(5));
    d.everyoneVotes(artist(0));

    assertThat(d.phase()).isEqualTo("VOTE");
    assertThat(f.sink.ofType("IMPOSTOR_VOTE_CLOSED")).isEmpty();

    d.advance(Duration.ofMillis(9_999));
    assertThat(f.sink.ofType("IMPOSTOR_VOTE_CLOSED")).isEmpty();
    d.advance(Duration.ofMillis(1));
    assertThat(f.sink.ofType("IMPOSTOR_VOTE_CLOSED")).hasSize(1);
    assertThat(d.phase()).isEqualTo("ROUND_END"); // escaped: the top is an artist
  }

  @Test
  void DI9_everyoneVotingAfter15sClosesOnTheLastVote() {
    start5();
    d.toVote();
    d.advance(Duration.ofSeconds(20));
    UUID accused = artist(0);
    List<UUID> others = d.seated().stream().filter(p -> !p.equals(accused)).toList();
    for (UUID p : others) {
      d.vote(p, accused);
    }
    assertThat(d.phase()).isEqualTo("VOTE"); // the accused has not voted yet

    d.vote(accused, artist(1));

    assertThat(d.phase()).isEqualTo("ROUND_END");
    assertThat(f.sink.ofType("IMPOSTOR_VOTE_CLOSED")).hasSize(1);
  }

  @Test
  void DI9_withoutEveryoneVotingTheVoteClosesAtTheDeadline() {
    start5();
    d.toVote();
    d.vote(artist(0), artist(1));
    d.vote(artist(1), artist(0));

    d.advance(VOTE.minusMillis(1));
    assertThat(d.phase()).isEqualTo("VOTE");
    d.advance(Duration.ofMillis(1));

    assertThat(f.sink.ofType("IMPOSTOR_VOTE_CLOSED")).hasSize(1);
    assertThat(last("IMPOSTOR_VOTE_CLOSED")).containsEntry("accusedGuestId", null);
  }

  @Test
  void C6_aVoteAtTheDeadlineIsStaleAndOneMillisecondBeforeItIsAccepted() {
    start5();
    d.toVote();
    String votePhase = f.phaseId();
    d.advance(VOTE.minusMillis(1));
    d.vote(artist(0), artist(1)); // accepted
    assertThat(f.sink.ofType("IMPOSTOR_VOTE_PROGRESS").getLast().payload())
        .containsEntry("votedGuestIds", List.of(artist(0)));

    f.clock.advance(Duration.ofMillis(1)); // the deadline, with no timer fired yet
    assertThat(
            rejected(
                () ->
                    f.sessions.submit(
                        CODE,
                        artist(1),
                        "IMPOSTOR_VOTE",
                        votePhase,
                        Map.of("targetGuestId", artist(2).toString()))))
        .isEqualTo(ErrorCode.STALE_ACTION);
    assertThat(d.phase()).isEqualTo("ROUND_END");
    assertThat(get(last("IMPOSTOR_VOTE_CLOSED"), "votes")).isEqualTo(Map.of(artist(0), artist(1)));
  }

  // ------------------------------------------------------------------ votes

  @Test
  void DI9_theVotePhaseOpensWithAnEmptyProgressAndLaterOnesNameVotersOnly() {
    start5();
    d.toVote();

    assertThat(last("IMPOSTOR_VOTE_PROGRESS"))
        .containsEntry("phaseId", UUID.fromString(f.phaseId()))
        .containsEntry("votedGuestIds", List.of());
    d.vote(artist(2), artist(0));

    Map<String, Object> progress = last("IMPOSTOR_VOTE_PROGRESS");
    assertThat(progress.keySet()).containsExactly("phaseId", "votedGuestIds");
    assertThat(progress).containsEntry("votedGuestIds", List.of(artist(2)));
    assertThat(f.sink.ofType("IMPOSTOR_VOTE_PROGRESS").getLast().isPublic()).isTrue();
  }

  @Test
  void DI9_aVoteCanBeChangedAndWithdrawnAndTheSnapshotShowsOnlyTheViewersOwn() {
    start5();
    d.toVote();
    UUID a = artist(0);
    UUID b = artist(1);
    UUID c = artist(2);

    d.vote(a, b);
    d.vote(a, c);
    assertThat(d.snapshot(a)).containsEntry("myVote", c).containsEntry("votedGuestIds", List.of(a));
    assertThat(d.snapshot(b))
        .containsEntry("myVote", null)
        .containsEntry("votedGuestIds", List.of(a));

    d.vote(a, null);
    assertThat(d.snapshot(a))
        .containsEntry("myVote", null)
        .containsEntry("votedGuestIds", List.of());
    assertThat(last("IMPOSTOR_VOTE_PROGRESS")).containsEntry("votedGuestIds", List.of());
  }

  @Test
  void DI9_invalidVotesAreValidationFailedAndChangeNothing() {
    start5();
    d.toVote();
    UUID a = artist(0);
    d.vote(a, artist(1));
    int events = f.sink.events.size();

    assertThat(rejected(() -> d.vote(a, a))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> d.vote(a, UUID.randomUUID()))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> d.send(a, "IMPOSTOR_VOTE", Map.of())))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> d.send(a, "IMPOSTOR_VOTE", Map.of("targetGuestId", "nope"))))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> d.send(a, "IMPOSTOR_VOTE", Map.of("targetGuestId", 5))))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(f.sink.events).hasSize(events);
    assertThat(d.snapshot(a)).containsEntry("myVote", artist(1));
  }

  @Test
  void DI9_aVoteForAPlayerWhoLeftIsValidationFailed() {
    start5();
    d.toVote();
    UUID gone = artist(3);
    d.leave(gone);

    assertThat(rejected(() -> d.vote(artist(0), gone))).isEqualTo(ErrorCode.VALIDATION_FAILED);
  }

  @Test
  void DI9_votesAreLimitedToTenPerTenSecondsAndAcceptedAgainAfterwards() {
    start5();
    d.toVote();
    UUID a = artist(0);
    for (int i = 0; i < 10; i++) {
      d.vote(a, artist(1 + i % 2));
    }

    assertThat(rejected(() -> d.vote(a, artist(3)))).isEqualTo(ErrorCode.RATE_LIMITED);
    assertThat(d.snapshot(a)).containsEntry("myVote", artist(2)); // the tenth stands
    d.advance(Duration.ofSeconds(10));
    d.vote(a, artist(3));
    assertThat(d.snapshot(a)).containsEntry("myVote", artist(3));
  }

  @Test
  void DI9_whenAPlayerLeavesTheirVoteAndTheVotesForThemAreDroppedAndProgressIsRepublished() {
    start5();
    d.toVote();
    UUID leaver = artist(0);
    UUID second = artist(1);
    UUID third = artist(2);
    d.vote(leaver, second);
    d.vote(third, leaver);
    d.vote(second, third);
    int before = f.sink.ofType("IMPOSTOR_VOTE_PROGRESS").size();

    d.leave(leaver);

    assertThat(f.sink.ofType("IMPOSTOR_VOTE_PROGRESS")).hasSize(before + 1);
    List<UUID> voted = field(last("IMPOSTOR_VOTE_PROGRESS"), "votedGuestIds");
    assertThat(voted).containsExactly(second);
    assertThat(d.snapshot(third)).containsEntry("myVote", null);
  }

  @Test
  void DI9_aLeaveThatLeavesEveryoneElseVotedClosesTheVoteWhenTheMarkHasPassed() {
    start5();
    d.toVote();
    d.advance(Duration.ofSeconds(20));
    UUID waiting = artist(3);
    for (UUID p : d.seated()) {
      if (!p.equals(waiting)) {
        d.vote(p, p.equals(artist(0)) ? artist(1) : artist(0));
      }
    }
    assertThat(d.phase()).isEqualTo("VOTE");

    d.leave(waiting);

    assertThat(f.sink.ofType("IMPOSTOR_VOTE_CLOSED")).hasSize(1);
    assertThat(d.phase()).isIn("GUESS", "ROUND_END");
  }

  // ------------------------------------------------------------------ chat

  @Test
  void DI8_aLineIsPublishedToEveryoneInTheVotePhase() {
    start5();
    d.toVote();

    d.chat(artist(0), "  looks like a  scaly one ");

    Published line = f.sink.ofType("IMPOSTOR_CHAT_MESSAGE").getLast();
    assertThat(line.isPublic()).isTrue();
    assertThat(line.payload())
        .containsEntry("guestId", artist(0))
        .containsEntry("text", "looks like a  scaly one")
        .containsKey("ts");
    List<Map<String, Object>> chat = field(d.snapshot(artist(1)), "recentChat");
    assertThat(chat).hasSize(1);
    assertThat(chat.getFirst()).containsEntry("text", "looks like a  scaly one");
  }

  @Test
  void DI8_chatIsInvalidPhaseOutsideTheVoteAndStaleWithAnOldPhaseId() {
    start5();
    assertThat(rejected(() -> d.chat(artist(0), "hi")))
        .isEqualTo(ErrorCode.INVALID_PHASE); // reveal
    d.advance(REVEAL);
    assertThat(rejected(() -> d.chat(artist(0), "hi"))).isEqualTo(ErrorCode.INVALID_PHASE);
    d.toVote();
    String votePhase = f.phaseId();
    d.chat(artist(0), "now it is fine");

    d.everyoneVotes(d.impostor());
    d.advance(Duration.ofSeconds(15));
    assertThat(d.phase()).isEqualTo("GUESS");
    assertThat(rejected(() -> d.chat(artist(0), "hi"))).isEqualTo(ErrorCode.INVALID_PHASE);
    assertThat(
            rejected(
                () ->
                    f.sessions.submit(
                        CODE, artist(0), "IMPOSTOR_CHAT", votePhase, Map.of("text", "late"))))
        .isEqualTo(ErrorCode.STALE_ACTION);
    assertThat(f.sink.ofType("IMPOSTOR_CHAT_MESSAGE")).hasSize(1);
  }

  @Test
  void DI8_theSixthLineWithinTenSecondsIsRateLimitedAndNothingChanges() {
    start5();
    d.toVote();
    for (int i = 0; i < 5; i++) {
      d.chat(artist(0), "line " + i);
    }
    int events = f.sink.events.size();

    assertThat(rejected(() -> d.chat(artist(0), "line 5"))).isEqualTo(ErrorCode.RATE_LIMITED);
    assertThat(f.sink.events).hasSize(events);
    assertThat(rejected(() -> d.chat(artist(1), ""))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    d.advance(Duration.ofSeconds(10));
    d.chat(artist(0), "line 5");
  }

  @Test
  void DI8_atMostThirtyLinesAreKeptInTheSnapshotOldestFirst() {
    start5();
    d.toVote();
    int n = 0;
    for (int burst = 0; burst < 2; burst++) {
      for (int p = 0; p < 5; p++) {
        for (int i = 0; i < 4; i++) {
          d.chat(d.seated().get(p), "line " + n++);
        }
      }
      d.advance(Duration.ofSeconds(10));
    }
    assertThat(d.phase()).isEqualTo("VOTE");

    List<Map<String, Object>> chat = field(d.snapshot(artist(0)), "recentChat");
    assertThat(chat).hasSize(30);
    assertThat(chat.getFirst()).containsEntry("text", "line 10");
    assertThat(chat.getLast()).containsEntry("text", "line 39");
  }

  // ------------------------------------------------------------------ closing the vote

  @Test
  void DI10_aCaughtImpostorOpensTheGuessWithTheIdentityOnlyFromNowOn() {
    start5();
    d.toVote();
    UUID impostor = d.impostor();
    d.everyoneVotes(impostor);
    d.advance(Duration.ofSeconds(15));

    Map<String, Object> closed = last("IMPOSTOR_VOTE_CLOSED");
    assertThat(closed.keySet())
        .containsExactly("round", "votes", "accusedGuestId", "caught", "impostorGuestId");
    assertThat(closed)
        .containsEntry("round", 1)
        .containsEntry("accusedGuestId", impostor)
        .containsEntry("caught", true)
        .containsEntry("impostorGuestId", impostor);
    assertThat(get(closed, "votes")).isInstanceOf(Map.class);
    assertThat(d.lastPhase())
        .containsEntry("phase", "GUESS")
        .containsEntry("impostorGuestId", impostor)
        .containsEntry("round", 1)
        .containsEntry("totalRounds", 3)
        .containsKey("deadlineAt");

    Published open = f.sink.ofType("IMPOSTOR_GUESS_OPEN").getLast();
    assertThat(f.sink.ofType("IMPOSTOR_GUESS_OPEN")).hasSize(1);
    assertThat(open.recipient()).isEqualTo(impostor);
    assertThat(open.payload().keySet())
        .containsExactly("phaseId", "round", "category", "deadlineAt");
    assertThat(open.payload()).containsEntry("phaseId", UUID.fromString(f.phaseId()));
    assertThat(d.snapshot(artist(0)))
        .containsEntry("voteClosed", closed)
        .containsEntry("roundEnd", null);
  }

  @Test
  void DI10_aTieAtTheTopOrNoVotesMeansEscapedWithNoIdentityInTheVoteClosed() {
    start5();
    d.toVote();
    d.vote(artist(0), artist(1));
    d.vote(artist(1), artist(0));

    d.advance(VOTE);

    Map<String, Object> closed = last("IMPOSTOR_VOTE_CLOSED");
    assertThat(closed)
        .containsEntry("accusedGuestId", null)
        .containsEntry("caught", false)
        .containsEntry("impostorGuestId", null);
    assertThat(get(last("IMPOSTOR_ROUND_ENDED"), "outcome")).isEqualTo("ESCAPED");
    assertThat(f.sink.ofType("IMPOSTOR_GUESS_OPEN")).isEmpty();
  }

  @Test
  void DI10_aStrictTopThatIsAnArtistMeansEscaped() {
    start5();
    d.toVote();
    d.everyoneVotes(artist(0));
    d.advance(Duration.ofSeconds(15));

    assertThat(last("IMPOSTOR_VOTE_CLOSED"))
        .containsEntry("accusedGuestId", artist(0))
        .containsEntry("caught", false)
        .containsEntry("impostorGuestId", null);
    assertThat(get(last("IMPOSTOR_ROUND_ENDED"), "outcome")).isEqualTo("ESCAPED");
  }

  // ------------------------------------------------------------------ the guess

  @Test
  void DI11_aGuessThatMatchesAfterNormalizationWins() {
    start5();
    toGuess();
    UUID impostor = d.impostor();
    String word = d.word();

    d.guess(impostor, "  " + word.toUpperCase() + "!  ");

    Map<String, Object> ended = last("IMPOSTOR_ROUND_ENDED");
    assertThat(ended).containsEntry("outcome", "GUESSED").containsEntry("word", word);
    assertThat(ended.get("guess")).isEqualTo(word.toUpperCase() + "!");
    assertThat(scores().get(impostor)).isEqualTo(2);
    assertThat(d.phase()).isEqualTo("ROUND_END");
  }

  @Test
  void DI11_aCloseGuessCountsAsWrong() {
    start5();
    toGuess();
    String word = d.word();

    d.guess(d.impostor(), word.substring(0, word.length() - 1));

    assertThat(get(last("IMPOSTOR_ROUND_ENDED"), "outcome")).isEqualTo("CAUGHT");
  }

  @Test
  void DI11_anArtistGuessingIsForbiddenAndChangesNothing() {
    start5();
    toGuess();
    int events = f.sink.events.size();

    assertThat(rejected(() -> d.guess(artist(0), d.word()))).isEqualTo(ErrorCode.FORBIDDEN);

    assertThat(f.sink.events).hasSize(events);
    assertThat(d.phase()).isEqualTo("GUESS");
  }

  @Test
  void DI11_aSecondGuessIsStaleBecauseTheFirstEndedThePhase() {
    start5();
    toGuess();
    UUID impostor = d.impostor();
    String guessPhase = f.phaseId();
    d.guess(impostor, "not it");

    assertThat(
            rejected(
                () ->
                    f.sessions.submit(
                        CODE, impostor, "IMPOSTOR_GUESS", guessPhase, Map.of("text", "again"))))
        .isEqualTo(ErrorCode.STALE_ACTION);
    assertThat(rejected(() -> d.guess(impostor, "again"))).isEqualTo(ErrorCode.INVALID_PHASE);
  }

  @Test
  void DI11_anEmptyOrTooLongGuessIsValidationFailedAndTheGuessStaysOpen() {
    start5();
    toGuess();
    UUID impostor = d.impostor();

    assertThat(rejected(() -> d.guess(impostor, "  "))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> d.guess(impostor, "!!!"))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> d.guess(impostor, "x".repeat(81))))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(rejected(() -> d.send(impostor, "IMPOSTOR_GUESS", Map.of())))
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(d.phase()).isEqualTo("GUESS");
  }

  @Test
  void DI11_noGuessByTheDeadlineCountsAsWrong() {
    start5();
    toGuess();

    d.advance(GUESS);

    Map<String, Object> ended = last("IMPOSTOR_ROUND_ENDED");
    assertThat(ended).containsEntry("outcome", "CAUGHT").containsEntry("guess", null);
    assertThat(d.phase()).isEqualTo("ROUND_END");
  }

  // ------------------------------------------------------------------ scoring and the reveal

  @Test
  void DI12_aCaughtAndWrongRoundScoresEveryArtistAndTheFinalVotersOfTheImpostor() {
    start5();
    d.toVote();
    UUID impostor = d.impostor();
    List<UUID> artists = d.artists();
    d.vote(impostor, artists.get(0));
    d.vote(artists.get(0), impostor);
    d.vote(artists.get(1), impostor);
    d.vote(artists.get(2), impostor);
    d.vote(artists.get(3), artists.get(0)); // wrong vote: no bonus
    d.advance(Duration.ofSeconds(15));

    d.advance(GUESS);

    Map<UUID, Integer> deltas = field(last("IMPOSTOR_ROUND_ENDED"), "scoreDeltas");
    assertThat(deltas)
        .containsEntry(impostor, 0)
        .containsEntry(artists.get(0), 2)
        .containsEntry(artists.get(1), 2)
        .containsEntry(artists.get(2), 2)
        .containsEntry(artists.get(3), 1);
    assertThat(scores()).isEqualTo(field(last("IMPOSTOR_ROUND_ENDED"), "scores"));
  }

  @Test
  void DI12_anArtistWhoLeftBeforeTheRoundEndedGetsNothing() {
    start5();
    d.toVote();
    UUID impostor = d.impostor();
    List<UUID> artists = d.artists();
    d.everyoneVotes(impostor);
    d.advance(Duration.ofSeconds(15));
    UUID gone = artists.get(0);
    d.leave(gone);

    d.advance(GUESS);

    Map<UUID, Integer> deltas = field(last("IMPOSTOR_ROUND_ENDED"), "scoreDeltas");
    assertThat(deltas).containsEntry(gone, 0).containsEntry(artists.get(1), 2);
    Map<UUID, UUID> votes = field(last("IMPOSTOR_ROUND_ENDED"), "votes");
    assertThat(votes).doesNotContainKey(gone);
  }

  @Test
  void DI14_theRoundEndMatchesTheContractExactlyAndTheSnapshotCarriesTheSame() {
    start5();
    toGuess();
    UUID impostor = d.impostor();
    String word = d.word();
    Map<UUID, UUID> votes = field(last("IMPOSTOR_VOTE_CLOSED"), "votes");

    d.guess(impostor, "wrong answer");

    Map<String, Object> ended = last("IMPOSTOR_ROUND_ENDED");
    assertThat(ended.keySet())
        .containsExactly(
            "round",
            "outcome",
            "impostorGuestId",
            "category",
            "word",
            "decoyWord",
            "votes",
            "guess",
            "scoreDeltas",
            "scores");
    assertThat(ended)
        .containsEntry("round", 1)
        .containsEntry("outcome", "CAUGHT")
        .containsEntry("impostorGuestId", impostor)
        .containsEntry("word", word)
        .containsEntry("decoyWord", null)
        .containsEntry("votes", votes)
        .containsEntry("guess", "wrong answer");
    assertThat(get(ended, "category")).isEqualTo(roleCategory());
    assertThat(get(ended, "scoreDeltas")).isInstanceOf(Map.class);
    assertThat((Map<?, ?>) field(ended, "scoreDeltas")).hasSize(5);
    // The closing events precede the ROUND_END phase.
    List<Published> events = f.sink.events;
    assertThat(events.indexOf(f.sink.ofType("IMPOSTOR_ROUND_ENDED").getLast()))
        .isLessThan(events.indexOf(f.sink.ofType("PHASE_STARTED").getLast()));
    assertThat(d.lastPhase()).containsEntry("phase", "ROUND_END").containsKey("deadlineAt");
    for (UUID p : d.players) {
      assertThat(d.snapshot(p)).containsEntry("roundEnd", ended);
    }
  }

  private Object roleCategory() {
    return d.roles().get(d.impostor()).get("category");
  }

  @Test
  void DI14_aFullGameRecordsEachRoundAndFinishesWithTheStandingsDetail() {
    start5();
    // Round 1: escaped.
    d.toVote();
    UUID impostor1 = d.impostor();
    d.advance(VOTE);
    d.advance(ROUND_END);
    // Round 2: caught and the impostor guesses right.
    assertThat(d.lastPhase()).containsEntry("phase", "ROLE_REVEAL").containsEntry("round", 2);
    toGuess();
    UUID impostor2 = d.impostor();
    d.guess(impostor2, d.word());
    d.advance(ROUND_END);
    // Round 3: caught and wrong.
    toGuess();
    UUID impostor3 = d.impostor();
    List<UUID> artists3 = d.artists();
    d.advance(GUESS);
    d.advance(ROUND_END);

    assertThat(f.sink.ofType("IMPOSTOR_ROUND_ENDED"))
        .extracting(e -> e.payload().get("outcome"))
        .containsExactly("ESCAPED", "GUESSED", "CAUGHT");
    List<RoundRecord> records = f.recorder.rounds;
    assertThat(records).extracting(RoundRecord::roundNo).containsExactly(1, 2, 3);
    assertThat(records)
        .extracting(RoundRecord::endReason)
        .containsExactly("ESCAPED", "GUESSED", "CAUGHT");
    RoundRecord first = records.getFirst();
    assertThat(first.meta().keySet())
        .containsExactly(
            "impostorGuestId", "word", "category", "decoyWord", "outcome", "accusedGuestId");
    assertThat(first.meta())
        .containsEntry("impostorGuestId", impostor1)
        .containsEntry("decoyWord", null)
        .containsEntry("outcome", "ESCAPED")
        .containsEntry("accusedGuestId", null);
    assertThat(first.scoreDeltas()).containsEntry(impostor1, 2);
    assertThat(first.endedAt()).isAfter(first.startedAt());
    assertThat(records.get(2).meta()).containsEntry("accusedGuestId", impostor3);

    Map<String, Object> finished = last("GAME_FINISHED");
    assertThat(finished).containsEntry("endReason", "COMPLETED");
    List<Map<String, Object>> standings = field(finished, "standings");
    Map<UUID, Map<String, Object>> byGuest = new HashMap<>();
    standings.forEach(s -> byGuest.put((UUID) s.get("guestId"), s));
    assertThat(standings).hasSize(5);
    for (UUID g : d.players) {
      Map<String, Object> detail = field(byGuest.get(g), "detail");
      assertThat(detail.keySet())
          .containsExactly("roundsAsImpostor", "escapes", "correctGuesses", "impostorsFound");
    }
    assertThat((Map<String, Object>) field(byGuest.get(impostor1), "detail"))
        .containsEntry("escapes", 1);
    assertThat((Map<String, Object>) field(byGuest.get(impostor2), "detail"))
        .containsEntry("correctGuesses", 1);
    // Everyone but the impostor voted for the impostor of round 3, so each found one.
    for (UUID artist : artists3) {
      assertThat((Integer) field(field(byGuest.get(artist), "detail"), "impostorsFound"))
          .isGreaterThanOrEqualTo(1);
    }
    assertThat(f.room.status).isEqualTo(RoomStatus.RESULTS);
  }

  @Test
  void DI14_afterTheRevealTheNextRoundStartsWithFreshVotingState() {
    start5();
    d.toVote();
    d.chat(artist(0), "hello");
    d.vote(artist(0), artist(1));
    d.advance(VOTE);
    assertThat(d.phase()).isEqualTo("ROUND_END");
    d.advance(ROUND_END);

    assertThat(d.lastPhase()).containsEntry("phase", "ROLE_REVEAL").containsEntry("round", 2);
    Map<String, Object> view = d.snapshot(d.players.getFirst());
    assertThat(view)
        .containsEntry("votedGuestIds", List.of())
        .containsEntry("myVote", null)
        .containsEntry("recentChat", List.of())
        .containsEntry("voteClosed", null)
        .containsEntry("roundEnd", null);
  }

  // ------------------------------------------------------------------ leaving

  private void moveTo(String phase) {
    switch (phase) {
      case "ROLE_REVEAL" -> {}
      case "DRAWING" -> d.advance(REVEAL);
      case "VOTE" -> d.toVote();
      case "GUESS" -> toGuess();
      case "ROUND_END" -> {
        d.toVote();
        d.advance(VOTE);
      }
      default -> throw new AssertionError(phase);
    }
    assertThat(d.phase()).isEqualTo(phase);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ROLE_REVEAL", "DRAWING", "VOTE", "GUESS"})
  void DI13_theImpostorLeavingEndsTheRoundAtOnceWithNoPointsAndTheNextRoundStarts(String phase) {
    start5();
    moveTo(phase);
    UUID impostor = d.impostor();
    String word = d.word();
    Map<UUID, Integer> before = scores();

    d.leave(impostor);

    assertThat(d.phase()).isEqualTo("ROUND_END");
    Map<String, Object> ended = last("IMPOSTOR_ROUND_ENDED");
    assertThat(ended)
        .containsEntry("outcome", "IMPOSTOR_LEFT")
        .containsEntry("impostorGuestId", impostor)
        .containsEntry("word", word)
        .containsEntry("guess", null);
    assertThat(((Map<UUID, Integer>) field(ended, "scoreDeltas")).values()).containsOnly(0);
    assertThat(scores()).isEqualTo(before);
    assertThat(f.recorder.rounds)
        .singleElement()
        .satisfies(r -> assertThat(r.endReason()).isEqualTo("IMPOSTOR_LEFT"));

    d.advance(ROUND_END);

    assertThat(d.lastPhase()).containsEntry("phase", "ROLE_REVEAL").containsEntry("round", 2);
    assertThat(d.roles()).hasSize(4).doesNotContainKey(impostor);
    assertThat(f.sink.ofType("GAME_FINISHED")).isEmpty();
  }

  @Test
  void DI13_theImpostorLeavingAfterTheVoteClosedKeepsTheVoteClosedPayload() {
    start5();
    toGuess();
    Map<String, Object> closed = last("IMPOSTOR_VOTE_CLOSED");

    d.leave(d.impostor());

    assertThat(d.snapshot(d.seated().getFirst())).containsEntry("voteClosed", closed);
    assertThat(get(last("IMPOSTOR_ROUND_ENDED"), "outcome")).isEqualTo("IMPOSTOR_LEFT");
  }

  @Test
  void DI13_theImpostorLeavingInTheRoundEndChangesNothing() {
    start5();
    moveTo("ROUND_END");
    int events = f.sink.events.size();
    int records = f.recorder.rounds.size();
    UUID impostor = d.impostor();

    d.leave(impostor);

    assertThat(f.sink.events).hasSize(events);
    assertThat(f.recorder.rounds).hasSize(records);
    assertThat(d.phase()).isEqualTo("ROUND_END");
    d.advance(ROUND_END);
    assertThat(d.lastPhase()).containsEntry("round", 2);
    assertThat(d.roles()).doesNotContainKey(impostor);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ROLE_REVEAL", "DRAWING", "VOTE", "GUESS", "ROUND_END"})
  void DI13_anArtistLeavingIsDroppedAndTheRoundGoesOn(String phase) {
    start5();
    moveTo(phase);
    UUID leaver = d.artists().get(1);
    String before = d.phase();

    d.leave(leaver);

    assertThat(d.phase()).isEqualTo(before);
    assertThat(f.sink.ofType("GAME_FINISHED")).isEmpty();
    assertThat(f.sink.ofType("IMPOSTOR_ROUND_ENDED")).hasSize(phase.equals("ROUND_END") ? 1 : 0);
  }

  @Test
  void C1_fewerThanThreeLeftBeforeAnyRoundEndsAbortsTheGame() {
    f = fixture(2);
    d = new ImpostorDriver(f);
    d.start(4, Map.of("rounds", 3));
    d.leave(d.artists().get(0));
    assertThat(f.sink.ofType("GAME_ABORTED")).isEmpty();

    d.leave(d.artists().get(0));

    assertThat(f.sink.ofType("GAME_ABORTED")).hasSize(1);
    assertThat(f.sink.ofType("GAME_FINISHED")).isEmpty();
  }

  @Test
  void C1_fewerThanThreeLeftAfterARoundEndedFinishesWithResults() {
    f = fixture(3);
    d = new ImpostorDriver(f);
    d.start(4, Map.of("rounds", 3, "laps", 1));
    d.toVote();
    d.advance(VOTE);
    d.advance(ROUND_END);
    d.leave(d.artists().get(0));
    assertThat(f.sink.ofType("GAME_FINISHED")).isEmpty();

    d.leave(d.artists().get(0));

    assertThat(last("GAME_FINISHED")).containsEntry("endReason", "INSUFFICIENT_PLAYERS");
    assertThat(f.room.status).isEqualTo(RoomStatus.RESULTS);
  }

  @Test
  void C1_theImpostorLeavingDownToTwoRecordsTheRoundThenFinishes() {
    f = fixture(4);
    d = new ImpostorDriver(f);
    d.start(4, Map.of("rounds", 3));
    d.leave(d.artists().get(0)); // 3 left, still fine
    d.leave(d.impostor()); // 2 left: the round is recorded, then the game finishes

    assertThat(f.recorder.rounds).hasSize(1);
    assertThat(last("GAME_FINISHED")).containsEntry("endReason", "INSUFFICIENT_PLAYERS");
  }

  // ------------------------------------------------------------------ contract fix G1, G2, G4

  @Test
  void G1_theRoleRevealPhaseStartedCarriesTheSeatsAndColoursForAClientWithoutASnapshot() {
    start5();

    Map<String, Object> data = d.lastPhase();
    assertThat(data).containsEntry("phase", "ROLE_REVEAL");
    Map<String, Object> view = d.snapshot(d.players.getFirst());
    assertThat(data.get("seatOrder")).isEqualTo(view.get("seatOrder"));
    assertThat(data.get("colors")).isEqualTo(view.get("colors"));
    assertThat((Map<?, ?>) data.get("colors")).hasSize(5);
  }

  @Test
  void G2_theNicknamesOfTheWholeRosterIncludingThoseWhoLeftAreInTheSnapshot() {
    start5();
    UUID gone = d.artists().get(2);
    String nickname =
        f.room.members.stream()
            .filter(m -> m.guestId().equals(gone))
            .findFirst()
            .orElseThrow()
            .nickname();
    d.leave(gone);

    Map<UUID, String> names = field(d.snapshot(d.seated().getFirst()), "nicknames");

    assertThat(names).containsOnlyKeys(d.players).containsEntry(gone, nickname);
    assertThat(names.values()).doesNotContainNull();
  }

  @Test
  void G4_aReconnectingDrawerFindsTheOpenStrokeAndContinuesItToTheEndOfTheTurn() {
    start5();
    d.advance(REVEAL);
    UUID drawer = (UUID) d.lastPhase().get("drawerGuestId");
    assertThat(d.snapshot(drawer)).containsEntry("turnStroke", null);
    UUID stroke = UUID.randomUUID();
    Map<UUID, String> colors = field(d.snapshot(drawer), "colors");
    String color = colors.get(drawer);
    f.sessions.submit(
        CODE, drawer, "IMPOSTOR_DRAW_STROKE", f.phaseId(), batch(stroke, 0, color, false));
    f.sessions.submit(
        CODE, drawer, "IMPOSTOR_DRAW_STROKE", f.phaseId(), batch(stroke, 1, color, false));

    Map<String, Object> turnStroke = field(d.snapshot(d.players.get(1)), "turnStroke");
    assertThat(turnStroke)
        .containsEntry("strokeId", stroke)
        .containsEntry("nextBatchNo", 2)
        .containsEntry("closed", false);

    int turn = (Integer) d.lastPhase().get("turn");
    f.sessions.submit(
        CODE,
        drawer,
        "IMPOSTOR_DRAW_STROKE",
        f.phaseId(),
        batch(stroke, (Integer) turnStroke.get("nextBatchNo"), color, true));

    assertThat((Integer) d.lastPhase().get("turn")).isEqualTo(turn + 1);
    assertThat(d.snapshot(drawer)).containsEntry("turnStroke", null);
  }

  private static Map<String, Object> batch(UUID strokeId, int batchNo, String color, boolean last) {
    List<List<Integer>> pts = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      pts.add(List.of(100 * i, 50));
    }
    Map<String, Object> p = new HashMap<>();
    p.put("strokeId", strokeId.toString());
    p.put("batchNo", batchNo);
    p.put("tool", "PENCIL");
    p.put("color", color);
    p.put("size", 2);
    p.put("points", pts);
    p.put("final", last);
    return p;
  }

  // ------------------------------------------------------------------ rematch

  @Test
  void C3_aRematchStartsANewSessionWithTheRotationCountsAtZero() {
    f = fixture(6);
    d = new ImpostorDriver(f);
    d.start(4, Map.of("rounds", 4, "laps", 1));
    Map<UUID, Integer> asImpostor = new HashMap<>();
    for (int round = 1; round <= 4; round++) {
      asImpostor.merge(d.impostor(), 1, Integer::sum);
      d.toVote();
      d.advance(VOTE);
      d.advance(ROUND_END);
    }
    assertThat(asImpostor.values()).containsOnly(1); // [DI-3] once each over n rounds
    UUID oldSession = (UUID) last("GAME_FINISHED").get("sessionId");

    f.sessions.rematchVote(CODE, f.host(), true);
    f.startAndPlay();

    assertThat(last("GAME_STARTED").get("sessionId")).isNotEqualTo(oldSession);
    assertThat(scores().values()).containsOnly(0);
    assertThat(d.lastPhase()).containsEntry("phase", "ROLE_REVEAL").containsEntry("round", 1);
    Map<UUID, Integer> again = new HashMap<>();
    for (int round = 1; round <= 4; round++) {
      again.merge(d.impostor(), 1, Integer::sum);
      d.toVote();
      d.advance(VOTE);
      d.advance(ROUND_END);
    }
    assertThat(again).containsOnlyKeys(d.players);
    assertThat(again.values()).containsOnly(1);
  }
}
