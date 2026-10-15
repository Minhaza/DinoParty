package com.dinoparty.words.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;

/** Read-only view of {@code word_category_label}: one label per category and locale. */
@Entity
@Table(name = "word_category_label")
public class WordCategoryLabelEntity {

  @Embeddable
  public record Id(
      @Column(name = "category_key", nullable = false, length = 40) String categoryKey,
      @Column(nullable = false, length = 16) String locale)
      implements Serializable {}

  @EmbeddedId private Id id;

  @Column(nullable = false, length = 60)
  private String label;

  protected WordCategoryLabelEntity() {}

  public String getCategoryKey() {
    return id.categoryKey();
  }

  public String getLocale() {
    return id.locale();
  }

  public String getLabel() {
    return label;
  }
}
