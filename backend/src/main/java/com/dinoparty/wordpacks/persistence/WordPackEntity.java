package com.dinoparty.wordpacks.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A room-scoped custom word pack. {@code wordCount} and {@code contentVersion} follow its entries.
 */
@Entity
@Table(name = "word_pack")
public class WordPackEntity {

  @Id private UUID id;

  @Column(name = "room_id", nullable = false, updatable = false)
  private UUID roomId;

  @Column(nullable = false, length = 80)
  private String name;

  @Column(nullable = false, length = 280)
  private String description;

  @Column(name = "word_count", nullable = false)
  private int wordCount;

  @Column(name = "content_version", nullable = false)
  private long contentVersion;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WordPackEntity() {}

  public WordPackEntity(UUID id, UUID roomId, String name, String description, Instant now) {
    this.id = id;
    this.roomId = roomId;
    this.name = name;
    this.description = description;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void rename(String name, Instant now) {
    this.name = name;
    this.updatedAt = now;
  }

  public void describe(String description, Instant now) {
    this.description = description;
    this.updatedAt = now;
  }

  /** Records a change of the entries: new count, and a new version that stales open previews. */
  public void entriesChanged(int wordCount, Instant now) {
    this.wordCount = wordCount;
    this.contentVersion++;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRoomId() {
    return roomId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public int getWordCount() {
    return wordCount;
  }

  public long getContentVersion() {
    return contentVersion;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
