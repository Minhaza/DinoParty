package com.dinoparty.room.persistence;

import com.dinoparty.room.RoomStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "room")
public class RoomEntity {

  @Id private UUID id;

  @Column(nullable = false, length = 6)
  private String code;

  @Column(name = "game_key", nullable = false, length = 40)
  private String gameKey;

  @Column(name = "host_guest_id", nullable = false)
  private UUID hostGuestId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private RoomStatus status;

  @Column(name = "max_players", nullable = false)
  private int maxPlayers;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private String settings;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  protected RoomEntity() {}

  public RoomEntity(
      UUID id,
      String code,
      String gameKey,
      UUID hostGuestId,
      int maxPlayers,
      String settings,
      Instant now) {
    this.id = id;
    this.code = code;
    this.gameKey = gameKey;
    this.hostGuestId = hostGuestId;
    this.status = RoomStatus.WAITING;
    this.maxPlayers = maxPlayers;
    this.settings = settings;
    this.createdAt = now;
  }

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getGameKey() {
    return gameKey;
  }

  public UUID getHostGuestId() {
    return hostGuestId;
  }

  public RoomStatus getStatus() {
    return status;
  }

  public int getMaxPlayers() {
    return maxPlayers;
  }

  public String getSettings() {
    return settings;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void transferHost(UUID newHostGuestId) {
    this.hostGuestId = newHostGuestId;
  }

  public void configure(String gameKey, String settings, int maxPlayers) {
    this.gameKey = gameKey;
    this.settings = settings;
    this.maxPlayers = maxPlayers;
  }

  public void changeStatus(RoomStatus status) {
    this.status = status;
  }

  public void close(Instant now) {
    this.status = RoomStatus.CLOSED;
    this.closedAt = now;
  }
}
