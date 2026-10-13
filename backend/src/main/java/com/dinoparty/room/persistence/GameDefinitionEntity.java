package com.dinoparty.room.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Read-only view of the seeded {@code game_definition} catalog. */
@Entity
@Table(name = "game_definition")
public class GameDefinitionEntity {

  @Id private String key;

  @Column(nullable = false)
  private String name;

  @Column(name = "min_players", nullable = false)
  private short minPlayers;

  @Column(name = "max_players", nullable = false)
  private short maxPlayers;

  @Column(nullable = false)
  private boolean enabled;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "default_settings", nullable = false)
  private String defaultSettings;

  protected GameDefinitionEntity() {}

  public String getKey() {
    return key;
  }

  public String getName() {
    return name;
  }

  public int getMinPlayers() {
    return minPlayers;
  }

  public int getMaxPlayers() {
    return maxPlayers;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getDefaultSettings() {
    return defaultSettings;
  }
}
