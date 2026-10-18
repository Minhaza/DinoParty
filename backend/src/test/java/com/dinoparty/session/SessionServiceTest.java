package com.dinoparty.session;

import static com.dinoparty.session.SessionFixture.CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.engine.DeadlineFired;
import com.dinoparty.engine.GameContext;
import com.dinoparty.engine.GameState;
import com.dinoparty.engine.OutboundEvent;
import com.dinoparty.engine.PhaseChange;
import com.dinoparty.engine.PlayerAction;
import com.dinoparty.engine.Transition;
import com.dinoparty.games.testgame.TestGameModule;
import com.dinoparty.room.RoomStatus;
import com.dinoparty.session.SessionFixture.Published;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Engine behavior with a manual clock and in-memory collaborators: the transition table of
 * docs/game-engine.md §2.1, the action pipeline and the deadline races of §5.2 ([C-4]..[C-12]).
 */
class SessionServiceTest {

  private final SessionFixture f = new SessionFixture();

  private void score(UUID guest, String phaseId, int points) {
    f.sessions.submit(CODE, guest, "TEST_SCORE", phaseId, Map.of("points", points));
  }

  private static DomainException rejected(Runnable action) {
    try {
      action.run();
    } catch (DomainException e) {
      return e;
    }
    throw new AssertionError("expected a DomainException");
  }

  // ------------------------------------------------------------------ start and countdown

  @Test
  void onlyTheHostCanStart() {
    List<UUID> p = f.players(3);

    assertThat(rejected(() -> f.sessions.start(CODE, p.get(1))).code())
        .isEqualTo(ErrorCode.NOT_HOST);
    assertThat(f.room.status).isEqualTo(RoomStatus.WAITING);
    assertThat(f.sessions.activeCount()).isZero();
  }

  @Test
  void startNeedsEnoughConnectedPlayers() {
    f.players(1);
    f.room.addMember(false); // present but disconnected: does not count [C-4]

    assertThat(rejected(() -> f.sessions.start(CODE, f.host())).code())
        .isEqualTo(ErrorCode.NOT_ENOUGH_PLAYERS);
    assertThat(f.room.status).isEqualTo(RoomStatus.WAITING);
  }

  @Test
  void unavailableGameCannotStart() {
    f.players(2);
    f.room.gameKey = "draw-guess"; // no module registered in this fixture

    assertThat(rejected(() -> f.sessions.start(CODE, f.host())).code())
        .isEqualTo(ErrorCode.GAME_NOT_FOUND);
  }

  @Test
  void repeatedStartKeepsOneCountdown() {
    f.players(2);

    f.sessions.start(CODE, f.host());
    f.sessions.start(CODE, f.host());

    assertThat(f.room.reasons()).containsExactly("STARTING:HOST_STARTED");
    assertThat(f.scheduler.liveHandles()).isEqualTo(1);
    assertThat(f.sessions.activeCount()).isEqualTo(1);
  }

  @Test
  void startWhilePlayingIsAnInvalidPhase() {
    f.players(2);
    f.startAndPlay();

    assertThat(rejected(() -> f.sessions.start(CODE, f.host())).code())
        .isEqualTo(ErrorCode.INVALID_PHASE);
  }

  @Test
  void aStartRejectedByTheModuleInitializesNothing() {
    f.players(2);
    f.room.settings = Map.of("rejectStart", true);

    DomainException e = rejected(() -> f.sessions.start(CODE, f.host()));

    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(e.fieldErrors()).extracting("field").containsExactly("settings.rejectStart");
    assertThat(f.room.status).isEqualTo(RoomStatus.WAITING);
    assertThat(f.room.changes).isEmpty();
    assertThat(f.sink.events).isEmpty();
    assertThat(f.sessions.activeCount()).isZero();
    assertThat(f.scheduler.liveHandles()).isZero();

    // Fixing the settings makes the next start work normally.
    f.room.settings = Map.of();
    f.startAndPlay();
    assertThat(f.room.status).isEqualTo(RoomStatus.PLAYING);
  }

  @Test
  void aModuleFailureWhilePreparingIsAServerErrorAndInitializesNothing() {
    f.players(2);
    f.room.settings = Map.of("crashStart", true);

    assertThat(rejected(() -> f.sessions.start(CODE, f.host())).code())
        .isEqualTo(ErrorCode.SERVER_ERROR);
    assertThat(f.room.changes).isEmpty();
    assertThat(f.sessions.activeCount()).isZero();
  }

  @Test
  void settingsAndSetupAreFrozenWhenTheHostStarts() {
    f.players(2);
    f.room.settings = Map.of("secretPrefix", "alpha");
    f.sessions.start(CODE, f.host());

    f.room.settings = Map.of("secretPrefix", "beta"); // a change during the countdown
    f.clock.advance(Duration.ofSeconds(3));
    f.scheduler.fireDue(f.clock, false);

    assertThat(f.sink.ofType("GAME_STARTED").getFirst().payload().get("settings"))
        .isEqualTo(Map.of("secretPrefix", "alpha"));
    assertThat((String) f.sink.ofType("TEST_SECRET").getFirst().payload().get("secret"))
        .startsWith("alpha-");
    assertThat(f.recorder.starts.getFirst().settings()).containsEntry("secretPrefix", "alpha");
  }

  @Test
  void countdownEndStartsTheGameInTheDocumentedEventOrder() {
    List<UUID> p = f.players(3);

    f.startAndPlay();

    assertThat(f.room.reasons()).containsExactly("STARTING:HOST_STARTED", "PLAYING:COUNTDOWN_DONE");
    assertThat(f.sink.publicTypes()).containsExactly("GAME_STARTED", "PHASE_STARTED");
    assertThat(f.recorder.starts).hasSize(1);
    Map<String, Object> started = f.sink.ofType("GAME_STARTED").getFirst().payload();
    @SuppressWarnings("unchecked")
    List<UUID> order = (List<UUID>) started.get("order");
    assertThat(order).containsExactlyInAnyOrderElementsOf(p);
    // The secret goes only to the turn player, after the public PHASE_STARTED.
    List<Published> secrets = f.sink.ofType("TEST_SECRET");
    assertThat(secrets).hasSize(1);
    assertThat(secrets.getFirst().recipient()).isEqualTo(order.getFirst());
    assertThat(f.sink.events.indexOf(secrets.getFirst()))
        .isGreaterThan(f.sink.events.indexOf(f.sink.ofType("PHASE_STARTED").getFirst()));
  }

  @Test
  void countdownIsCancelledWhenTooFewPlayersStayConnected() {
    List<UUID> p = f.players(2);
    f.sessions.start(CODE, f.host());

    f.room.setConnected(p.get(1), false);
    f.sessions.onConnection(CODE, p.get(1), false);

    assertThat(f.room.reasons())
        .containsExactly("STARTING:HOST_STARTED", "WAITING:START_CANCELLED");
    assertThat(f.sessions.activeCount()).isZero();
    assertThat(f.scheduler.liveHandles()).isZero();
    f.clock.advance(Duration.ofSeconds(5));
    f.scheduler.fireDue(f.clock, true); // the cancelled timer firing anyway must do nothing
    assertThat(f.room.status).isEqualTo(RoomStatus.WAITING);
  }

  @Test
  void countdownIsCancelledWhenAPlayerLeaves() {
    List<UUID> p = f.players(2);
    f.sessions.start(CODE, f.host());

    f.room.removeMember(p.get(1));
    f.sessions.onMemberLeft(CODE, p.get(1));

    assertThat(f.room.reasons()).contains("WAITING:START_CANCELLED");
    assertThat(f.sessions.activeCount()).isZero();
  }

  @Test
  void aDisconnectedMemberAtCountdownEndStillStartsIfEnoughRemain() {
    List<UUID> p = f.players(3);
    f.sessions.start(CODE, f.host());
    f.room.setConnected(p.get(2), false);
    f.sessions.onConnection(CODE, p.get(2), false);

    f.clock.advance(Duration.ofSeconds(3));
    f.scheduler.fireDue(f.clock, false);

    assertThat(f.room.status).isEqualTo(RoomStatus.PLAYING);
    Map<String, Object> snapshot = f.sessions.gameSnapshot(CODE, p.get(0));
    assertThat(snapshot.get("players").toString()).contains("DISCONNECTED");
  }

  @Test
  void aFailedSessionInsertReturnsTheRoomToTheLobby() {
    f.players(2);
    f.recorder.failStart = true;

    f.sessions.start(CODE, f.host());
    f.clock.advance(Duration.ofSeconds(3));
    f.scheduler.fireDue(f.clock, false);

    assertThat(f.room.reasons()).containsExactly("STARTING:HOST_STARTED", "WAITING:GAME_ABORTED");
    assertThat(f.sink.publicTypes()).containsExactly("GAME_ABORTED");
    assertThat(f.sessions.activeCount()).isZero();
  }

  // ------------------------------------------------------------------ action pipeline

  @Test
  void anAcceptedActionScoresAndIsPublished() {
    List<UUID> p = f.players(2);
    f.startAndPlay();

    score(p.get(1), f.phaseId(), 3);

    assertThat(f.sessions.info(CODE).scores()).containsEntry(p.get(1), 3);
    assertThat(f.sink.ofType("TEST_SCORED")).hasSize(1);
  }

  @Test
  void anActionForAnEarlierPhaseIsStale() { // [C-5]
    List<UUID> p = f.players(2);
    f.startAndPlay();
    String old = f.phaseId();
    UUID turn = (UUID) f.sink.ofType("PHASE_STARTED").getLast().payload().get("turnGuestId");
    f.sessions.submit(CODE, turn, "TEST_NEXT", old, Map.of()); // phase 2 starts

    DomainException e = rejected(() -> score(p.get(0), old, 1));

    assertThat(e.code()).isEqualTo(ErrorCode.STALE_ACTION);
    assertThat(f.sessions.info(CODE).scores().values()).allMatch(v -> v == 0);
  }

  @Test
  void aMissingPhaseIdIsAValidationError() {
    List<UUID> p = f.players(2);
    f.startAndPlay();

    assertThat(rejected(() -> score(p.get(0), null, 1)).code())
        .isEqualTo(ErrorCode.VALIDATION_FAILED);
  }

  @Test
  void aCommandOutsideItsPhasesOrOfAnotherGameIsRefused() {
    List<UUID> p = f.players(2);
    f.startAndPlay();

    assertThat(
            rejected(() -> f.sessions.submit(CODE, p.get(0), "SUBMIT_GUESS", f.phaseId(), Map.of()))
                .code())
        .isEqualTo(ErrorCode.INVALID_ACTION);
  }

  @Test
  void aModuleRejectionLeavesTheStateUntouched() {
    List<UUID> p = f.players(2);
    f.startAndPlay();
    Map<String, Object> before = f.sessions.gameSnapshot(CODE, p.get(0));
    int events = f.sink.events.size();

    DomainException e =
        rejected(
            () ->
                f.sessions.submit(CODE, p.get(0), "TEST_SCORE", f.phaseId(), Map.of("points", 99)));
    UUID notTurn =
        p.stream()
            .filter(g -> !g.equals(f.sink.ofType("TEST_SECRET").getFirst().recipient()))
            .findFirst()
            .orElseThrow();
    DomainException forbidden =
        rejected(() -> f.sessions.submit(CODE, notTurn, "TEST_NEXT", f.phaseId(), Map.of()));

    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ACTION);
    assertThat(forbidden.code()).isEqualTo(ErrorCode.FORBIDDEN);
    assertThat(f.sessions.gameSnapshot(CODE, p.get(0))).isEqualTo(before);
    assertThat(f.sink.events).hasSize(events);
  }

  @Test
  void aStrangerOrALeftPlayerIsNotInTheRoom() {
    List<UUID> p = f.players(3);
    f.startAndPlay();

    assertThat(rejected(() -> f.sessions.authorize(CODE, UUID.randomUUID())).code())
        .isEqualTo(ErrorCode.NOT_IN_ROOM);

    f.room.removeMember(p.get(2));
    f.sessions.onMemberLeft(CODE, p.get(2));
    assertThat(rejected(() -> f.sessions.authorize(CODE, p.get(2))).code())
        .isEqualTo(ErrorCode.NOT_IN_ROOM);
    assertThat(rejected(() -> score(p.get(2), f.phaseId(), 1)).code())
        .isEqualTo(ErrorCode.NOT_IN_ROOM);
  }

  @Test
  void commandsWithoutASessionNeedMembershipThenAreInvalidPhase() {
    List<UUID> p = f.players(2);

    assertThat(rejected(() -> score(p.get(0), UUID.randomUUID().toString(), 1)).code())
        .isEqualTo(ErrorCode.INVALID_PHASE);
    assertThat(rejected(() -> score(UUID.randomUUID(), UUID.randomUUID().toString(), 1)).code())
        .isEqualTo(ErrorCode.NOT_IN_ROOM);
  }

  // ------------------------------------------------------------------ deadlines [C-6]

  @Test
  void anActionExactlyAtTheDeadlineIsStaleAndOneMillisecondBeforeIsAccepted() {
    List<UUID> p = f.players(2);
    f.startAndPlay();
    String phase = f.phaseId();

    f.clock.advance(Duration.ofMillis(59_999));
    score(p.get(0), phase, 1); // accepted: 1 ms before the deadline

    f.clock.advance(Duration.ofMillis(1)); // now == deadline, no timer fired yet
    assertThat(rejected(() -> score(p.get(0), phase, 1)).code()).isEqualTo(ErrorCode.STALE_ACTION);
    assertThat(f.sessions.info(CODE).scores().get(p.get(0))).isEqualTo(1);
    assertThat(f.sink.ofType("PHASE_STARTED")).hasSize(2); // the deadline was applied first
  }

  @Test
  void aPhaseChangedByAnActionCancelsTheOldDeadline() {
    f.players(2);
    f.startAndPlay();
    UUID turn = f.sink.ofType("TEST_SECRET").getFirst().recipient();
    String phase1 = f.phaseId();
    f.sessions.submit(CODE, turn, "TEST_NEXT", phase1, Map.of());
    String phase2 = f.phaseId();

    f.clock.advance(Duration.ofSeconds(60)); // the old round deadline would be due now
    f.scheduler.fireDue(f.clock, true); // including the cancelled timer

    // The new phase has its own deadline 60 s after it started, i.e. also due now: exactly one
    // transition happened, driven by the new phase, never by the cancelled one.
    assertThat(phase2).isNotEqualTo(phase1);
    assertThat(f.sink.ofType("TEST_ROUND_ENDED")).hasSize(2);
    assertThat(f.sink.ofType("TEST_ROUND_ENDED").getFirst().payload().get("reason"))
        .isEqualTo("NEXT");
    assertThat(f.sink.ofType("TEST_ROUND_ENDED").getLast().payload().get("reason"))
        .isEqualTo("TIME");
  }

  @Test
  void duplicateAndSpuriousWakeUpsDoNothing() {
    f.players(2);
    f.startAndPlay();

    f.sessions.wake(CODE, UUID.randomUUID()); // unknown session instance
    f.sessions.wake(CODE, UUID.randomUUID());
    f.sessions.wake("ZZZ999", UUID.randomUUID()); // unknown room
    f.clock.advance(Duration.ofSeconds(59));
    f.scheduler.fireDue(f.clock, true); // nothing is due yet

    assertThat(f.sink.ofType("TEST_ROUND_ENDED")).isEmpty();
  }

  @Test
  void twoWakeUpsForOneDeadlineRunItOnce() {
    f.players(2);
    f.startAndPlay();
    f.clock.advance(Duration.ofSeconds(60));
    UUID instance = f.scheduler.armed.getLast().instance();

    f.sessions.wake(CODE, instance);
    f.sessions.wake(CODE, instance);

    assertThat(f.sink.ofType("TEST_ROUND_ENDED")).hasSize(1);
  }

  @Test
  void aWakeUpAfterAbortIsANoOp() {
    List<UUID> p = f.players(2);
    f.startAndPlay();
    UUID instance = f.scheduler.armed.getLast().instance();
    f.room.removeMember(p.get(1));
    f.sessions.onMemberLeft(CODE, p.get(1)); // below the minimum with no completed round: abort
    int events = f.sink.events.size();

    f.clock.advance(Duration.ofMinutes(2));
    f.sessions.wake(CODE, instance);

    assertThat(f.sink.events).hasSize(events);
    assertThat(f.sessions.activeCount()).isZero();
  }

  @Test
  void theWatchdogDrainsADeadlineWhoseTimerWasLost() {
    f.players(2);
    f.startAndPlay();
    f.clock.advance(Duration.ofSeconds(62)); // 2 s overdue, and no timer ever fires

    f.sessions.watchdog();

    assertThat(f.sink.ofType("TEST_ROUND_ENDED")).hasSize(1);
    assertThat(f.sink.ofType("PHASE_STARTED")).hasSize(2);
  }

  @Test
  void theWatchdogLeavesADeadlineThatIsOnlySlightlyLateToItsTimer() {
    f.players(2);
    f.startAndPlay();
    f.clock.advance(Duration.ofMillis(60_500)); // 0.5 s late: within the tolerance

    f.sessions.watchdog();

    assertThat(f.sink.ofType("TEST_ROUND_ENDED")).isEmpty();
  }

  @Test
  void aSnapshotNeverShowsAnOverduePhase() {
    List<UUID> p = f.players(2);
    f.startAndPlay();

    f.clock.advance(Duration.ofSeconds(61)); // no timer has fired

    Map<String, Object> snapshot = f.sessions.gameSnapshot(CODE, p.get(0));
    assertThat(snapshot.get("phaseId").toString()).isEqualTo(f.phaseId());
    assertThat(snapshot.get("round")).isEqualTo(2);
  }

  @Test
  void aStormOfActionsRacingTheDeadlineStaysConsistent() throws Exception {
    List<UUID> p = f.players(4);
    f.startAndPlay();
    AtomicInteger accepted = new AtomicInteger();
    AtomicInteger stale = new AtomicInteger();
    int threads = 200;
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(32);
    String phase = f.phaseId();
    for (int i = 0; i < threads; i++) {
      UUID guest = p.get(i % p.size());
      pool.submit(
          () -> {
            go.await();
            try {
              score(guest, phase, 1);
              accepted.incrementAndGet();
            } catch (DomainException e) {
              if (e.code() == ErrorCode.STALE_ACTION) {
                stale.incrementAndGet();
              } else {
                throw e;
              }
            }
            return null;
          });
    }
    pool.submit(
        () -> {
          go.await();
          f.clock.advance(Duration.ofSeconds(61)); // the deadline passes in the middle of the storm
          return null;
        });
    go.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    // Every command was either applied before the phase ended or refused as stale, never lost.
    assertThat(accepted.get() + stale.get()).isEqualTo(threads);
    int total = f.sessions.info(CODE).scores().values().stream().mapToInt(Integer::intValue).sum();
    assertThat(total).isEqualTo(accepted.get());
    assertThat(f.sink.ofType("TEST_SCORED")).hasSize(accepted.get());
  }

  // ------------------------------------------------------------------ finish, results, rematch

  private void playToTheEnd() {
    f.startAndPlay();
    f.clock.advance(Duration.ofSeconds(60));
    f.scheduler.fireDue(f.clock, false); // round 1 ends by time, round 2 starts
    f.clock.advance(Duration.ofSeconds(60));
    f.scheduler.fireDue(f.clock, false); // round 2 ends, game over
  }

  @Test
  void aFinishedGameReportsRankedStandingsAndMovesToResults() { // [C-8]
    List<UUID> p = f.players(3);
    f.startAndPlay();
    score(p.get(0), f.phaseId(), 5);
    score(p.get(1), f.phaseId(), 5);
    score(p.get(2), f.phaseId(), 2);
    f.clock.advance(Duration.ofSeconds(60));
    f.scheduler.fireDue(f.clock, false);
    f.clock.advance(Duration.ofSeconds(60));
    f.scheduler.fireDue(f.clock, false);

    assertThat(f.room.status).isEqualTo(RoomStatus.RESULTS);
    assertThat(f.room.reasons().getLast()).isEqualTo("RESULTS:GAME_FINISHED");
    Published finished = f.sink.ofType("GAME_FINISHED").getFirst();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> standings =
        (List<Map<String, Object>>) finished.payload().get("standings");
    assertThat(standings.stream().map(s -> s.get("rank")).toList()).containsExactly(1, 1, 3);
    assertThat(finished.payload().get("endReason")).isEqualTo("COMPLETED");
    assertThat(f.recorder.rounds).hasSize(2);
    assertThat(f.recorder.results).hasSize(1);
    assertThat(f.recorder.results.getFirst().standings()).hasSize(3);
    // Every phase deadline is gone; only the results timeout remains.
    assertThat(f.scheduler.liveHandles()).isEqualTo(1);
    assertThat(f.sessions.gameSnapshot(CODE, p.get(0)).get("status")).isEqualTo("FINISHED");
  }

  @Test
  void theHostsYesVoteStartsARematchWithNothingCarriedOver() { // [C-3] [C-10]
    List<UUID> p = f.players(3);
    f.startAndPlay();
    score(p.get(1), f.phaseId(), 4);
    f.clock.advance(Duration.ofSeconds(60));
    f.scheduler.fireDue(f.clock, false);
    f.clock.advance(Duration.ofSeconds(60));
    f.scheduler.fireDue(f.clock, false);
    UUID firstSession = f.recorder.starts.getFirst().sessionId();

    f.sessions.rematchVote(CODE, p.get(1), false);
    assertThat(f.room.status).isEqualTo(RoomStatus.RESULTS); // a no never resets
    f.sessions.rematchVote(CODE, f.host(), true);

    assertThat(f.room.status).isEqualTo(RoomStatus.WAITING);
    assertThat(f.room.reasons().getLast()).isEqualTo("WAITING:REMATCH");
    assertThat(f.sessions.activeCount()).isZero();
    assertThat(f.sessions.info(CODE)).isEqualTo(RoomSessionInfo.NONE);
    assertThat(f.sessions.gameSnapshot(CODE, p.get(0))).isNull();
    assertThat(f.scheduler.liveHandles()).isZero();

    f.startAndPlay();
    assertThat(f.recorder.starts).hasSize(2);
    assertThat(f.recorder.starts.getLast().sessionId()).isNotEqualTo(firstSession);
    assertThat(f.sessions.info(CODE).scores().values()).allMatch(v -> v == 0);
  }

  @Test
  void everyConnectedMemberVotingYesResetsAndDisconnectedOnesAreIgnored() {
    List<UUID> p = f.players(3);
    playToTheEnd();
    f.room.setConnected(p.get(2), false);
    f.sessions.onConnection(CODE, p.get(2), false);

    f.sessions.rematchVote(CODE, p.get(1), true);
    assertThat(f.room.status).isEqualTo(RoomStatus.RESULTS);
    f.sessions.rematchVote(CODE, p.get(0), true); // host: resets anyway

    assertThat(f.room.status).isEqualTo(RoomStatus.WAITING);
  }

  @Test
  void aNonHostMajorityOfConnectedYesVotesResets() {
    List<UUID> p = f.players(3);
    playToTheEnd();
    f.room.setConnected(p.get(0), false); // the host is away
    f.sessions.onConnection(CODE, p.get(0), false);

    f.sessions.rematchVote(CODE, p.get(1), true);
    f.sessions.rematchVote(CODE, p.get(2), true);

    assertThat(f.room.status).isEqualTo(RoomStatus.WAITING);
  }

  @Test
  void aRematchVoteOutsideResultsIsInvalid() {
    List<UUID> p = f.players(2);
    f.startAndPlay();

    assertThat(rejected(() -> f.sessions.rematchVote(CODE, p.get(0), true)).code())
        .isEqualTo(ErrorCode.INVALID_PHASE);
  }

  @Test
  void theResultsTimeoutReturnsTheRoomToWaiting() { // D19
    f.players(2);
    playToTheEnd();

    f.clock.advance(Duration.ofMinutes(15));
    f.scheduler.fireDue(f.clock, false);

    assertThat(f.room.reasons().getLast()).isEqualTo("WAITING:RESULTS_TIMEOUT");
    assertThat(f.sessions.activeCount()).isZero();
  }

  // ------------------------------------------------------------------ leaving and aborting

  @Test
  void leavingBelowTheMinimumBeforeAnyRoundAbortsWithoutResults() { // [C-1]
    List<UUID> p = f.players(2);
    f.startAndPlay();

    f.room.removeMember(p.get(1));
    f.sessions.onMemberLeft(CODE, p.get(1));

    assertThat(f.sink.publicTypes()).contains("GAME_ABORTED").doesNotContain("GAME_FINISHED");
    assertThat(f.sink.ofType("GAME_ABORTED").getFirst().payload().get("reason"))
        .isEqualTo("NO_COMPLETED_ROUND");
    assertThat(f.room.reasons().getLast()).isEqualTo("WAITING:GAME_ABORTED");
    assertThat(f.recorder.aborts).containsExactly("NO_COMPLETED_ROUND");
    assertThat(f.sessions.activeCount()).isZero();
    assertThat(f.scheduler.liveHandles()).isZero();
  }

  @Test
  void leavingBelowTheMinimumAfterARoundFinishesWithResults() {
    List<UUID> p = f.players(2);
    f.startAndPlay();
    score(p.get(0), f.phaseId(), 3);
    f.clock.advance(Duration.ofSeconds(60));
    f.scheduler.fireDue(f.clock, false); // round 1 done

    f.room.removeMember(p.get(1));
    f.sessions.onMemberLeft(CODE, p.get(1));

    assertThat(f.sink.ofType("GAME_FINISHED").getFirst().payload().get("endReason"))
        .isEqualTo("INSUFFICIENT_PLAYERS");
    assertThat(f.room.status).isEqualTo(RoomStatus.RESULTS);
    assertThat(f.recorder.results).hasSize(1);
  }

  @Test
  void aTemporaryDisconnectNeverEndsTheGame() { // D23
    List<UUID> p = f.players(2);
    f.startAndPlay();

    f.room.setConnected(p.get(1), false);
    f.sessions.onConnection(CODE, p.get(1), false);

    assertThat(f.room.status).isEqualTo(RoomStatus.PLAYING);
    assertThat(f.sessions.gameSnapshot(CODE, p.get(0)).get("players").toString())
        .contains("DISCONNECTED");
    f.sessions.onConnection(CODE, p.get(1), true);
    assertThat(f.sessions.gameSnapshot(CODE, p.get(0)).get("players").toString())
        .doesNotContain("DISCONNECTED");
  }

  @Test
  void leftPlayersKeepTheirScoreAndTheirSlot() {
    List<UUID> p = f.players(3);
    f.startAndPlay();
    score(p.get(2), f.phaseId(), 4);

    f.room.removeMember(p.get(2));
    f.sessions.onMemberLeft(CODE, p.get(2));

    assertThat(f.sessions.info(CODE).scores()).containsEntry(p.get(2), 4);
    assertThat(f.sessions.gameSnapshot(CODE, p.get(0)).get("players").toString()).contains("LEFT");
  }

  @Test
  void closingTheRoomDropsTheSessionAndRecordsAnAbort() {
    f.players(2);
    f.startAndPlay();

    f.room.status = RoomStatus.CLOSED;
    f.sessions.onRoomClosed(CODE);

    assertThat(f.sessions.activeCount()).isZero();
    assertThat(f.recorder.aborts).containsExactly("ROOM_CLOSED");
    assertThat(f.scheduler.liveHandles()).isZero();
  }

  @Test
  void gracefulShutdownAbortsEveryRunningGame() {
    f.players(2);
    f.startAndPlay();

    f.sessions.shutdown();

    assertThat(f.sink.ofType("GAME_ABORTED").getFirst().payload().get("reason"))
        .isEqualTo("SERVER_SHUTDOWN");
    assertThat(f.room.status).isEqualTo(RoomStatus.WAITING);
    assertThat(f.sessions.activeCount()).isZero();
  }

  // ------------------------------------------------------------------ module misbehavior

  /** Delegates to the test game but publishes a private event type on the public stream. */
  private static final class LeakyModule extends TestGameModule {
    LeakyModule() {
      super(60_000, 2);
    }

    @Override
    public Transition init(GameContext ctx) {
      return super.init(ctx)
          .withEvents(OutboundEvent.publicEvent("TEST_SECRET", Map.of("secret", "leak")));
    }
  }

  @Test
  void publishingAPrivateTypeOnThePublicStreamAbortsTheSession() {
    SessionFixture leaky = new SessionFixture(new LeakyModule());
    leaky.players(2);

    leaky.startAndPlay();

    assertThat(leaky.sink.events.stream().noneMatch(e -> e.payload().toString().contains("leak")))
        .isTrue();
    assertThat(leaky.sink.ofType("GAME_ABORTED").getFirst().payload().get("reason"))
        .isEqualTo("INTERNAL_ERROR");
    assertThat(leaky.room.status).isEqualTo(RoomStatus.WAITING);
    assertThat(leaky.sessions.activeCount()).isZero();
  }

  /** Throws from handle() to prove one bad command does not take the session down. */
  private static final class CrashingModule extends TestGameModule {
    CrashingModule() {
      super(60_000, 2);
    }

    @Override
    public Transition handle(GameState state, PlayerAction action, GameContext ctx) {
      throw new IllegalStateException("boom");
    }

    @Override
    public Transition onDeadline(GameState state, DeadlineFired deadline, GameContext ctx) {
      return Transition.stay(state).withPhase(new PhaseChange("ROUND", null, Map.of()));
    }
  }

  @Test
  void anUnexpectedExceptionInHandleIsAServerErrorAndTheGameContinues() {
    SessionFixture crashing = new SessionFixture(new CrashingModule());
    List<UUID> p = crashing.players(2);
    crashing.startAndPlay();

    DomainException e =
        rejected(
            () ->
                crashing.sessions.submit(
                    CODE, p.get(0), "TEST_SCORE", crashing.phaseId(), Map.of("points", 1)));

    assertThat(e.code()).isEqualTo(ErrorCode.SERVER_ERROR);
    assertThat(crashing.room.status).isEqualTo(RoomStatus.PLAYING);
    assertThat(crashing.sessions.activeCount()).isEqualTo(1);
  }

  @Test
  void privateEventsOfANewPhaseCanCarryItsPhaseId() {
    f.players(2);
    f.startAndPlay();
    f.sessions.submit(
        CODE,
        f.sink.ofType("TEST_SECRET").getFirst().recipient(),
        "TEST_NEXT",
        f.phaseId(),
        Map.of());

    List<Published> secrets = f.sink.ofType("TEST_SECRET");
    List<Published> phases = f.sink.ofType("PHASE_STARTED");
    assertThat(secrets).hasSize(2);
    for (int i = 0; i < 2; i++) {
      assertThat(secrets.get(i).payload().get("phaseId"))
          .isEqualTo(phases.get(i).payload().get("phaseId"));
    }
  }

  // ------------------------------------------------------------------ snapshots and secrecy

  @Test
  void snapshotsShowASecretOnlyToItsOwner() {
    List<UUID> p = f.players(3);
    f.startAndPlay();
    UUID turn = f.sink.ofType("TEST_SECRET").getFirst().recipient();
    String secret = f.sink.ofType("TEST_SECRET").getFirst().payload().get("secret").toString();

    for (UUID viewer : p) {
      Map<String, Object> snapshot = f.sessions.gameSnapshot(CODE, viewer);
      if (viewer.equals(turn)) {
        assertThat(snapshot).containsEntry("secret", secret);
      } else {
        assertThat(snapshot).doesNotContainKey("secret");
        assertThat(snapshot.toString()).doesNotContain(secret);
      }
    }
    assertThat(f.sessions.gameSnapshot(CODE, UUID.randomUUID()))
        .isNull(); // an outsider gets nothing
  }

  @Test
  void theSecretNeverAppearsOnAPublicEventBeforeTheRoundEnds() {
    f.players(3);
    f.startAndPlay();
    String secret = f.sink.ofType("TEST_SECRET").getFirst().payload().get("secret").toString();

    assertThat(f.sink.events.stream().filter(Published::isPublic))
        .noneMatch(e -> e.payload().toString().contains(secret));
    f.clock.advance(Duration.ofSeconds(60));
    f.scheduler.fireDue(f.clock, false);
    // Only a later transition reveals it, in the closing event of the round.
    assertThat(f.sink.ofType("TEST_ROUND_ENDED").getFirst().payload().get("secret"))
        .isEqualTo(secret);
  }

  @Test
  void theSnapshotHeaderMatchesTheLatestPhaseEvent() {
    List<UUID> p = f.players(2);
    f.startAndPlay();

    Map<String, Object> snapshot = f.sessions.gameSnapshot(CODE, p.get(0));
    Map<String, Object> phase = f.sink.ofType("PHASE_STARTED").getLast().payload();

    assertThat(snapshot.get("phase")).isEqualTo(phase.get("phase"));
    assertThat(snapshot.get("phaseId")).isEqualTo(phase.get("phaseId"));
    assertThat(snapshot.get("status")).isEqualTo("RUNNING");
    assertThat(snapshot.get("scores")).isNotNull();
    assertThat(snapshot).containsKeys("sessionId", "order", "deadlineAt", "players", "round");
  }

  @Test
  void noSessionsLeakAfterEveryWayOfEndingAGame() {
    List<UUID> p = f.players(3);
    playToTheEnd();
    f.sessions.rematchVote(CODE, f.host(), true);
    assertThat(f.sessions.activeCount()).isZero();

    f.startAndPlay();
    f.room.removeMember(p.get(1));
    f.sessions.onMemberLeft(CODE, p.get(1));
    f.room.removeMember(p.get(2));
    f.sessions.onMemberLeft(CODE, p.get(2));

    assertThat(f.sessions.activeCount()).isZero();
    assertThat(f.scheduler.liveHandles()).isZero();
  }

  // ------------------------------------------------------------------ SPI additions of P6-03

  @Test
  void theModuleSeesTheCurrentHostAndFollowsAHostChange() {
    List<UUID> p = f.players(3);
    f.startAndPlay();
    Runnable byGuest =
        () -> f.sessions.submit(CODE, p.get(1), "TEST_HOST_ONLY", f.phaseId(), Map.of());
    Runnable byHost =
        () -> f.sessions.submit(CODE, p.get(0), "TEST_HOST_ONLY", f.phaseId(), Map.of());

    assertThat(rejected(byGuest).code()).isEqualTo(ErrorCode.NOT_HOST);
    byHost.run();

    f.room.host = p.get(1);
    f.sessions.onHostChanged(CODE, p.get(1));

    byGuest.run();
    assertThat(rejected(byHost).code()).isEqualTo(ErrorCode.NOT_HOST);
  }

  @Test
  void submissionsOfATransitionAreHandedToTheRecorder() {
    List<UUID> p = f.players(2);
    f.startAndPlay();

    f.sessions.submit(CODE, p.get(1), "TEST_SUBMIT", f.phaseId(), Map.of("text", "kept"));

    assertThat(f.recorder.submissions).hasSize(1);
    var record = f.recorder.submissions.getFirst();
    assertThat(record.authorGuestId()).isEqualTo(p.get(1));
    assertThat(record.text()).isEqualTo("kept");
    assertThat(record.submittedAt()).isEqualTo(f.clock.instant());
  }

  @Test
  void aModuleSubmissionIsRecordedBeforeTheFinishItCauses() {
    List<UUID> p = f.players(2);
    f.startAndPlay();
    UUID turn = (UUID) f.sink.ofType("PHASE_STARTED").getLast().payload().get("turnGuestId");
    f.sessions.submit(CODE, turn, "TEST_NEXT", f.phaseId(), Map.of()); // round 2 of 2

    f.sessions.submit(
        CODE, p.get(0), "TEST_SUBMIT", f.phaseId(), Map.of("text", "last", "finish", true));

    assertThat(f.recorder.log).containsSubsequence("submission", "finished");
  }
}
