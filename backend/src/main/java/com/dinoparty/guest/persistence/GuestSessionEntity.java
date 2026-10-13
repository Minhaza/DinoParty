package com.dinoparty.guest.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A guest login. Only the SHA-256 hash of the bearer token is stored, never the token itself. */
@Entity
@Table(name = "guest_session")
public class GuestSessionEntity {

  @Id private UUID id;

  @Column(name = "guest_id", nullable = false)
  private UUID guestId;

  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected GuestSessionEntity() {}

  public GuestSessionEntity(
      UUID id, UUID guestId, String tokenHash, Instant now, Instant expiresAt) {
    this.id = id;
    this.guestId = guestId;
    this.tokenHash = tokenHash;
    this.createdAt = now;
    this.lastSeenAt = now;
    this.expiresAt = expiresAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getGuestId() {
    return guestId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public boolean isActiveAt(Instant instant) {
    return revokedAt == null && expiresAt.isAfter(instant);
  }

  public void renew(Instant now, Instant newExpiry) {
    this.lastSeenAt = now;
    this.expiresAt = newExpiry;
  }

  public void revoke(Instant now) {
    this.revokedAt = now;
  }
}
