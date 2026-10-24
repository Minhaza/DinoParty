package com.dinoparty.session;

import static com.dinoparty.session.ImpostorDriver.REVEAL;
import static com.dinoparty.session.ImpostorDriver.ROUND_END;
import static com.dinoparty.session.ImpostorDriver.VOTE;
import static com.dinoparty.session.SessionFixture.CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.games.impostor.DinoImpostorModule;
import com.dinoparty.session.SessionFixture.Published;
import com.dinoparty.words.TestWords;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * R35 / [DI-4] / [DI-9] / [DI-14]: over whole games, every public event, every viewer's snapshot at
 * every step and every rejection message is searched for the word, the category label, the impostor
 * and the vote targets, as far as each of them is not public yet: the word and the category until
 * {@code IMPOSTOR_ROUND_ENDED}; the impostor until {@code IMPOSTOR_VOTE_CLOSED} (when caught) or
 * the round end; vote targets until {@code IMPOSTOR_VOTE_CLOSED}.
 */
class DinoImpostorSecrecyTest {

  private enum Kind {
    ESCAPED,
    GUESSED,
    CAUGHT_WRONG,
    LEFT_IN_VOTE,
    LEFT_IN_GUESS
  }

  /** What each viewer was able to see, with how many events were public at that moment. */
  private record Frame(int events, UUID viewer, Map<String, Object> snapshot, UUID ownVote) {}

  private final SessionFixture f =
      new SessionFixture(new DinoImpostorModule(TestWords.service(), new SeededRandomSource(5)));
  private final ImpostorDriver d = new ImpostorDriver(f);
  private final List<Frame> frames = new ArrayList<>();
  private final List<String> errors = new ArrayList<>();
  private final Map<UUID, UUID> ownVotes = new HashMap<>();
  private final UUID spectator = UUID.randomUUID();

  private void record() {
    for (UUID p : d.players) {
      Map<String, Object> view = f.sessions.gameSnapshot(CODE, p);
      if (view != null) {
        frames.add(new Frame(f.sink.events.size(), p, view, ownVotes.get(p)));
      }
    }
  }

  private void vote(UUID voter, UUID target) {
    d.vote(voter, target);
    if (target == null) {
      ownVotes.remove(voter);
    } else {
      ownVotes.put(voter, target);
    }
    record();
  }

  private void everyoneVotes(UUID target) {
    for (UUID p : d.roles().keySet()) {
      // The accused votes too (for someone else): the vote only closes early when everyone has.
      vote(p, p.equals(target) ? d.artists().getFirst() : target);
    }
  }

  /** Commands that must be refused, with messages that must reveal nothing. */
  private void provoke(UUID impostor, UUID artist) {
    List<Runnable> bad =
        List.of(
            () -> d.vote(artist, artist),
            () -> d.vote(artist, UUID.randomUUID()),
            () -> d.guess(artist, "anything"),
            () -> d.chat(artist, ""),
            () -> d.send(artist, "IMPOSTOR_VOTE", Map.of()));
    for (Runnable r : bad) {
      try {
        r.run();
      } catch (DomainException e) {
        errors.add(e.getMessage());
      }
    }
  }

  private void playRound(Kind kind) {
    ownVotes.clear();
    d.toVote();
    UUID impostor = d.impostor();
    List<UUID> artists = d.artists();
    d.chat(artists.getFirst(), "I think it is a bit scaly");
    record();
    provoke(impostor, artists.getFirst());
    switch (kind) {
      case ESCAPED -> {
        vote(impostor, artists.get(0));
        vote(artists.get(1), artists.get(0));
        vote(artists.get(2), artists.get(0));
        d.advance(VOTE);
      }
      case GUESSED, CAUGHT_WRONG -> {
        everyoneVotes(impostor);
        d.advance(Duration.ofSeconds(15));
        assertThat(d.phase()).isEqualTo("GUESS");
        provoke(impostor, artists.getFirst());
        d.guess(impostor, kind == Kind.GUESSED ? d.word() : "definitely not the word");
        record();
      }
      case LEFT_IN_VOTE -> {
        vote(artists.get(0), impostor);
        vote(impostor, artists.get(1));
        d.leave(impostor);
      }
      case LEFT_IN_GUESS -> {
        everyoneVotes(impostor);
        d.advance(Duration.ofSeconds(15));
        assertThat(d.phase()).isEqualTo("GUESS");
        d.leave(impostor);
      }
    }
    assertThat(d.phase()).isEqualTo("ROUND_END");
    d.advance(ROUND_END);
  }

  private void playGame(int players, Kind... kinds) {
    d.afterStep = this::record;
    d.start(players, Map.of("rounds", kinds.length, "laps", 1));
    for (Kind kind : kinds) {
      playRound(kind);
    }
    assertThat(f.sink.ofType("GAME_FINISHED")).hasSize(1);
  }

  // ------------------------------------------------------------------ the checks

  private record Reveal(boolean word, boolean impostor, boolean votes) {}

  /** What is public after the first {@code count} events, for {@code round}. */
  private Reveal revealed(int count, int round) {
    boolean ended = false;
    boolean closed = false;
    boolean caught = false;
    for (Published e : f.sink.events.subList(0, count)) {
      if (!e.isPublic() || !Integer.valueOf(round).equals(e.payload().get("round"))) {
        continue;
      }
      switch (e.type()) {
        case "IMPOSTOR_ROUND_ENDED" -> ended = true;
        case "IMPOSTOR_VOTE_CLOSED" -> {
          closed = true;
          caught |= Boolean.TRUE.equals(e.payload().get("caught"));
        }
        default -> {}
      }
    }
    return new Reveal(ended, ended || caught, ended || closed);
  }

  private static boolean contains(Object value, String needle) {
    return switch (value) {
      case null -> false;
      case String s -> s.toLowerCase().contains(needle.toLowerCase());
      case UUID u -> u.toString().contains(needle);
      case Map<?, ?> m ->
          m.keySet().stream().anyMatch(k -> contains(k, needle))
              || m.values().stream().anyMatch(v -> contains(v, needle));
      case Collection<?> c -> c.stream().anyMatch(v -> contains(v, needle));
      default -> false;
    };
  }

  /** A non-null {@code impostorGuestId} or the exact role value {@code IMPOSTOR} inside. */
  private static boolean labelsImpostor(Object value) {
    return switch (value) {
      case null -> false;
      case String s -> s.equals("IMPOSTOR");
      case Map<?, ?> m ->
          m.entrySet().stream()
                  .anyMatch(e -> "impostorGuestId".equals(e.getKey()) && e.getValue() != null)
              || m.values().stream().anyMatch(DinoImpostorSecrecyTest::labelsImpostor);
      case Collection<?> c -> c.stream().anyMatch(DinoImpostorSecrecyTest::labelsImpostor);
      default -> false;
    };
  }

  private static final Set<String> VOTE_KEYS =
      Set.of("votes", "accusedGuestId", "targetGuestId", "myVote");

  private static boolean carriesVoteTargets(Object value) {
    return switch (value) {
      case null -> false;
      case Map<?, ?> m ->
          m.entrySet().stream()
                  .anyMatch(e -> VOTE_KEYS.contains(e.getKey()) && e.getValue() != null)
              || m.values().stream().anyMatch(DinoImpostorSecrecyTest::carriesVoteTargets);
      case Collection<?> c -> c.stream().anyMatch(DinoImpostorSecrecyTest::carriesVoteTargets);
      default -> false;
    };
  }

  /** The word and the category label of every round, from the private roles. */
  private Map<Integer, List<String>> secretsByRound() {
    Map<Integer, List<String>> secrets = new HashMap<>();
    for (Published e : f.sink.ofType("IMPOSTOR_ROLE")) {
      if (e.payload().get("word") != null) {
        @SuppressWarnings("unchecked")
        Map<String, Object> category = (Map<String, Object>) e.payload().get("category");
        secrets.put(
            (Integer) e.payload().get("round"),
            List.of((String) e.payload().get("word"), (String) category.get("label")));
      }
    }
    return secrets;
  }

  private void assertNothingLeaked() {
    Map<Integer, List<String>> secrets = secretsByRound();
    // Public events, in order.
    int round = 0;
    for (int i = 0; i < f.sink.events.size(); i++) {
      Published e = f.sink.events.get(i);
      if (e.type().equals("PHASE_STARTED")) {
        round = (Integer) e.payload().get("round");
      }
      if (!e.isPublic()) {
        continue;
      }
      Reveal open = revealed(i + 1, round);
      if (!open.word()) {
        secrets
            .getOrDefault(round, List.of())
            .forEach(s -> assertThat(contains(e.payload(), s)).as("%s: %s", e.type(), s).isFalse());
      }
      if (!open.impostor()) {
        assertThat(labelsImpostor(e.payload())).as("%s labels the impostor", e.type()).isFalse();
      }
      if (!open.votes()) {
        assertThat(carriesVoteTargets(e.payload())).as("%s carries votes", e.type()).isFalse();
      }
    }
    // The private events other than the roles hold no word either (the guess prompt has the
    // category label only, and goes to the caught impostor).
    for (Published e : f.sink.events) {
      if (!e.isPublic() && e.type().equals("IMPOSTOR_GUESS_OPEN")) {
        assertThat(e.payload().keySet())
            .containsExactly("phaseId", "round", "category", "deadlineAt");
        assertThat(contains(e.payload(), secrets.get((Integer) e.payload().get("round")).get(0)))
            .isFalse();
      }
    }
    // Every viewer's snapshot at every step.
    for (Frame frame : frames) {
      Map<String, Object> view = new HashMap<>(frame.snapshot());
      Object role = view.remove("role"); // legitimately their own
      int r = (Integer) view.get("round");
      Reveal open = revealed(frame.events(), r);
      Object myVote = view.remove("myVote");
      if (!open.word()) {
        secrets
            .get(r)
            .forEach(s -> assertThat(contains(view, s)).as("snapshot shows %s", s).isFalse());
      }
      if (!open.impostor()) {
        assertThat(labelsImpostor(view)).as("snapshot labels the impostor").isFalse();
      }
      if (!open.votes()) {
        assertThat(carriesVoteTargets(view)).as("snapshot carries votes").isFalse();
        assertThat(view.get("voteClosed")).isNull();
        assertThat(view.get("roundEnd")).isNull();
      }
      // Only the viewer's own vote, and only what they cast.
      if (myVote != null) {
        assertThat(myVote).isEqualTo(frame.ownVote());
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> own = (Map<String, Object>) role;
      if (own != null && own.get("role").equals("IMPOSTOR") && !open.word()) {
        // The impostor sees no word at all, not even in their own role.
        assertThat(contains(frame.snapshot(), secrets.get(r).get(0))).isFalse();
      }
    }
    // Rejections.
    assertThat(errors).isNotEmpty();
    for (String message : errors) {
      secrets
          .values()
          .forEach(s -> s.forEach(x -> assertThat(message).doesNotContainIgnoringCase(x)));
    }
  }

  // ------------------------------------------------------------------ tests

  @Test
  void aGameOfEscapedGuessedAndCaughtRoundsLeaksNothing() {
    playGame(5, Kind.ESCAPED, Kind.GUESSED, Kind.CAUGHT_WRONG);

    assertThat(f.sink.ofType("IMPOSTOR_ROUND_ENDED"))
        .extracting(e -> e.payload().get("outcome"))
        .containsExactly("ESCAPED", "GUESSED", "CAUGHT");
    assertNothingLeaked();
  }

  @Test
  void anImpostorWhoLeavesInTheVoteOrTheGuessLeaksNothing() {
    playGame(5, Kind.LEFT_IN_VOTE, Kind.LEFT_IN_GUESS, Kind.CAUGHT_WRONG);

    assertThat(f.sink.ofType("IMPOSTOR_ROUND_ENDED"))
        .extracting(e -> e.payload().get("outcome"))
        .containsExactly("IMPOSTOR_LEFT", "IMPOSTOR_LEFT", "CAUGHT");
    assertNothingLeaked();
  }

  @Test
  void aSpectatorOrOutsiderGetsNoGameSnapshotAndSoNoRoleOrWord() {
    d.start(4, Map.of("rounds", 3));
    assertThat(f.sessions.gameSnapshot(CODE, spectator)).isNull();
  }

  @Test
  void everyRoleOfARoundIsOneTransitionOfTheSameShape() {
    d.start(6, Map.of("rounds", 3, "laps", 1));
    for (int round = 1; round <= 3; round++) {
      d.advance(REVEAL);
      d.toVote();
      d.advance(VOTE);
      d.advance(ROUND_END);
    }
    List<Published> events = f.sink.events;
    List<Published> phases = f.sink.ofType("PHASE_STARTED");
    for (int round = 1; round <= 3; round++) {
      int r = round;
      List<Published> roles =
          f.sink.ofType("IMPOSTOR_ROLE").stream()
              .filter(e -> e.payload().get("round").equals(r))
              .toList();
      assertThat(roles).hasSize(6);
      Published reveal =
          phases.stream()
              .filter(
                  p ->
                      p.payload().get("phase").equals("ROLE_REVEAL")
                          && p.payload().get("round").equals(r))
              .findFirst()
              .orElseThrow();
      int at = events.indexOf(reveal);
      // Contiguous right after the round's PHASE_STARTED, all with its phaseId.
      assertThat(events.subList(at + 1, at + 7)).containsExactlyElementsOf(roles);
      Set<List<String>> shapes = new HashSet<>();
      for (Published e : roles) {
        assertThat(e.payload().get("phaseId")).isEqualTo(reveal.payload().get("phaseId"));
        shapes.add(List.copyOf(e.payload().keySet()));
        @SuppressWarnings("unchecked")
        Map<String, Object> category = (Map<String, Object>) e.payload().get("category");
        assertThat(category).containsOnlyKeys("key", "label");
      }
      assertThat(shapes).hasSize(1);
      assertThat(roles).extracting(Published::recipient).doesNotHaveDuplicates();
    }
  }
}
