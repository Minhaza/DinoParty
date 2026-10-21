package com.dinoparty.web;

import com.dinoparty.engine.GameDefinition;
import com.dinoparty.engine.SettingField;
import com.dinoparty.engine.SettingField.BoolField;
import com.dinoparty.engine.SettingField.EnumField;
import com.dinoparty.engine.SettingField.IntField;
import com.dinoparty.engine.SettingField.ListField;
import com.dinoparty.session.GameDiscovery;
import com.dinoparty.session.GameDiscovery.AvailableGame;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public game discovery (api-contracts §2.6). Everything here is mapped from the game modules'
 * declarations; this class holds no game data of its own.
 */
@RestController
@RequestMapping("/api/v1/games")
class GameController {

  record GameSummaryDto(
      String key,
      String name,
      String tagline,
      int minPlayers,
      int maxPlayers,
      String iconKey,
      boolean enabled) {}

  record GameDetailDto(
      String key,
      String name,
      String tagline,
      int minPlayers,
      int maxPlayers,
      String iconKey,
      boolean enabled,
      String description,
      List<String> howToPlay,
      List<SettingFieldDto> settingsSchema,
      Map<String, Object> defaultSettings) {}

  /** Members that do not apply to a field's {@code type} are omitted. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record SettingFieldDto(
      String key,
      String label,
      String type,
      @JsonProperty("default") Object defaultValue,
      Integer min,
      Integer max,
      Integer autoValue,
      List<?> options,
      Integer maxItems,
      String itemFormat,
      String exclusiveOption) {

    static SettingFieldDto from(SettingField field) {
      return switch (field) {
        case IntField f ->
            new SettingFieldDto(
                f.key(),
                f.label(),
                f.type(),
                f.defaultValue(),
                f.min(),
                f.max(),
                f.autoValue(),
                null,
                null,
                null,
                null);
        case BoolField f ->
            new SettingFieldDto(
                f.key(),
                f.label(),
                f.type(),
                f.defaultValue(),
                null,
                null,
                null,
                f.restricted() ? f.options().stream().sorted().toList() : null,
                null,
                null,
                null);
        case EnumField f ->
            new SettingFieldDto(
                f.key(),
                f.label(),
                f.type(),
                f.defaultValue(),
                null,
                null,
                null,
                f.options(),
                null,
                null,
                null);
        case ListField f ->
            new SettingFieldDto(
                f.key(),
                f.label(),
                f.type(),
                f.defaultValue(),
                null,
                null,
                null,
                null,
                f.maxItems(),
                f.format().wire(),
                f.exclusiveOption());
      };
    }
  }

  private final GameDiscovery games;

  GameController(GameDiscovery games) {
    this.games = games;
  }

  @GetMapping
  List<GameSummaryDto> list() {
    return games.list().stream().map(GameController::summary).toList();
  }

  @GetMapping("/{gameKey}")
  GameDetailDto get(@PathVariable String gameKey) {
    AvailableGame game = games.get(gameKey);
    GameDefinition d = game.definition();
    return new GameDetailDto(
        d.key(),
        d.metadata().name(),
        d.metadata().tagline(),
        d.minPlayers(),
        d.maxPlayers(),
        d.metadata().iconKey(),
        game.enabled(),
        d.metadata().description(),
        d.metadata().howToPlay(),
        d.settings().fields().stream().map(SettingFieldDto::from).toList(),
        d.settings().defaults());
  }

  private static GameSummaryDto summary(AvailableGame game) {
    GameDefinition d = game.definition();
    return new GameSummaryDto(
        d.key(),
        d.metadata().name(),
        d.metadata().tagline(),
        d.minPlayers(),
        d.maxPlayers(),
        d.metadata().iconKey(),
        game.enabled());
  }
}
