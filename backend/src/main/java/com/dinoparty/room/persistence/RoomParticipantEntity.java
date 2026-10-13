package com.dinoparty.room.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_participant")
public class RoomParticipantEntity {

  @Id private UUID id;

  @Column(name = "room_id", nullable = false)
  private UUID roomId;

  @Column(name = "guest_id", nullable = false)
  private UUID guestId;

  @Column(name = "joined_at", nullable = false)
  private Instant joinedAt;

  @Column(name = "left_at")
  private Instant leftAt;

  @Column(nullable = false)
  private boolean ready;

  protected RoomParticipantEntity() {}

  public RoomParticipantEntity(UUID id, UUID roomId, UUID guestId, Instant joinedAt) {
    this.id = id;
    this.roomId = roomId;
    this.guestId = guestId;
    this.joinedAt = joinedAt;
  }

  public UUID getRoomId() {
    return roomId;
  }

  public UUID getGuestId() {
    return guestId;
  }

  public Instant getJoinedAt() {
    return joinedAt;
  }

  public boolean isActive() {
    return leftAt == null;
  }

  public void leave(Instant now) {
    this.leftAt = now;
    this.ready = false;
  }

  public boolean isReady() {
    return ready;
  }

  public void setReady(boolean ready) {
    this.ready = ready;
  }

  public void rejoin(Instant now) {
    this.joinedAt = now;
    this.leftAt = null;
    this.ready = false;
  }
}
