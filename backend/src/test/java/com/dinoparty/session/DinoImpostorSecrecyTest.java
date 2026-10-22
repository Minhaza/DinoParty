package com.dinoparty.session;

import static com.dinoparty.session.SessionFixture.CODE;
import static org.assertj.core.api.Assertions.assertThat;

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
 * R35 / [DI-4]: over full rounds, every public event and every viewer's snapshot is recorded and
 * searched for the word and the impostor; the roles of a round are one transition of one shape.
 */
class DinoImpostorSecrecyTest {

  private final SessionFixture f =
      new SessionFixture(new DinoImpostorModule(TestWords.service(), new SeededRandomSource(5)));
  private List<UUID> players;

  /** Everything each viewer was able to see, recorded at every step. */
  private final Map<UUID, List<Map<String, Object>>> snapshots = new HashMap<>();

  private final UUID spectator = UUID.randomUUID();

  private void record() {
    for (UUID p : players) {
      snapshots.computeIfAbsent(p, k -> new ArrayList<>()).add(f.sessions.gameSnapshot(CODE, p));
    }
  }

  private void advance(Duration d) {
    f.clock.advance(d);
    f.scheduler.fireDue(f.clock, false);
    record();
  }

  private String phase() {
    return (String) f.sink.ofType("PHASE_STARTED").getLast().payload().get("phase");
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

  /** An {@code impostorGuestId} key or the exact role value {@code IMPOSTOR} anywhere inside. */
  private static boolean labelsImpostor(Object value) {
    return switch (value) {
      case null -> false;
      case String s -> s.equals("IMPOSTOR");
      case Map<?, ?> m ->
          m.keySet().stream().anyMatch(k -> "impostorGuestId".equals(k))
              || m.values().stream().anyMatch(DinoImpostorSecrecyTest::labelsImpostor);
      case Collection<?> c -> c.stream().anyMatch(DinoImpostorSecrecyTest::labelsImpostor);
      default -> false;
    };
  }

  /** Plays {@code rounds} full rounds, drawing a stroke in the first turn of each. */
  private void play(int n, int rounds) {
    f.room.settings = Map.of("rounds", rounds, "laps", 1);
    players = f.players(n);
    f.startAndPlay();
    record();
    for (int round = 1; round <= rounds; round++) {
      advance(Duration.ofSeconds(5));
      while (!phase().equals("VOTE")) {
        advance(Duration.ofSeconds(25));
      }
      advance(Duration.ofSeconds(60));
    }
  }

  @Test
  void theWordAndTheImpostorNeverReachAPublicEventOrAnotherViewersSnapshot() {
    play(5, 3);

    List<Published> roles = f.sink.ofType("IMPOSTOR_ROLE");
    assertThat(roles).hasSize(15);

    for (int round = 1; round <= 3; round++) {
      int r = round;
      Map<UUID, Map<String, Object>> byGuest = new HashMap<>();
      roles.stream()
          .filter(e -> e.payload().get("round").equals(r))
          .forEach(e -> byGuest.put(e.recipient(), e.payload()));
      assertThat(byGuest).hasSize(5);
      UUID impostor =
          byGuest.entrySet().stream()
              .filter(e -> e.getValue().get("role").equals("IMPOSTOR"))
              .map(Map.Entry::getKey)
              .findFirst()
              .orElseThrow();
      String word =
          byGuest.values().stream()
              .map(p -> (String) p.get("word"))
              .filter(w -> w != null)
              .findFirst()
              .orElseThrow();

      // Public events: neither the word nor an impostor label, ever (the vote result is P9-03).
      for (Published e : f.sink.events) {
        if (e.isPublic()) {
          assertThat(contains(e.payload(), word)).as("%s carries the word", e.type()).isFalse();
          // Every player's id is public (order, seats), so the impostor is identified by a
          // label, never by presence: no impostor key and no role value in a public event.
          assertThat(labelsImpostor(e.payload())).as("%s labels the impostor", e.type()).isFalse();
        }
      }
      // Private events: the word only inside the artists' own roles.
      for (Published e : f.sink.events) {
        if (!e.isPublic() && !e.type().equals("IMPOSTOR_ROLE")) {
          assertThat(contains(e.payload(), word)).isFalse();
        }
      }
      for (Published e : roles) {
        if (e.payload().get("round").equals(r) && e.recipient().equals(impostor)) {
          assertThat(contains(e.payload(), word)).as("the impostor was told the word").isFalse();
        }
      }
    }

    // Snapshots: a player's snapshot holds only their own role; nobody's names the impostor.
    Set<String> secrets = new HashSet<>();
    for (Published e : roles) {
      if (e.payload().get("word") != null) {
        secrets.add((String) e.payload().get("word"));
      }
    }
    for (Map.Entry<UUID, List<Map<String, Object>>> viewer : snapshots.entrySet()) {
      for (Map<String, Object> snapshot : viewer.getValue()) {
        assertThat(labelsImpostor(withoutOwnRole(snapshot))).isFalse();
        @SuppressWarnings("unchecked")
        Map<String, Object> role = (Map<String, Object>) snapshot.get("role");
        if (role != null && role.get("role").equals("IMPOSTOR")) {
          // The impostor sees their own role and no word at all.
          assertThat(role.get("word")).isNull();
          secrets.forEach(w -> assertThat(contains(snapshot, w)).isFalse());
        }
      }
    }
    // Exactly the impostor's own snapshot says IMPOSTOR, and only about themselves.
    for (Map.Entry<UUID, List<Map<String, Object>>> viewer : snapshots.entrySet()) {
      for (Map<String, Object> snapshot : viewer.getValue()) {
        @SuppressWarnings("unchecked")
        Map<String, Object> role = (Map<String, Object>) snapshot.get("role");
        if (role == null) {
          continue;
        }
        UUID viewerId = viewer.getKey();
        boolean saysImpostor = role.get("role").equals("IMPOSTOR");
        long impostorRoles =
            roles.stream()
                .filter(e -> e.recipient().equals(viewerId))
                .filter(e -> e.payload().get("round").equals(snapshot.get("round")))
                .filter(e -> e.payload().get("role").equals("IMPOSTOR"))
                .count();
        assertThat(saysImpostor).isEqualTo(impostorRoles == 1);
      }
    }
  }

  /**
   * The snapshot minus its own {@code role} entry, which legitimately says IMPOSTOR to its owner.
   */
  private static Map<String, Object> withoutOwnRole(Map<String, Object> snapshot) {
    Map<String, Object> copy = new HashMap<>(snapshot);
    copy.remove("role");
    return copy;
  }

  @Test
  void aSpectatorOrOutsiderGetsNoGameSnapshotAndSoNoRoleOrWord() {
    play(4, 3);
    assertThat(f.sessions.gameSnapshot(CODE, spectator)).isNull();
  }

  @Test
  void everyRoleOfARoundIsOneTransitionOfTheSameShape() {
    play(6, 3);
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
