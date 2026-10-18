package com.dinoparty.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * All registered game modules, in key order; the one authority for which games exist and for their
 * metadata, bounds, settings schema and commands. Adding a game means registering a module (and its
 * {@code game_definition} availability row), nothing else. Construction fails on duplicate keys and
 * on a command or event type declared by two modules, so a clash is found at startup and not in the
 * middle of a game.
 */
@Component
public class GameRegistry {

  private final Map<String, GameModule> modules = new LinkedHashMap<>();
  private final Map<String, CommandSpec> commandSpecs = new HashMap<>();

  public GameRegistry(List<GameModule> registered) {
    Map<String, String> commandOwners = new HashMap<>();
    Map<String, String> eventOwners = new HashMap<>();
    List<GameModule> ordered =
        registered.stream()
            .sorted(Comparator.comparing((GameModule m) -> m.definition().key()))
            .toList();
    for (GameModule module : ordered) {
      GameDefinition definition = module.definition();
      if (modules.putIfAbsent(definition.key(), module) != null) {
        throw new IllegalStateException("Duplicate game key: " + definition.key());
      }
      for (CommandSpec spec : definition.commands()) {
        String previous = commandOwners.putIfAbsent(spec.type(), definition.key());
        if (previous != null) {
          throw new IllegalStateException(
              "Command " + spec.type() + " declared by " + previous + " and " + definition.key());
        }
        commandSpecs.put(spec.type(), spec);
      }
      Set<String> events = new HashSet<>(definition.publicEvents());
      events.addAll(definition.privateEvents());
      for (String event : events) {
        String previous = eventOwners.putIfAbsent(event, definition.key());
        if (previous != null) {
          throw new IllegalStateException(
              "Event " + event + " declared by " + previous + " and " + definition.key());
        }
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
