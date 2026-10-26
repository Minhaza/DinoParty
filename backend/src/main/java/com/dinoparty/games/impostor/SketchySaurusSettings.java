package com.dinoparty.games.impostor;

import com.dinoparty.engine.SettingField.BoolField;
import com.dinoparty.engine.SettingField.EnumField;
import com.dinoparty.engine.SettingField.IntField;
import com.dinoparty.engine.SettingsSchema;
import com.dinoparty.games.impostor.DinoImpostorSettings.IntBound;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The host's settings of one Sketchy Saurus session (game-rules Game 4 settings, api-contracts
 * §2.2) and their {@link #SCHEMA}. The shared ones have the bounds of Dino Impostor. A missing or
 * unusable value falls back to the default instead of failing the game. The catalog row ({@code
 * V11}) keeps a non-authoritative copy of the defaults.
 *
 * @param rounds 0 = automatic (the player count at start, clamped 3..10), else 3..10 (D59)
 * @param impostorKnows whether the impostor is told their role; off gives them a decoy word (D63)
 * @param unawareGuess whether a caught impostor who did not know gets a guess; ignored when {@code
 *     impostorKnows} is on (D63)
 */
record SketchySaurusSettings(
    int rounds,
    int sketchSeconds,
    int voteSeconds,
    int guessSeconds,
    ImpostorDifficulty difficulty,
    boolean impostorKnows,
    boolean unawareGuess) {

  static final IntBound SKETCH_SECONDS = new IntBound(30, 120, 60);
  static final boolean DEFAULT_IMPOSTOR_KNOWS = true;
  static final boolean DEFAULT_UNAWARE_GUESS = false;

  public static final SettingsSchema SCHEMA =
      new SettingsSchema(
          List.of(
              IntField.withAuto(
                  "rounds",
                  "Rounds",
                  DinoImpostorSettings.MIN_ROUNDS,
                  DinoImpostorSettings.MAX_ROUNDS,
                  DinoImpostorSettings.AUTO_ROUNDS,
                  0),
              intField("sketchSeconds", "Sketching time", SKETCH_SECONDS),
              intField("voteSeconds", "Voting time", DinoImpostorSettings.VOTE_SECONDS),
              intField("guessSeconds", "Guessing time", DinoImpostorSettings.GUESS_SECONDS),
              new EnumField(
                  "difficulty",
                  "Difficulty",
                  Arrays.stream(ImpostorDifficulty.values()).map(ImpostorDifficulty::key).toList(),
                  ImpostorDifficulty.MIXED.key()),
              BoolField.of("impostorKnows", "Impostor knows their role", DEFAULT_IMPOSTOR_KNOWS),
              BoolField.of("unawareGuess", "Unaware impostor may guess", DEFAULT_UNAWARE_GUESS)));

  /** Every setting with its default ({@link #SCHEMA}). */
  public static Map<String, Object> defaults() {
    return SCHEMA.defaults();
  }

  static SketchySaurusSettings from(Map<String, Object> settings) {
    Map<String, Object> s = settings == null ? Map.of() : settings;
    Object rounds = s.get("rounds");
    boolean validRounds =
        rounds instanceof Number n
            && n.doubleValue() == n.longValue()
            && n.longValue() >= DinoImpostorSettings.MIN_ROUNDS
            && n.longValue() <= DinoImpostorSettings.MAX_ROUNDS;
    return new SketchySaurusSettings(
        validRounds ? ((Number) rounds).intValue() : DinoImpostorSettings.AUTO_ROUNDS,
        integer(s, "sketchSeconds", SKETCH_SECONDS),
        integer(s, "voteSeconds", DinoImpostorSettings.VOTE_SECONDS),
        integer(s, "guessSeconds", DinoImpostorSettings.GUESS_SECONDS),
        ImpostorDifficulty.fromKey(s.get("difficulty")).orElse(ImpostorDifficulty.MIXED),
        bool(s, "impostorKnows", DEFAULT_IMPOSTOR_KNOWS),
        bool(s, "unawareGuess", DEFAULT_UNAWARE_GUESS));
  }

  /** [DI-2] the effective round count for a frozen roster of {@code players}. */
  int rounds(int players) {
    return rounds == DinoImpostorSettings.AUTO_ROUNDS
        ? Math.clamp(players, DinoImpostorSettings.MIN_ROUNDS, DinoImpostorSettings.MAX_ROUNDS)
        : rounds;
  }

  /**
   * Words to draw at start: the roster is not frozen yet when the host starts, so the automatic
   * count draws for the most rounds it can reach.
   */
  int wordsToDraw() {
    return rounds == DinoImpostorSettings.AUTO_ROUNDS ? DinoImpostorSettings.MAX_ROUNDS : rounds;
  }

  /** [SS-3]: whether a caught impostor gets a {@code GUESS} phase. */
  boolean impostorMayGuess() {
    return impostorKnows || unawareGuess;
  }

  private static IntField intField(String key, String label, IntBound bound) {
    return IntField.of(key, label, bound.min(), bound.max(), bound.defaultValue());
  }

  private static int integer(Map<String, Object> settings, String key, IntBound bound) {
    if (settings.get(key) instanceof Number n
        && n.doubleValue() == n.longValue()
        && n.longValue() >= bound.min()
        && n.longValue() <= bound.max()) {
      return n.intValue();
    }
    return bound.defaultValue();
  }

  private static boolean bool(Map<String, Object> settings, String key, boolean fallback) {
    return settings.get(key) instanceof Boolean b ? b : fallback;
  }
}
