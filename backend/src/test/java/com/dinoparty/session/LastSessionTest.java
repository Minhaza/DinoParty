package com.dinoparty.session;

import static com.dinoparty.session.SessionFixture.CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.room.RoomStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code RoomDto.lastSession} (api-contracts §2.1, D13): the newest session of the room that is no
 * longer running, cleared when the next session starts running.
 */
class LastSessionTest {

  private final SessionFixture f = new SessionFixture();

  private LastSession last() {
    return f.sessions.info(CODE).lastSession();
  }

  private void playToTheEnd() {
    f.startAndPlay();
    f.clock.advance(Duration.ofSeconds(60));
    f.scheduler.fireDue(f.clock, false);
    f.clock.advance(Duration.ofSeconds(60));
    f.scheduler.fireDue(f.clock, false);
  }

  @Test
  void aRoomWithoutAGameHasNone() {
    f.players(2);

    assertThat(last()).isNull();
    f.sessions.start(CODE, f.host());
    assertThat(last()).as("first countdown").isNull();
  }

  @Test
  void aFinishedGameIsTheLastSessionInResultsAndAfterTheReset() {
    f.players(2);
    playToTheEnd();
    Instant finishedAt = f.clock.instant();
    UUID sessionId = f.recorder.starts.getFirst().sessionId();

    assertThat(f.room.status).isEqualTo(RoomStatus.RESULTS);
    assertThat(last()).isEqualTo(new LastSession(sessionId, "FINISHED", "COMPLETED", finishedAt));
    assertThat(f.sessions.info(CODE).sessionId()).isEqualTo(sessionId);

    f.sessions.rematchVote(CODE, f.host(), true);

    assertThat(f.room.status).isEqualTo(RoomStatus.WAITING);
    assertThat(f.sessions.info(CODE).sessionId()).isNull();
    assertThat(last().sessionId()).isEqualTo(sessionId);
  }

  @Test
  void aNewGameKeepsThePreviousOneDuringTheCountdownAndClearsItOnceRunning() {
    f.players(2);
    playToTheEnd();
    f.sessions.rematchVote(CODE, f.host(), true);
    UUID first = f.recorder.starts.getFirst().sessionId();

    f.sessions.start(CODE, f.host());
    assertThat(last().sessionId()).as("countdown").isEqualTo(first);

    f.clock.advance(Duration.ofSeconds(3));
    f.scheduler.fireDue(f.clock, false);

    assertThat(f.room.status).isEqualTo(RoomStatus.PLAYING);
    assertThat(last()).as("running").isNull();
  }

  @Test
  void aCancelledCountdownLeavesItUnchanged() {
    List<UUID> p = f.players(2);
    playToTheEnd();
    f.sessions.rematchVote(CODE, f.host(), true);
    UUID first = f.recorder.starts.getFirst().sessionId();

    f.sessions.start(CODE, f.host());
    f.room.removeMember(p.get(1));
    f.sessions.onMemberLeft(CODE, p.get(1));

    assertThat(f.room.reasons().getLast()).isEqualTo("WAITING:START_CANCELLED");
    assertThat(last().sessionId()).isEqualTo(first);
  }

  @Test
  void anAbortedGameIsAbortedWithItsReason() {
    List<UUID> p = f.players(2);
    f.startAndPlay();
    UUID sessionId = f.recorder.starts.getFirst().sessionId();

    f.room.removeMember(p.get(1));
    f.sessions.onMemberLeft(CODE, p.get(1));

    assertThat(last())
        .isEqualTo(new LastSession(sessionId, "ABORTED", "NO_COMPLETED_ROUND", f.clock.instant()));
  }

  @Test
  void afterARestartTheStoredSessionIsReadOnceAndKept() {
    f.players(2);
    LastSession interrupted =
        new LastSession(UUID.randomUUID(), "INTERRUPTED", "SERVER_RESTART", Instant.EPOCH);
    f.history.stored = interrupted;

    assertThat(last()).isEqualTo(interrupted);
    assertThat(last()).isEqualTo(interrupted);
    assertThat(f.history.lookups.get()).isEqualTo(1);
  }

  @Test
  void aRunningGameNeverShowsTheStoredSession() {
    f.players(2);
    f.history.stored =
        new LastSession(UUID.randomUUID(), "INTERRUPTED", "SERVER_RESTART", Instant.EPOCH);

    f.startAndPlay();

    assertThat(last()).isNull();
  }

  @Test
  void closingTheRoomForgetsIt() {
    f.players(2);
    playToTheEnd();
    f.history.stored = null;

    f.room.status = RoomStatus.CLOSED;
    f.sessions.onRoomClosed(CODE);

    assertThat(last()).isNull();
    assertThat(f.history.lookups.get()).isEqualTo(1);
  }
}
