package com.dinoparty.engine;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Static facts about a game. {@code publicEvents}/{@code privateEvents} declare every outbound
 * event type and its audience; the engine rejects a transition that publishes a type on the wrong
 * audience, which keeps secrets off the public stream by construction.
 */
public record GameDefinition(
    String key,
    int minPlayers,
    int maxPlayers,
    int minActivePlayers,
    boolean scored,
    List<CommandSpec> commands,
    Set<String> publicEvents,
    Set<String> privateEvents) {

  private static final Pattern KEY = Pattern.compile("[a-z][a-z-]{1,39}");

  public GameDefinition {
    if (key == null || !KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("Invalid game key");
    }
    if (minPlayers < 1 || maxPlayers < minPlayers) {
      throw new IllegalArgumentException("Invalid player bounds for " + key);
    }
    if (minActivePlayers < 1 || minActivePlayers > minPlayers) {
      throw new IllegalArgumentException("minActivePlayers must be within 1.." + minPlayers);
    }
    commands = List.copyOf(commands);
    publicEvents = Set.copyOf(publicEvents);
    privateEvents = Set.copyOf(privateEvents);
    if (publicEvents.stream().anyMatch(privateEvents::contains)) {
      throw new IllegalArgumentException("An event type cannot be both public and private");
    }
  }

  public Map<String, CommandSpec> commandsByType() {
    return commands.stream().collect(Collectors.toUnmodifiableMap(CommandSpec::type, c -> c));
  }
}
