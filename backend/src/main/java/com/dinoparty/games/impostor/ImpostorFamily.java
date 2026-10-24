package com.dinoparty.games.impostor;

import com.dinoparty.engine.CommandSpec;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The one declaration of what Dino Impostor and Sketchy Saurus share (R40, D65): the {@code
 * IMPOSTOR_*} command specs with the union of the family's phases, the shared event types with
 * their audience, and the numbers of [DI-8]..[DI-14]. Both modules declare these constants, and
 * {@code GameRegistry} fails startup if a declaration ever differs. A module rejects a phase its
 * own game never enters (the chat and vote of Dino Impostor in {@code GALLERY}).
 */
final class ImpostorFamily {

  private ImpostorFamily() {}

  static final String VOTE_PHASE = "VOTE";
  static final String GALLERY_PHASE = "GALLERY";
  static final String GUESS_PHASE = "GUESS";
  static final String ROUND_END_PHASE = "ROUND_END";

  static final String CHAT = "IMPOSTOR_CHAT";
  static final String VOTE = "IMPOSTOR_VOTE";
  static final String GUESS = "IMPOSTOR_GUESS";

  static final String ROLE_EVENT = "IMPOSTOR_ROLE";
  static final String CHAT_MESSAGE_EVENT = "IMPOSTOR_CHAT_MESSAGE";
  static final String VOTE_PROGRESS_EVENT = "IMPOSTOR_VOTE_PROGRESS";
  static final String VOTE_CLOSED_EVENT = "IMPOSTOR_VOTE_CLOSED";
  static final String GUESS_OPEN_EVENT = "IMPOSTOR_GUESS_OPEN";
  static final String ROUND_ENDED_EVENT = "IMPOSTOR_ROUND_ENDED";

  /**
   * The transport only counts per second, so the rate and burst of chat and vote are a backstop;
   * the exact 10 s window of [DI-8]/[DI-9] is enforced by {@link RateWindow}.
   */
  static final CommandSpec CHAT_SPEC =
      new CommandSpec(CHAT, 1024, 0.5, 5, Set.of(VOTE_PHASE, GALLERY_PHASE));

  static final CommandSpec VOTE_SPEC =
      new CommandSpec(VOTE, 256, 1.0, 10, Set.of(VOTE_PHASE, GALLERY_PHASE));

  static final CommandSpec GUESS_SPEC = new CommandSpec(GUESS, 1024, 2, 3, Set.of(GUESS_PHASE));

  static final List<CommandSpec> COMMANDS = List.of(CHAT_SPEC, VOTE_SPEC, GUESS_SPEC);

  static final Set<String> PUBLIC_EVENTS =
      Set.of(CHAT_MESSAGE_EVENT, VOTE_PROGRESS_EVENT, VOTE_CLOSED_EVENT, ROUND_ENDED_EVENT);

  static final Set<String> PRIVATE_EVENTS = Set.of(ROLE_EVENT, GUESS_OPEN_EVENT);

  static final Duration ROUND_END_TIME = Duration.ofSeconds(8);

  /** [DI-9]: the vote cannot close early before this much of the phase has passed. */
  static final Duration VOTE_MINIMUM = Duration.ofSeconds(15);

  static final Duration RATE_WINDOW = Duration.ofSeconds(10);
  static final int CHATS_PER_WINDOW = 5;
  static final int VOTES_PER_WINDOW = 10;

  static final int CHAT_MAX_LENGTH = 120;
  static final int CHAT_KEPT = 30;
  static final int GUESS_MAX_LENGTH = 80;

  /** Internal deadline of the 15 s mark; {@code phase-end} is the engine's. */
  static final String VOTE_MINIMUM_DEADLINE = "impostor-vote-min";
}
