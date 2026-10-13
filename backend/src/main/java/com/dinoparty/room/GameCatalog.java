package com.dinoparty.room;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.room.persistence.GameDefinitionEntity;
import com.dinoparty.room.persistence.GameDefinitionRepository;
import org.springframework.stereotype.Component;

/** Lookup of the seeded {@code game_definition} rows. */
@Component
class GameCatalog {

  private final GameDefinitionRepository repository;
  private final SettingsCodec settingsCodec;

  GameCatalog(GameDefinitionRepository repository, SettingsCodec settingsCodec) {
    this.repository = repository;
    this.settingsCodec = settingsCodec;
  }

  /** Returns an enabled game or fails with {@code GAME_NOT_FOUND}. */
  GameInfo require(String gameKey) {
    return repository
        .findById(gameKey)
        .filter(GameDefinitionEntity::isEnabled)
        .map(this::toInfo)
        .orElseThrow(() -> new DomainException(ErrorCode.GAME_NOT_FOUND, "Unknown game."));
  }

  private GameInfo toInfo(GameDefinitionEntity e) {
    return new GameInfo(
        e.getKey(),
        e.getName(),
        e.getMinPlayers(),
        e.getMaxPlayers(),
        e.isEnabled(),
        settingsCodec.read(e.getDefaultSettings()));
  }
}
