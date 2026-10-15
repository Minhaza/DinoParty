package com.dinoparty.words.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read-only view of the seeded {@code word_category} rows. */
@Entity
@Table(name = "word_category")
public class WordCategoryEntity {

  @Id private String key;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(nullable = false)
  private boolean active;

  protected WordCategoryEntity() {}

  public String getKey() {
    return key;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public boolean isActive() {
    return active;
  }
}
