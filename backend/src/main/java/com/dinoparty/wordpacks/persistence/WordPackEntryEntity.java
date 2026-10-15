package com.dinoparty.wordpacks.persistence;

import com.dinoparty.words.WordDifficulty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One word of a pack. {@code textKey} is the case-insensitive duplicate key, unique per pack. */
@Entity
@Table(name = "word_pack_entry")
public class WordPackEntryEntity {

  @Id private UUID id;

  @Column(name = "pack_id", nullable = false, updatable = false)
  private UUID packId;

  @Column(nullable = false, updatable = false)
  private int position;

  @Column(nullable = false, updatable = false, length = 80)
  private String text;

  @Column(name = "text_key", nullable = false, updatable = false)
  private String textKey;

  @Column(nullable = false, updatable = false, length = 40)
  private String category;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, length = 8)
  private WordDifficulty difficulty;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected WordPackEntryEntity() {}

  public WordPackEntryEntity(
      UUID id,
      UUID packId,
      int position,
      String text,
      String textKey,
      String category,
      WordDifficulty difficulty,
      Instant createdAt) {
    this.id = id;
    this.packId = packId;
    this.position = position;
    this.text = text;
    this.textKey = textKey;
    this.category = category;
    this.difficulty = difficulty;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPackId() {
    return packId;
  }

  public int getPosition() {
    return position;
  }

  public String getText() {
    return text;
  }

  public String getTextKey() {
    return textKey;
  }

  public String getCategory() {
    return category;
  }

  public WordDifficulty getDifficulty() {
    return difficulty;
  }
}
