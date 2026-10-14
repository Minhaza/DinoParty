package com.dinoparty.wordpacks.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WordPackEntryRepository extends JpaRepository<WordPackEntryEntity, UUID> {

  List<WordPackEntryEntity> findByPackIdOrderByPositionAscIdAsc(UUID packId);

  List<WordPackEntryEntity> findByPackIdInOrderByPackIdAscPositionAscIdAsc(
      Collection<UUID> packIds);

  Optional<WordPackEntryEntity> findByIdAndPackId(UUID id, UUID packId);

  @Query("select e.textKey from WordPackEntryEntity e where e.packId = :packId")
  List<String> keysOf(@Param("packId") UUID packId);

  @Query("select coalesce(max(e.position), -1) from WordPackEntryEntity e where e.packId = :packId")
  int maxPosition(@Param("packId") UUID packId);

  @Query("select count(e) from WordPackEntryEntity e where e.packId = :packId")
  int countByPack(@Param("packId") UUID packId);

  @Modifying
  @Query("delete from WordPackEntryEntity e where e.packId = :packId")
  void deleteByPackId(@Param("packId") UUID packId);
}
