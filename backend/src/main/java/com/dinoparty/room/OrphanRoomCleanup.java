package com.dinoparty.room;

import com.dinoparty.guest.GuestService;
import com.dinoparty.room.persistence.RoomEntity;
import com.dinoparty.room.persistence.RoomParticipantEntity;
import com.dinoparty.room.persistence.RoomParticipantRepository;
import com.dinoparty.room.persistence.RoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes ghost rooms (game-engine.md §9.3). Presence is only in memory, so after a restart members
 * who never come back are never timed out and keep their room, and themselves, stuck. A {@code
 * WAITING} room is closed, with every member removed, once for {@link RoomProperties#roomOrphanTtl}
 * nothing showed life: it was not created, joined or seen (a member's newest {@code last_seen_at})
 * within that time, and no member has a live connection or is inside a disconnect grace period.
 * Rooms with a game ({@code STARTING}, {@code PLAYING}, {@code RESULTS}) are never touched; {@code
 * RESULTS} times out to {@code WAITING} by itself.
 *
 * <p>Each room is decided again inside its mailbox under a row lock, so a concurrent join or
 * reconnect wins, and a room already closed is skipped: running the sweep twice is harmless.
 */
@Component
public class OrphanRoomCleanup {

  private static final Logger log = LoggerFactory.getLogger(OrphanRoomCleanup.class);

  private final RoomRepository rooms;
  private final RoomParticipantRepository participants;
  private final RoomMailbox mailbox;
  private final GuestService guests;
  private final ObjectProvider<MemberPresence> presence;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final RoomProperties properties;
  private final TransactionTemplate tx;

  OrphanRoomCleanup(
      RoomRepository rooms,
      RoomParticipantRepository participants,
      RoomMailbox mailbox,
      GuestService guests,
      ObjectProvider<MemberPresence> presence,
      ApplicationEventPublisher events,
      Clock clock,
      RoomProperties properties,
      PlatformTransactionManager transactionManager) {
    this.rooms = rooms;
    this.participants = participants;
    this.mailbox = mailbox;
    this.guests = guests;
    this.presence = presence;
    this.events = events;
    this.clock = clock;
    this.properties = properties;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /** One sweep; returns the number of rooms closed. */
  public int closeIdleRooms() {
    Instant now = clock.instant();
    Instant cutoff = now.minus(properties.roomOrphanTtl());
    int closed = 0;
    for (String code : rooms.findCodesByStatusCreatedBefore(RoomStatus.WAITING, cutoff)) {
      try (MDC.MDCCloseable ignored = MDC.putCloseable("roomCode", code)) {
        Boolean done =
            mailbox.run(code, () -> tx.execute(status -> closeIfIdle(code, cutoff, now)));
        if (Boolean.TRUE.equals(done)) {
          closed++;
          log.info("Closed idle room after {}", properties.roomOrphanTtl());
        }
      } catch (RuntimeException e) {
        log.warn("Idle-room check failed", e);
      }
    }
    return closed;
  }

  private boolean closeIfIdle(String code, Instant cutoff, Instant now) {
    RoomEntity room = rooms.findByCodeForUpdate(code).orElse(null);
    if (room == null
        || room.getStatus() != RoomStatus.WAITING
        || room.getCreatedAt().isAfter(cutoff)) {
      return false;
    }
    List<RoomParticipantEntity> members =
        participants.findByRoomIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(room.getId());
    if (members.stream().anyMatch(p -> p.getJoinedAt().isAfter(cutoff))) {
      return false;
    }
    MemberPresence view = presence.getIfAvailable();
    if (view != null && members.stream().anyMatch(p -> view.isLive(code, p.getGuestId()))) {
      return false;
    }
    List<UUID> ids = members.stream().map(RoomParticipantEntity::getGuestId).toList();
    if (guests.lastSeen(ids).values().stream().anyMatch(seen -> seen.isAfter(cutoff))) {
      return false;
    }
    members.forEach(p -> p.leave(now));
    room.close(now);
    participants.flush();
    events.publishEvent(new RoomEvent.Closed(room.getId(), code, "IDLE"));
    return true;
  }
}
