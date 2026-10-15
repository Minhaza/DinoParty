package com.dinoparty.words.persistence;

import com.dinoparty.words.WordDifficulty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Read-only view of the seeded {@code word_catalog_entry} rows. */
@Entity
@Table(name = "word_catalog_entry")
public class WordCatalogEntryEntity {

  @Id private UUID id;

  @Column(nullable = false, length = 16)
  private String locale;

  @Column(name = "display_text", nullable = false, length = 20)
  private String displayText;

  @Column(name = "category_key", nullable = false, length = 40)
  private String categoryKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 8)
  private WordDifficulty difficulty;

  @Column(nullable = false)
  private boolean active;

  protected WordCatalogEntryEntity() {}

  public UUID getId() {
    return id;
  }

  public String getLocale() {
    return locale;
  }

  public String getDisplayText() {
    return displayText;
  }

  public String getCategoryKey() {
    return categoryKey;
  }

  public WordDifficulty getDifficulty() {
    return difficulty;
  }

  public boolean isActive() {
    return active;
  }
}
