package com.dinoparty.session;

import static com.dinoparty.session.ImpostorDriver.REVEAL;
import static com.dinoparty.session.ImpostorDriver.ROUND_END;
import static com.dinoparty.session.SessionFixture.CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.SeededRandomSource;
import com.dinoparty.games.impostor.SketchySaurusModule;
import com.dinoparty.session.SessionFixture.Published;
import com.dinoparty.words.TestWords;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * R35 / [SS-2] / [SS-3] / [DI-4]: over whole games in each of the three settings, every public and
 * private event, every viewer's snapshot at every step and every rejection message is searched for
 * what is not public yet:
 *
 * <ul>
 *   <li>a stroke of a draft: it reaches nobody (no event at all) and no other viewer's snapshot
 *       before {@code GALLERY} opens;
 *   <li>the word, its category label and the decoy: until {@code IMPOSTOR_ROUND_ENDED};
 *   <li>the impostor: until {@code IMPOSTOR_VOTE_CLOSED} (when caught) or the round end;
 *   <li>vote targets: until {@code IMPOSTOR_VOTE_CLOSED}.
 * </ul>
 *
 * With {@code impostorKnows} off, the unaware impostor's role events and snapshots must also have
 * the same fields, types and timing as an artist's.
 */
class SketchySaurusSecrecyTest {

  private enum Kind {
    ESCAPED,
    CAUGHT_RIGHT,
    CAUGHT_WRONG,
    LEFT_IN_SKETCH,
    LEFT_IN_GALLERY
  }

  /** What each viewer was able to see at one step, with how many events existed at that moment. */
  private record Frame(
      int step, int events, UUID viewer, Map<String, Object> snapshot, UUID ownVote) {}

  private record Secrets(String word, String label, String decoy, UUID impostor) {}

  private final SessionFixture f =
      new SessionFixture(new SketchySaurusModule(TestWords.service(), new SeededRandomSource(5)));
  private final SketchyDriver s = new SketchyDriver(f);
  private final ImpostorDriver d = s.d;
  private final List<Frame> frames = new ArrayList<>();
  private final List<String> errors = new ArrayList<>();
  private final Map<UUID, UUID> ownVotes = new HashMap<>();
  private final Map<Integer, Secrets> secrets = new HashMap<>();

  /** Every stroke id a player drew, by round, and its author. */
  private final Map<Integer, Map<UUID, UUID>> strokeAuthors = new HashMap<>();

  private final UUID outsider = UUID.randomUUID();
  private int step;
  private boolean unaware;

  private void record() {
    step++;
    for (UUID p : d.players) {
      Map<String, Object> view = f.sessions.gameSnapshot(CODE, p);
      if (view != null) {
        frames.add(new Frame(step, f.sink.events.size(), p, view, ownVotes.get(p)));
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

  private void everyoneVotes(UUID accused) {
    UUID other = d.seated().stream().filter(p -> !p.equals(accused)).findFirst().orElseThrow();
    for (UUID p : d.seated()) {
      vote(p, p.equals(accused) ? other : accused);
    }
  }

  private void provoke(String type, UUID guest, Map<String, Object> payload) {
    try {
      d.send(guest, type, payload);
    } catch (DomainException e) {
      errors.add(e.getMessage());
    }
  }

  /** Commands that must be refused, with messages that must reveal nothing. */
  private void provoke(UUID artist) {
    provoke("IMPOSTOR_VOTE", artist, Map.of("targetGuestId", artist.toString()));
    provoke("IMPOSTOR_VOTE", artist, Map.of("targetGuestId", UUID.randomUUID().toString()));
    provoke("IMPOSTOR_GUESS", artist, Map.of("text", "anything"));
    provoke("IMPOSTOR_CHAT", artist, Map.of("text", ""));
    provoke("IMPOSTOR_VOTE", artist, Map.of());
    provoke("SKETCHY_DRAW_STROKE", artist, Map.of("strokeId", "x"));
    provoke("SKETCHY_DRAW_STROKE", artist, s.batch(UUID.randomUUID(), 4, 3, true)); // out of order
  }

  private void sketchEverything(int round) {
    Map<UUID, UUID> authors = strokeAuthors.computeIfAbsent(round, r -> new HashMap<>());
    for (int pass = 0; pass < 2; pass++) {
      for (UUID p : d.seated()) {
        authors.put(s.stroke(p, 6 + pass), p);
      }
      record();
    }
  }

  private void playRound(Kind kind, boolean mayGuess) {
    ownVotes.clear();
    d.advance(REVEAL);
    assertThat(d.phase()).isEqualTo("SKETCH");
    int round = d.round();
    UUID impostor = s.impostor();
    List<UUID> artists = s.artists();
    secrets.put(
        round,
        new Secrets(
            s.word(), labelOf(impostor, artists.getFirst()), unaware ? s.decoy() : null, impostor));
    sketchEverything(round);
    provoke(artists.getFirst());
    if (kind == Kind.LEFT_IN_SKETCH) {
      d.leave(impostor);
      assertThat(d.phase()).isEqualTo("ROUND_END");
      d.advance(ROUND_END);
      return;
    }
    for (UUID p : d.seated()) {
      s.submit(p);
    }
    record();
    assertThat(d.phase()).isEqualTo("GALLERY");
    d.chat(artists.getFirst(), "11 22 33");
    record();
    provoke(artists.getFirst());
    switch (kind) {
      case ESCAPED -> {
        everyoneVotes(artists.getFirst());
        d.advance(Duration.ofSeconds(15));
      }
      case CAUGHT_RIGHT, CAUGHT_WRONG -> {
        everyoneVotes(impostor);
        d.advance(Duration.ofSeconds(15));
        if (mayGuess) {
          assertThat(d.phase()).isEqualTo("GUESS");
          provoke(artists.getFirst());
          d.guess(impostor, kind == Kind.CAUGHT_RIGHT ? s.word() : "definitely not the word");
          record();
        }
      }
      case LEFT_IN_GALLERY -> {
        vote(artists.get(0), impostor);
        vote(impostor, artists.get(1));
        d.leave(impostor);
      }
      default -> throw new IllegalStateException();
    }
    assertThat(d.phase()).isEqualTo("ROUND_END");
    d.advance(ROUND_END);
  }

  private String labelOf(UUID impostor, UUID artist) {
    @SuppressWarnings("unchecked")
    Map<String, Object> category = (Map<String, Object>) d.roles().get(artist).get("category");
    return (String) category.get("label");
  }

  private void playGame(Map<String, Object> settings, boolean mayGuess, Kind... kinds) {
    unaware = Boolean.FALSE.equals(settings.get("impostorKnows"));
    d.afterStep = this::record;
    Map<String, Object> all = new HashMap<>(settings);
    all.put("rounds", kinds.length);
    d.start(5, all);
    for (Kind kind : kinds) {
      playRound(kind, mayGuess);
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

  /** Index of the round's {@code PHASE_STARTED{GALLERY}}, or the end of time if it never opened. */
  private int galleryOpensAt(int round) {
    for (int i = 0; i < f.sink.events.size(); i++) {
      Published e = f.sink.events.get(i);
      if (e.type().equals("PHASE_STARTED")
          && e.payload().get("phase").equals("GALLERY")
          && e.payload().get("round").equals(round)) {
        return i;
      }
    }
    return Integer.MAX_VALUE;
  }

  private static boolean contains(Object value, String needle) {
    return switch (value) {
      case null -> false;
      case String x -> x.toLowerCase().contains(needle.toLowerCase());
      case UUID u -> u.toString().contains(needle);
      case Map<?, ?> m ->
          m.keySet().stream().anyMatch(k -> contains(k, needle))
              || m.values().stream().anyMatch(v -> contains(v, needle));
      case Collection<?> c -> c.stream().anyMatch(v -> contains(v, needle));
      default -> false;
    };
  }

  private static boolean labelsImpostor(Object value) {
    return switch (value) {
      case null -> false;
      case String x -> x.equals("IMPOSTOR");
      case Map<?, ?> m ->
          m.entrySet().stream()
                  .anyMatch(e -> "impostorGuestId".equals(e.getKey()) && e.getValue() != null)
              || m.values().stream().anyMatch(SketchySaurusSecrecyTest::labelsImpostor);
      case Collection<?> c -> c.stream().anyMatch(SketchySaurusSecrecyTest::labelsImpostor);
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
              || m.values().stream().anyMatch(SketchySaurusSecrecyTest::carriesVoteTargets);
      case Collection<?> c -> c.stream().anyMatch(SketchySaurusSecrecyTest::carriesVoteTargets);
      default -> false;
    };
  }

  /** The keys and value types of a view, ignoring the values (the word is a text or null). */
  private static Object shape(Object value) {
    return switch (value) {
      case Map<?, ?> m -> {
        Map<Object, Object> shape = new LinkedHashMap<>();
        m.forEach((k, v) -> shape.put(k, k.equals("word") ? "word" : shape(v)));
        yield shape;
      }
      case List<?> l -> l.isEmpty() ? "[]" : "[..]";
      case null -> "null";
      default -> value.getClass().getSimpleName();
    };
  }

  private void assertNothingLeaked() {
    int round = 0;
    // Public and private events, in order.
    for (int i = 0; i < f.sink.events.size(); i++) {
      Published e = f.sink.events.get(i);
      if (e.type().equals("PHASE_STARTED")) {
        round = (Integer) e.payload().get("round");
      }
      Secrets known = secrets.get(round);
      boolean beforeGallery = i < galleryOpensAt(round);
      if (beforeGallery) {
        int r = round;
        strokeAuthors
            .getOrDefault(r, Map.of())
            .keySet()
            .forEach(
                id ->
                    assertThat(contains(e.payload(), id.toString()))
                        .as("%s carries a draft stroke before the gallery", e.type())
                        .isFalse());
      }
      if (known == null || !e.isPublic()) {
        continue;
      }
      Reveal open = revealed(i + 1, round);
      if (!open.word()) {
        for (String secret : secretTexts(known)) {
          assertThat(contains(e.payload(), secret)).as("%s: %s", e.type(), secret).isFalse();
        }
      }
      if (!open.impostor()) {
        assertThat(labelsImpostor(e.payload())).as("%s labels the impostor", e.type()).isFalse();
      }
      if (!open.votes()) {
        assertThat(carriesVoteTargets(e.payload())).as("%s carries votes", e.type()).isFalse();
      }
    }
    // The private events other than the roles hold no word and no decoy either.
    for (Published e : f.sink.events) {
      if (!e.isPublic() && e.type().equals("IMPOSTOR_GUESS_OPEN")) {
        assertThat(e.payload().keySet())
            .containsExactly("phaseId", "round", "category", "deadlineAt");
        Secrets known = secrets.get((Integer) e.payload().get("round"));
        assertThat(contains(e.payload(), known.word())).isFalse();
        if (known.decoy() != null) {
          assertThat(contains(e.payload(), known.decoy())).isFalse();
        }
      }
      if (!e.isPublic()) {
        assertThat(e.type()).isIn("IMPOSTOR_ROLE", "IMPOSTOR_GUESS_OPEN");
      }
    }
    // Every viewer's snapshot at every step.
    for (Frame frame : frames) {
      Map<String, Object> view = new HashMap<>(frame.snapshot());
      Object role = view.remove("role"); // legitimately their own
      Object myVote = view.remove("myVote");
      int r = (Integer) view.get("round");
      Secrets known = secrets.get(r);
      if (known == null) {
        continue; // before the first role event of a round only a stale round number is shown
      }
      Reveal open = revealed(frame.events(), r);
      // A draft is its author's alone, and nobody's before the gallery.
      Object draft = view.remove("draft");
      if (frame.events() < galleryOpensAt(r)) {
        for (Map.Entry<UUID, UUID> stroke : strokeAuthors.getOrDefault(r, Map.of()).entrySet()) {
          boolean mine = stroke.getValue().equals(frame.viewer());
          assertThat(contains(view, stroke.getKey().toString()))
              .as("snapshot shows a stroke outside the draft")
              .isFalse();
          if (!mine) {
            assertThat(contains(draft, stroke.getKey().toString()))
                .as("%s sees a stroke of another player's draft", frame.viewer())
                .isFalse();
          }
        }
      } else {
        assertThat(draft).isNull();
      }
      if (!open.word()) {
        for (String secret : secretTexts(known)) {
          assertThat(contains(view, secret)).as("snapshot shows %s", secret).isFalse();
        }
      }
      if (!open.impostor()) {
        assertThat(labelsImpostor(view)).as("snapshot labels the impostor").isFalse();
      }
      if (!open.votes()) {
        assertThat(carriesVoteTargets(view)).as("snapshot carries votes").isFalse();
        assertThat(view.get("voteClosed")).isNull();
        assertThat(view.get("roundEnd")).isNull();
      }
      if (myVote != null) {
        assertThat(myVote).isEqualTo(frame.ownVote());
      }
      if (!unaware && role instanceof Map<?, ?> own && "IMPOSTOR".equals(own.get("role"))) {
        assertThat(own.get("word")).as("the impostor who knows has no word").isNull();
      }
    }
    // Rejections.
    assertThat(errors).isNotEmpty();
    for (String message : errors) {
      for (Secrets known : secrets.values()) {
        for (String secret : secretTexts(known)) {
          assertThat(message).doesNotContainIgnoringCase(secret);
        }
      }
    }
  }

  private static List<String> secretTexts(Secrets known) {
    List<String> texts = new ArrayList<>(List.of(known.word(), known.label()));
    if (known.decoy() != null) {
      texts.add(known.decoy());
    }
    return texts;
  }

  /** The unaware impostor is indistinguishable from an artist until the vote result names them. */
  private void assertImpostorLooksLikeAnArtist() {
    for (Map.Entry<Integer, Secrets> entry : secrets.entrySet()) {
      int round = entry.getKey();
      UUID impostor = entry.getValue().impostor();
      List<Published> roles =
          f.sink.ofType("IMPOSTOR_ROLE").stream()
              .filter(e -> e.payload().get("round").equals(round))
              .toList();
      int seated = roles.size();
      assertThat(seated).isBetween(3, 5);
      Published reveal =
          f.sink.ofType("PHASE_STARTED").stream()
              .filter(
                  p ->
                      p.payload().get("phase").equals("ROLE_REVEAL")
                          && p.payload().get("round").equals(round))
              .findFirst()
              .orElseThrow();
      int at = f.sink.events.indexOf(reveal);
      // One transition: contiguous right after the round's PHASE_STARTED, with its phaseId.
      assertThat(f.sink.events.subList(at + 1, at + 1 + seated)).containsExactlyElementsOf(roles);
      assertThat(roles.stream().map(e -> e.payload().get("phaseId")).collect(Collectors.toSet()))
          .containsExactly(reveal.payload().get("phaseId"));
      assertThat(
              roles.stream()
                  .map(e -> List.copyOf(e.payload().keySet()))
                  .collect(Collectors.toSet()))
          .hasSize(1);
      if (unaware) {
        assertThat(roles.stream().map(e -> shape(e.payload())).collect(Collectors.toSet()))
            .as("shape of the role events of round %d", round)
            .hasSize(1);
        assertThat(roles).allSatisfy(e -> assertThat(e.payload()).containsEntry("role", "ARTIST"));
        assertThat(
                f.sink.events.subList(at + 1, at + 1 + seated).stream()
                    .map(Published::recipient)
                    .collect(Collectors.toSet()))
            .hasSize(seated);
        // Until the vote result, the impostor received exactly what an artist received.
        int closed = indexOfVoteClosed(round);
        UUID artist =
            roles.stream()
                .map(Published::recipient)
                .filter(g -> !g.equals(impostor))
                .findFirst()
                .orElseThrow();
        assertThat(privateTypes(impostor, at, closed)).isEqualTo(privateTypes(artist, at, closed));
        assertThat(privateTypes(impostor, at, closed)).containsExactly("IMPOSTOR_ROLE");
      }
    }
    if (unaware) {
      Map<Integer, List<Frame>> byStep =
          frames.stream().collect(Collectors.groupingBy(Frame::step));
      for (List<Frame> sameStep : byStep.values()) {
        int r = (Integer) sameStep.getFirst().snapshot().get("round");
        Secrets known = secrets.get(r);
        if (known == null) {
          continue;
        }
        Frame impostor =
            sameStep.stream()
                .filter(x -> x.viewer().equals(known.impostor()))
                .findFirst()
                .orElse(null);
        Frame artist =
            sameStep.stream()
                .filter(x -> !x.viewer().equals(known.impostor()))
                .findFirst()
                .orElse(null);
        if (impostor == null || artist == null) {
          continue;
        }
        Map<String, Object> a = new HashMap<>(impostor.snapshot());
        Map<String, Object> b = new HashMap<>(artist.snapshot());
        a.remove("myVote");
        b.remove("myVote");
        assertThat(shape(a)).as("step %d, round %d", impostor.step(), r).isEqualTo(shape(b));
      }
    }
  }

  /** The vote result of the round, or its round end when the round ended before any vote. */
  private int indexOfVoteClosed(int round) {
    for (String type : List.of("IMPOSTOR_VOTE_CLOSED", "IMPOSTOR_ROUND_ENDED")) {
      for (int i = 0; i < f.sink.events.size(); i++) {
        Published e = f.sink.events.get(i);
        if (e.type().equals(type) && e.payload().get("round").equals(round)) {
          return i;
        }
      }
    }
    return f.sink.events.size();
  }

  private List<String> privateTypes(UUID guest, int from, int to) {
    List<String> types = new ArrayList<>();
    for (int i = from; i < to; i++) {
      Published e = f.sink.events.get(i);
      if (guest.equals(e.recipient())) {
        types.add(e.type());
      }
    }
    return types;
  }

  // ------------------------------------------------------------------ tests

  @Test
  void anImpostorWhoKnowsLeaksNothingOverEveryKindOfRound() {
    playGame(
        Map.of(),
        true,
        Kind.ESCAPED,
        Kind.CAUGHT_RIGHT,
        Kind.CAUGHT_WRONG,
        Kind.LEFT_IN_GALLERY,
        Kind.LEFT_IN_SKETCH);

    assertThat(f.sink.ofType("IMPOSTOR_ROUND_ENDED"))
        .extracting(e -> e.payload().get("outcome"))
        .containsExactly("ESCAPED", "GUESSED", "CAUGHT", "IMPOSTOR_LEFT", "IMPOSTOR_LEFT");
    assertNothingLeaked();
    assertImpostorLooksLikeAnArtist();
  }

  @Test
  void anUnawareImpostorWithNoGuessLeaksNothingAndLooksLikeAnArtist() {
    playGame(
        Map.of("impostorKnows", false),
        false,
        Kind.ESCAPED,
        Kind.CAUGHT_WRONG,
        Kind.LEFT_IN_GALLERY,
        Kind.LEFT_IN_SKETCH);

    assertThat(f.sink.ofType("IMPOSTOR_ROUND_ENDED"))
        .extracting(e -> e.payload().get("outcome"))
        .containsExactly("ESCAPED", "CAUGHT", "IMPOSTOR_LEFT", "IMPOSTOR_LEFT");
    assertThat(f.sink.ofType("IMPOSTOR_GUESS_OPEN")).isEmpty();
    assertNothingLeaked();
    assertImpostorLooksLikeAnArtist();
  }

  @Test
  void anUnawareImpostorWhoMayGuessLeaksNothingAndLooksLikeAnArtistUntilCaught() {
    playGame(
        Map.of("impostorKnows", false, "unawareGuess", true),
        true,
        Kind.ESCAPED,
        Kind.CAUGHT_RIGHT,
        Kind.CAUGHT_WRONG,
        Kind.LEFT_IN_GALLERY);

    assertThat(f.sink.ofType("IMPOSTOR_ROUND_ENDED"))
        .extracting(e -> e.payload().get("outcome"))
        .containsExactly("ESCAPED", "GUESSED", "CAUGHT", "IMPOSTOR_LEFT");
    assertThat(f.sink.ofType("IMPOSTOR_GUESS_OPEN")).hasSize(2);
    assertNothingLeaked();
    assertImpostorLooksLikeAnArtist();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void anOutsiderGetsNoGameSnapshotAndSoNoRoleWordOrDrawing(boolean impostorKnows) {
    d.start(4, Map.of("rounds", 3, "impostorKnows", impostorKnows));
    s.toSketch();
    s.stroke(d.players.getFirst(), 5);

    assertThat(f.sessions.gameSnapshot(CODE, outsider)).isNull();
    assertThat(f.sink.events.stream().filter(e -> e.type().equals("SKETCHY_GALLERY_OPENED")))
        .isEmpty();
  }

  @Test
  void aDraftNeverLeavesTheServerExceptInItsAuthorsOwnSnapshotUntilTheGallery() {
    d.start(5, Map.of("rounds", 3));
    s.toSketch();
    UUID author = d.players.get(2);
    UUID strokeId = s.stroke(author, 9);
    int before = f.sink.events.size();
    s.stroke(author, 3);

    assertThat(f.sink.events).hasSize(before); // not one event for any draw command
    for (UUID p : d.players) {
      assertThat(contains(f.sessions.gameSnapshot(CODE, p), strokeId.toString()))
          .as("snapshot of %s", p)
          .isEqualTo(p.equals(author));
    }
    s.everyoneSubmits(0);
    assertThat(contains(d.lastEvent("SKETCHY_GALLERY_OPENED"), strokeId.toString())).isTrue();
  }
}
