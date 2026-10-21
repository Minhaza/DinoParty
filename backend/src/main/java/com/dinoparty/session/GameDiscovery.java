package com.dinoparty.session;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.engine.GameDefinition;
import com.dinoparty.engine.GameRegistry;
import com.dinoparty.room.GameCatalog;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Read side of the game catalog for the public game API: every registered game with its declared
 * metadata and settings, plus whether its {@code game_definition} row currently enables it.
 */
@Service
public class GameDiscovery {

  /** A registered game; {@code enabled} is false when rooms cannot be created with it. */
  public record AvailableGame(GameDefinition definition, boolean enabled) {}

  private final GameRegistry registry;
  private final GameCatalog catalog;

  GameDiscovery(GameRegistry registry, GameCatalog catalog) {
    this.registry = registry;
    this.catalog = catalog;
  }

  /** Every registered game in key order. */
  public List<AvailableGame> list() {
    Set<String> enabled = catalog.enabledKeys();
    return registry.definitions().stream()
        .map(d -> new AvailableGame(d, enabled.contains(d.key())))
        .toList();
  }

  /** One registered game; {@code GAME_NOT_FOUND} when no module declares the key. */
  public AvailableGame get(String gameKey) {
    GameDefinition definition =
        registry
            .definition(gameKey)
            .orElseThrow(() -> new DomainException(ErrorCode.GAME_NOT_FOUND, "Unknown game."));
    return new AvailableGame(definition, catalog.enabledKeys().contains(definition.key()));
  }
}
