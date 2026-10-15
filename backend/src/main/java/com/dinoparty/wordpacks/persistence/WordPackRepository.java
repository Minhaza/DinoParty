package com.dinoparty.wordpacks.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WordPackRepository extends JpaRepository<WordPackEntity, UUID> {

  /** A pack of this room only: the room scope of every lookup. */
  Optional<WordPackEntity> findByIdAndRoomId(UUID id, UUID roomId);

  List<WordPackEntity> findByRoomIdOrderByCreatedAtAscIdAsc(UUID roomId);

  List<WordPackEntity> findByRoomIdAndIdIn(UUID roomId, Collection<UUID> ids);

  long countByRoomId(UUID roomId);
}
