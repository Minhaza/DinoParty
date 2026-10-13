package com.dinoparty.guest.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "guest_identity")
public class GuestIdentityEntity {

  @Id private UUID id;

  @Column(nullable = false, length = 20)
  private String nickname;

  @Column(name = "avatar_key", nullable = false, length = 40)
  private String avatarKey;

  @Column(name = "avatar_color", length = 16)
  private String avatarColor;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected GuestIdentityEntity() {}

  public GuestIdentityEntity(
      UUID id, String nickname, String avatarKey, String avatarColor, Instant now) {
    this.id = id;
    this.nickname = nickname;
    this.avatarKey = avatarKey;
    this.avatarColor = avatarColor;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public String getNickname() {
    return nickname;
  }

  public String getAvatarKey() {
    return avatarKey;
  }

  public String getAvatarColor() {
    return avatarColor;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void updateProfile(String nickname, String avatarKey, String avatarColor, Instant now) {
    this.nickname = nickname;
    this.avatarKey = avatarKey;
    this.avatarColor = avatarColor;
    this.updatedAt = now;
  }
}
