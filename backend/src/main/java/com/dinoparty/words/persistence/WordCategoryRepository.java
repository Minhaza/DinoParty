package com.dinoparty.words.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WordCategoryRepository extends JpaRepository<WordCategoryEntity, String> {

  List<WordCategoryEntity> findByActiveTrueOrderBySortOrderAsc();
}
