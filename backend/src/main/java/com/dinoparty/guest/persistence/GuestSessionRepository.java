package com.dinoparty.guest.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuestSessionRepository extends JpaRepository<GuestSessionEntity, UUID> {

  Optional<GuestSessionEntity> findByTokenHash(String tokenHash);

  /** Rows of {@code [guestId, newest lastSeenAt]} for the given guests that have a session. */
  @Query(
      "select s.guestId, max(s.lastSeenAt) from GuestSessionEntity s"
          + " where s.guestId in :guestIds group by s.guestId")
  List<Object[]> lastSeen(@Param("guestIds") Collection<UUID> guestIds);
}
