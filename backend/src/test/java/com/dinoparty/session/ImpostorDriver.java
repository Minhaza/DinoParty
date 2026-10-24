package com.dinoparty.session;

import static com.dinoparty.session.SessionFixture.CODE;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.session.SessionFixture.Published;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plays an impostor game through the real engine for the session tests: moves the manual clock,
 * reads the latest phase and the private roles, and sends the voting commands. Package-private
 * helper of the tests, shared by Dino Impostor today and Sketchy Saurus later.
 */
final class ImpostorDriver {

  static final Duration REVEAL = Duration.ofSeconds(5);
  static final Duration TURN = Duration.ofSeconds(25);
  static final Duration VOTE = Duration.ofSeconds(60);
  static final Duration GUESS = Duration.ofSeconds(30);
  static final Duration ROUND_END = Duration.ofSeconds(8);

  final SessionFixture f;
  List<UUID> players;

  /** Runs after every clock step; the secrecy tests record snapshots here. */
  Runnable afterStep = () -> {};

  ImpostorDriver(SessionFixture f) {
    this.f = f;
  }

  /** Starts a game of {@code n} and lets the countdown pass: ROLE_REVEAL of round 1 is running. */
  void start(int n, Map<String, Object> settings) {
    f.room.settings = settings;
    players = f.players(n);
    f.startAndPlay();
    afterStep.run();
  }

  void advance(Duration d) {
    f.clock.advance(d);
    f.scheduler.fireDue(f.clock, false);
    afterStep.run();
  }

  Map<String, Object> lastPhase() {
    return f.sink.ofType("PHASE_STARTED").getLast().payload();
  }

  String phase() {
    return (String) lastPhase().get("phase");
  }

  int round() {
    return (Integer) lastPhase().get("round");
  }

  /** The IMPOSTOR_ROLE events of the latest round, by recipient. */
  Map<UUID, Map<String, Object>> roles() {
    Map<UUID, Map<String, Object>> roles = new HashMap<>();
    for (Published e : f.sink.ofType("IMPOSTOR_ROLE")) {
      if (e.payload().get("round").equals(round())) {
        roles.put(e.recipient(), e.payload());
      }
    }
    return roles;
  }

  UUID impostor() {
    return roles().entrySet().stream()
        .filter(e -> e.getValue().get("role").equals("IMPOSTOR"))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow();
  }

  /** The secret word of the latest round, from an artist's private role. */
  String word() {
    return roles().values().stream()
        .map(r -> (String) r.get("word"))
        .filter(w -> w != null)
        .findFirst()
        .orElseThrow();
  }

  /** The artists seated in the latest round. */
  List<UUID> artists() {
    UUID impostor = impostor();
    return seated().stream().filter(p -> !p.equals(impostor)).toList();
  }

  Map<String, Object> snapshot(UUID viewer) {
    return f.sessions.gameSnapshot(CODE, viewer);
  }

  /** Lets every drawing turn time out until the VOTE phase is running. */
  void toVote() {
    for (int guard = 0; guard < 60 && !phase().equals("VOTE"); guard++) {
      advance(phase().equals("ROLE_REVEAL") ? REVEAL : TURN);
    }
    if (!phase().equals("VOTE")) {
      throw new AssertionError("no VOTE phase, stuck in " + phase());
    }
  }

  void send(UUID guest, String type, Map<String, Object> payload) {
    f.sessions.submit(CODE, guest, type, f.phaseId(), payload);
  }

  void chat(UUID guest, String text) {
    send(guest, "IMPOSTOR_CHAT", Map.of("text", text));
  }

  void vote(UUID voter, UUID target) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("targetGuestId", target == null ? null : target.toString());
    send(voter, "IMPOSTOR_VOTE", payload);
  }

  void guess(UUID guest, String text) {
    send(guest, "IMPOSTOR_GUESS", Map.of("text", text));
  }

  private final Set<UUID> left = new HashSet<>();

  /** The players of the latest round who have not left. */
  List<UUID> seated() {
    return roles().keySet().stream().filter(p -> !left.contains(p)).toList();
  }

  /** Everyone seated votes for {@code accused}; the accused votes for another seated player. */
  void everyoneVotes(UUID accused) {
    UUID other = seated().stream().filter(p -> !p.equals(accused)).findFirst().orElseThrow();
    for (UUID p : seated()) {
      vote(p, p.equals(accused) ? other : accused);
    }
  }

  void leave(UUID guest) {
    left.add(guest);
    f.room.removeMember(guest);
    f.sessions.onMemberLeft(CODE, guest);
    afterStep.run();
  }

  static ErrorCode rejected(Runnable action) {
    try {
      action.run();
    } catch (DomainException e) {
      return e.code();
    }
    throw new AssertionError("expected a rejection");
  }

  /** The newest public event of {@code type}. */
  Map<String, Object> lastEvent(String type) {
    return f.sink.ofType(type).getLast().payload();
  }
}
