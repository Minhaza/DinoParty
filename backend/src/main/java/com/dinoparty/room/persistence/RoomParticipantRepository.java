package com.dinoparty.room.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomParticipantRepository extends JpaRepository<RoomParticipantEntity, UUID> {

  List<RoomParticipantEntity> findByRoomIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(UUID roomId);

  long countByRoomIdAndLeftAtIsNull(UUID roomId);

  Optional<RoomParticipantEntity> findByRoomIdAndGuestId(UUID roomId, UUID guestId);

  boolean existsByGuestIdAndLeftAtIsNull(UUID guestId);

  Optional<RoomParticipantEntity> findByGuestIdAndLeftAtIsNull(UUID guestId);

  @Modifying
  @Query(
      "update RoomParticipantEntity p set p.ready = false where p.roomId in"
          + " (select r.id from RoomEntity r where r.status in :statuses)")
  int clearReadyInRooms(@Param("statuses") Collection<com.dinoparty.room.RoomStatus> statuses);
}
