package com.dinoparty.guest.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestSessionRepository extends JpaRepository<GuestSessionEntity, UUID> {

  Optional<GuestSessionEntity> findByTokenHash(String tokenHash);
}
