package com.dinoparty.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * All registered game modules, in key order; the one authority for which games exist and for their
 * metadata, bounds, settings schema and commands. Adding a game means registering a module (and its
 * {@code game_definition} availability row), nothing else. Construction fails on duplicate keys, so
 * a clash is found at startup and not in the middle of a game. A command or event type may be
 * declared by several modules only when they share it on purpose (D65, games of one family): the
 * {@link CommandSpec}s must be equal and the event's audience (public or private) the same; any
 * difference fails startup.
 */
@Component
public class GameRegistry {

  private final Map<String, GameModule> modules = new LinkedHashMap<>();
  private final Map<String, CommandSpec> commandSpecs = new HashMap<>();

  public GameRegistry(List<GameModule> registered) {
    Map<String, Declared<CommandSpec>> commandOwners = new HashMap<>();
    Map<String, Declared<Boolean>> eventOwners = new HashMap<>();
    List<GameModule> ordered =
        registered.stream()
            .sorted(Comparator.comparing((GameModule m) -> m.definition().key()))
            .toList();
    for (GameModule module : ordered) {
      GameDefinition definition = module.definition();
      String key = definition.key();
      if (modules.putIfAbsent(key, module) != null) {
        throw new IllegalStateException("Duplicate game key: " + key);
      }
      for (CommandSpec spec : definition.commands()) {
        Declared<CommandSpec> previous =
            commandOwners.putIfAbsent(spec.type(), new Declared<>(key, spec));
        if (previous != null && previous.owner().equals(key)) {
          throw new IllegalStateException("Command " + spec.type() + " declared twice by " + key);
        }
        if (previous != null && !previous.what().equals(spec)) {
          throw new IllegalStateException(
              "Command "
                  + spec.type()
                  + " declared by "
                  + previous.owner()
                  + " and "
                  + key
                  + " with different specs ("
                  + previous.what()
                  + " vs "
                  + spec
                  + ")");
        }
        commandSpecs.put(spec.type(), spec);
      }
      declareEvents(eventOwners, key, definition.publicEvents(), true);
      declareEvents(eventOwners, key, definition.privateEvents(), false);
    }
  }

  /** A type with the game that declared it first, to name both games in a clash. */
  private record Declared<T>(String owner, T what) {}

  /** D65: the same event type may come from several games of a family if its audience is equal. */
  private static void declareEvents(
      Map<String, Declared<Boolean>> owners, String key, Set<String> events, boolean isPublic) {
    for (String event : events) {
      Declared<Boolean> previous = owners.putIfAbsent(event, new Declared<>(key, isPublic));
      if (previous == null) {
        continue;
      }
      if (previous.what() != isPublic) {
        throw new IllegalStateException(
            "Event "
                + event
                + " is "
                + (previous.what() ? "public" : "private")
                + " in "
                + previous.owner()
                + " and "
                + (isPublic ? "public" : "private")
                + " in "
                + key);
      }
      if (previous.owner().equals(key)) {
        throw new IllegalStateException("Event " + event + " declared twice by " + key);
      }
    }
  }

  public Optional<GameModule> find(String key) {
    return Optional.ofNullable(modules.get(key));
  }

  public Optional<GameDefinition> definition(String key) {
    return find(key).map(GameModule::definition);
  }

  /** The spec for a client command type of any registered game; used by the transport. */
  public Optional<CommandSpec> commandSpec(String type) {
    return Optional.ofNullable(commandSpecs.get(type));
  }

  public List<GameDefinition> definitions() {
    return modules.values().stream().map(GameModule::definition).toList();
  }

  /**
   * Compares the modules with the enabled catalog rows (key to min/max players). A bounds mismatch
   * throws; the returned list holds a module without an enabled row and, when strict, an enabled
   * row without a module (a room of that game could never start).
   */
  public List<String> verifyAgainstCatalog(Map<String, int[]> enabledRows, boolean strict) {
    List<String> problems = new ArrayList<>();
    for (GameDefinition d : definitions()) {
      int[] row = enabledRows.get(d.key());
      if (row == null) {
        problems.add("Module " + d.key() + " has no enabled game_definition row");
      } else if (row[0] != d.minPlayers() || row[1] != d.maxPlayers()) {
        throw new IllegalStateException("Player bounds of " + d.key() + " differ from its row");
      }
    }
    if (strict) {
      enabledRows.keySet().stream()
          .filter(k -> !modules.containsKey(k))
          .forEach(k -> problems.add("Enabled game " + k + " has no module"));
    }
    return problems;
  }
}
