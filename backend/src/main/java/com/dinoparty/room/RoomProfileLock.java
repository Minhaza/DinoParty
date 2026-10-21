package com.dinoparty.room;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.guest.ProfileEditGuard;
import com.dinoparty.room.persistence.RoomEntity;
import com.dinoparty.room.persistence.RoomParticipantRepository;
import com.dinoparty.room.persistence.RoomRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Freezes a member's profile while their room is starting or playing: the roster takes nicknames
 * and avatars at the end of the countdown. The check and the change run inside the room mailbox,
 * where every status change happens, so a start can never interleave with a rename.
 */
@Component
class RoomProfileLock implements ProfileEditGuard {

  private static final Set<RoomStatus> LOCKED = Set.of(RoomStatus.STARTING, RoomStatus.PLAYING);

  /** Re-reads after the guest moved to another room meanwhile; more moves than this are absurd. */
  private static final int ATTEMPTS = 3;

  private record Outcome<T>(boolean moved, T value) {}

  private final RoomParticipantRepository participants;
  private final RoomRepository rooms;
  private final RoomMailbox mailbox;

  RoomProfileLock(
      RoomParticipantRepository participants, RoomRepository rooms, RoomMailbox mailbox) {
    this.participants = participants;
    this.rooms = rooms;
    this.mailbox = mailbox;
  }

  @Override
  public <T> T whileEditable(UUID guestId, Supplier<T> change) {
    for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
      Optional<RoomEntity> room = activeRoom(guestId);
      if (room.isEmpty()) {
        return change.get();
      }
      UUID roomId = room.get().getId();
      Outcome<T> outcome =
          mailbox.run(
              room.get().getCode(),
              () -> {
                Optional<RoomEntity> current = activeRoom(guestId);
                if (current.isPresent() && !current.get().getId().equals(roomId)) {
                  return new Outcome<T>(true, null);
                }
                if (current.isPresent() && LOCKED.contains(current.get().getStatus())) {
                  throw new DomainException(
                      ErrorCode.INVALID_PHASE,
                      "You can't change your profile while a game is running.");
                }
                return new Outcome<>(false, change.get());
              });
      if (!outcome.moved()) {
        return outcome.value();
      }
    }
    throw new DomainException(ErrorCode.INVALID_PHASE, "Your room changed; try again.");
  }

  private Optional<RoomEntity> activeRoom(UUID guestId) {
    return participants
        .findByGuestIdAndLeftAtIsNull(guestId)
        .flatMap(p -> rooms.findById(p.getRoomId()));
  }
}
