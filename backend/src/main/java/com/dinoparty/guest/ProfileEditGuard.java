package com.dinoparty.guest;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Port to the room module, which decides when a guest's profile is frozen (api-contracts §2: while
 * their room is {@code STARTING} or {@code PLAYING}). The guest module knows nothing of rooms.
 */
public interface ProfileEditGuard {

  /**
   * Runs {@code change} if the guest's profile may change now, serialized with the status changes
   * of their room so a game cannot start in between; otherwise throws {@code INVALID_PHASE}.
   */
  <T> T whileEditable(UUID guestId, Supplier<T> change);
}
