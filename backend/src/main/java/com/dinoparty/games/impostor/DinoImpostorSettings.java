package com.dinoparty.games.impostor;

import com.dinoparty.engine.SettingField.EnumField;
import com.dinoparty.engine.SettingField.IntField;
import com.dinoparty.engine.SettingsSchema;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The host's settings of one Dino Impostor session (game-rules Game 3 settings, api-contracts §2.2)
 * and their {@link #SCHEMA}, the one declaration of their keys, bounds and defaults. A missing or
 * unusable value falls back to the default instead of failing the game. The catalog row ({@code
 * V11}) keeps a non-authoritative copy of the defaults.
 *
 * @param rounds 0 = automatic (the player count at start, clamped 3..10), else 3..10 (D59)
 */
record DinoImpostorSettings(
    int rounds,
    int laps,
    int turnSeconds,
    int voteSeconds,
    int guessSeconds,
    ImpostorDifficulty difficulty) {

  record IntBound(int min, int max, int defaultValue) {}

  static final IntBound LAPS = new IntBound(1, 3, 2);
  static final IntBound TURN_SECONDS = new IntBound(10, 60, 25);
  static final IntBound VOTE_SECONDS = new IntBound(30, 120, 60);
  static final IntBound GUESS_SECONDS = new IntBound(15, 60, 30);

  static final int MIN_ROUNDS = 3;
  static final int MAX_ROUNDS = 10;
  static final int AUTO_ROUNDS = 0;

  public static final SettingsSchema SCHEMA =
      new SettingsSchema(
          List.of(
              IntField.withAuto("rounds", "Rounds", MIN_ROUNDS, MAX_ROUNDS, AUTO_ROUNDS, 0),
              intField("laps", "Laps around the table", LAPS),
              intField("turnSeconds", "Turn time", TURN_SECONDS),
              intField("voteSeconds", "Voting time", VOTE_SECONDS),
              intField("guessSeconds", "Guessing time", GUESS_SECONDS),
              new EnumField(
                  "difficulty",
                  "Difficulty",
                  Arrays.stream(ImpostorDifficulty.values()).map(ImpostorDifficulty::key).toList(),
                  ImpostorDifficulty.MIXED.key())));

  /** Every setting with its default ({@link #SCHEMA}). */
  public static Map<String, Object> defaults() {
    return SCHEMA.defaults();
  }

  static DinoImpostorSettings from(Map<String, Object> settings) {
    Map<String, Object> s = settings == null ? Map.of() : settings;
    Object rounds = s.get("rounds");
    boolean validRounds =
        rounds instanceof Number n
            && n.doubleValue() == n.longValue()
            && n.longValue() >= MIN_ROUNDS
            && n.longValue() <= MAX_ROUNDS;
    return new DinoImpostorSettings(
        validRounds ? ((Number) rounds).intValue() : AUTO_ROUNDS,
        integer(s, "laps", LAPS),
        integer(s, "turnSeconds", TURN_SECONDS),
        integer(s, "voteSeconds", VOTE_SECONDS),
        integer(s, "guessSeconds", GUESS_SECONDS),
        ImpostorDifficulty.fromKey(s.get("difficulty")).orElse(ImpostorDifficulty.MIXED));
  }

  /** [DI-2] the effective round count for a frozen roster of {@code players}. */
  int rounds(int players) {
    return rounds == AUTO_ROUNDS ? Math.clamp(players, MIN_ROUNDS, MAX_ROUNDS) : rounds;
  }

  /**
   * Words to draw at start: the roster is not frozen yet when the host starts, so the automatic
   * count draws for the most rounds it can reach.
   */
  int wordsToDraw() {
    return rounds == AUTO_ROUNDS ? MAX_ROUNDS : rounds;
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
}
