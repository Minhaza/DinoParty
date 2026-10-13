package com.dinoparty.room.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<RoomEntity, UUID> {

  Optional<RoomEntity> findByCode(String code);

  /** Row lock for membership changes; belt and braces behind the in-process room mailbox. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from RoomEntity r where r.code = :code")
  Optional<RoomEntity> findByCodeForUpdate(@Param("code") String code);

  boolean existsByCode(String code);
}
