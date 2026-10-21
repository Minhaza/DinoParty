package com.dinoparty.room;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.room.persistence.GameDefinitionEntity;
import com.dinoparty.room.persistence.GameDefinitionRepository;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Games a room may be created with: a registered game (from the {@link GameDirectory}) whose {@code
 * game_definition} row is enabled. The row only decides availability and anchors the foreign keys;
 * name, bounds and settings come from the game module.
 */
@Component
public class GameCatalog {

  private final GameDefinitionRepository repository;
  // Resolved on use: the game modules depend (through the word pools) on the room module.
  private final ObjectProvider<GameDirectory> directory;

  GameCatalog(GameDefinitionRepository repository, ObjectProvider<GameDirectory> directory) {
    this.repository = repository;
    this.directory = directory;
  }

  /** Returns an available game or fails with {@code GAME_NOT_FOUND}. */
  public GameInfo require(String gameKey) {
    return Optional.ofNullable(gameKey)
        .filter(key -> repository.findById(key).filter(GameDefinitionEntity::isEnabled).isPresent())
        .flatMap(key -> directory.getObject().find(key))
        .orElseThrow(() -> new DomainException(ErrorCode.GAME_NOT_FOUND, "Unknown game."));
  }

  /** Validated settings of an available game over its defaults (see {@link GameDirectory}). */
  public Map<String, Object> resolveSettings(String gameKey, Map<String, Object> supplied) {
    return directory.getObject().resolveSettings(gameKey, supplied);
  }

  /** Keys of the enabled {@code game_definition} rows. */
  public Set<String> enabledKeys() {
    return repository.findAll().stream()
        .filter(GameDefinitionEntity::isEnabled)
        .map(GameDefinitionEntity::getKey)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Player bounds stored in the enabled rows, for the startup check that they still equal the
   * modules' (the row keeps a copy because the Phase 2 schema has the columns).
   */
  public Map<String, int[]> enabledRowBounds() {
    return repository.findAll().stream()
        .filter(GameDefinitionEntity::isEnabled)
        .collect(
            Collectors.toUnmodifiableMap(
                GameDefinitionEntity::getKey,
                e -> new int[] {e.getMinPlayers(), e.getMaxPlayers()}));
  }
}
