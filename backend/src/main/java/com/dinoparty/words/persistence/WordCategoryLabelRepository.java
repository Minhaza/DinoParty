package com.dinoparty.words.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WordCategoryLabelRepository
    extends JpaRepository<WordCategoryLabelEntity, WordCategoryLabelEntity.Id> {

  List<WordCategoryLabelEntity> findByIdLocaleIn(Collection<String> locales);
}
