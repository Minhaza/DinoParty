package com.dinoparty.room.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<RoomEntity, UUID> {

  Optional<RoomEntity> findByCode(String code);

  /** Row lock for membership changes; belt and braces behind the in-process room mailbox. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from RoomEntity r where r.code = :code")
  Optional<RoomEntity> findByCodeForUpdate(@Param("code") String code);

  boolean existsByCode(String code);

  /** Codes of rooms in {@code status} created before {@code cutoff} (idle-room sweep). */
  @Query("select r.code from RoomEntity r where r.status = :status and r.createdAt < :cutoff")
  List<String> findCodesByStatusCreatedBefore(
      @Param("status") com.dinoparty.room.RoomStatus status, @Param("cutoff") Instant cutoff);

  /** Restart recovery: rooms left in a game status by a dead process go back to waiting. */
  @Modifying
  @Query("update RoomEntity r set r.status = 'WAITING' where r.status in :statuses")
  int resetStatuses(@Param("statuses") Collection<com.dinoparty.room.RoomStatus> statuses);
}
