package com.dinoparty.session;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.engine.GameDefinition;
import com.dinoparty.engine.GameRegistry;
import com.dinoparty.room.GameDirectory;
import com.dinoparty.room.GameInfo;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Serves the room module's game lookups from the registered game modules. */
@Component
class RegistryGameDirectory implements GameDirectory {

  private final GameRegistry registry;

  RegistryGameDirectory(GameRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Optional<GameInfo> find(String gameKey) {
    return registry
        .definition(gameKey)
        .map(d -> new GameInfo(d.key(), d.metadata().name(), d.minPlayers(), d.maxPlayers()));
  }

  @Override
  public Map<String, Object> resolveSettings(String gameKey, Map<String, Object> supplied) {
    GameDefinition definition =
        registry
            .definition(gameKey)
            .orElseThrow(() -> new DomainException(ErrorCode.GAME_NOT_FOUND, "Unknown game."));
    return definition.settings().resolve(supplied);
  }
}
