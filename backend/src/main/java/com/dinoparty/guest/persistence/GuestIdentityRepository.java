package com.dinoparty.guest.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestIdentityRepository extends JpaRepository<GuestIdentityEntity, UUID> {}
