package com.dinoparty.room.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomParticipantRepository extends JpaRepository<RoomParticipantEntity, UUID> {

  List<RoomParticipantEntity> findByRoomIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(UUID roomId);

  long countByRoomIdAndLeftAtIsNull(UUID roomId);

  Optional<RoomParticipantEntity> findByRoomIdAndGuestId(UUID roomId, UUID guestId);

  boolean existsByGuestIdAndLeftAtIsNull(UUID guestId);
}
