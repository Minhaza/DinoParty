package com.dinoparty.guest;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.RandomSource;
import com.dinoparty.common.RateLimiter;
import com.dinoparty.guest.persistence.GuestIdentityEntity;
import com.dinoparty.guest.persistence.GuestIdentityRepository;
import com.dinoparty.guest.persistence.GuestSessionEntity;
import com.dinoparty.guest.persistence.GuestSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Guest identity and session lifecycle: create, authenticate by opaque token, edit profile. */
@Service
public class GuestService {

  private static final int TOKEN_BYTES = 32;
  // base64url of 32 bytes is 43 chars; anything longer is junk we don't need to hash.
  private static final int MAX_TOKEN_LENGTH = 128;
  private static final Pattern NICKNAME = Pattern.compile(GuestProfileRules.NICKNAME_REGEX);
  private static final Pattern COLOR = Pattern.compile(GuestProfileRules.COLOR_REGEX);

  private final GuestIdentityRepository guests;
  private final GuestSessionRepository sessions;
  private final RandomSource random;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final GuestProperties properties;
  private final ProfileEditGuard profileGuard;
  private final TransactionTemplate tx;

  GuestService(
      GuestIdentityRepository guests,
      GuestSessionRepository sessions,
      RandomSource random,
      RateLimiter rateLimiter,
      Clock clock,
      GuestProperties properties,
      ProfileEditGuard profileGuard,
      PlatformTransactionManager transactionManager) {
    this.guests = guests;
    this.sessions = sessions;
    this.random = random;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.properties = properties;
    this.profileGuard = profileGuard;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /**
   * Creates a guest and its first session. {@code clientKey} identifies the caller for throttling.
   */
  @Transactional
  public GuestRegistration register(
      String nickname, String avatarKey, String avatarColor, String clientKey) {
    rateLimiter.check("guest-create:" + clientKey, properties.createLimitPerMinute(), 60);
    String cleanNickname = validNickname(nickname);
    validAvatarKey(avatarKey);
    validColor(avatarColor);

    Instant now = clock.instant();
    GuestIdentityEntity guest =
        guests.save(
            new GuestIdentityEntity(UUID.randomUUID(), cleanNickname, avatarKey, avatarColor, now));
    String token =
        Base64.getUrlEncoder().withoutPadding().encodeToString(random.nextBytes(TOKEN_BYTES));
    Instant expiresAt = now.plus(properties.tokenTtl());
    sessions.save(
        new GuestSessionEntity(
            UUID.randomUUID(), guest.getId(), TokenHasher.hash(token), now, expiresAt));
    return new GuestRegistration(toGuest(guest), token, expiresAt);
  }

  /** Resolves a raw bearer token; empty for unknown, expired or revoked sessions. */
  @Transactional(readOnly = true)
  public Optional<AuthenticatedGuest> authenticate(String token) {
    if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
      return Optional.empty();
    }
    return sessions
        .findByTokenHash(TokenHasher.hash(token))
        .filter(s -> s.isActiveAt(clock.instant()))
        .map(s -> new AuthenticatedGuest(s.getGuestId(), s.getId()));
  }

  /** True while the session is neither expired nor revoked; used to drop stale WebSocket links. */
  @Transactional(readOnly = true)
  public boolean isSessionActive(UUID sessionId) {
    return sessions.findById(sessionId).filter(s -> s.isActiveAt(clock.instant())).isPresent();
  }

  /** Sliding expiry: called when the client restores its identity (GET /guests/me). */
  @Transactional
  public void renewSession(UUID sessionId) {
    Instant now = clock.instant();
    sessions
        .findById(sessionId)
        .filter(s -> s.isActiveAt(now))
        .ifPresent(s -> s.renew(now, now.plus(properties.tokenTtl())));
  }

  @Transactional(readOnly = true)
  public Guest get(UUID guestId) {
    return guests
        .findById(guestId)
        .map(GuestService::toGuest)
        .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHENTICATED, "Unknown guest."));
  }

  @Transactional(readOnly = true)
  public List<Guest> getAll(Collection<UUID> guestIds) {
    return guests.findAllById(guestIds).stream().map(GuestService::toGuest).toList();
  }

  /** The newest {@code last_seen_at} over each guest's sessions; guests without one are absent. */
  @Transactional(readOnly = true)
  public Map<UUID, Instant> lastSeen(Collection<UUID> guestIds) {
    Map<UUID, Instant> seen = new HashMap<>();
    if (!guestIds.isEmpty()) {
      for (Object[] row : sessions.lastSeen(guestIds)) {
        seen.put((UUID) row[0], (Instant) row[1]);
      }
    }
    return seen;
  }

  /**
   * Applies only the provided (non-null) fields. Refused with {@code INVALID_PHASE} while the
   * guest's room is starting or playing; the transaction commits before the room's mailbox is
   * released, so a start queued behind it sees the new profile.
   */
  public Guest updateProfile(UUID guestId, String nickname, String avatarKey, String avatarColor) {
    String cleanNickname = nickname == null ? null : validNickname(nickname);
    String cleanAvatarKey = avatarKey == null ? null : validAvatarKey(avatarKey);
    String cleanColor = avatarColor == null ? null : validColor(avatarColor);
    return profileGuard.whileEditable(
        guestId,
        () ->
            tx.execute(
                status -> {
                  GuestIdentityEntity guest =
                      guests
                          .findById(guestId)
                          .orElseThrow(
                              () ->
                                  new DomainException(ErrorCode.UNAUTHENTICATED, "Unknown guest."));
                  guest.updateProfile(
                      cleanNickname == null ? guest.getNickname() : cleanNickname,
                      cleanAvatarKey == null ? guest.getAvatarKey() : cleanAvatarKey,
                      cleanColor == null ? guest.getAvatarColor() : cleanColor,
                      clock.instant());
                  return toGuest(guest);
                }));
  }

  private static String validNickname(String nickname) {
    String trimmed = nickname == null ? "" : nickname.strip();
    if (trimmed.length() < GuestProfileRules.NICKNAME_MIN
        || trimmed.length() > GuestProfileRules.NICKNAME_MAX) {
      throw DomainException.invalid("nickname", "must be 2–20 characters");
    }
    if (!NICKNAME.matcher(trimmed).matches()) {
      throw DomainException.invalid(
          "nickname", "may only contain letters, digits, single spaces and _ . ' -");
    }
    return trimmed;
  }

  private static String validAvatarKey(String avatarKey) {
    if (avatarKey == null || !AvatarCatalog.KEYS.contains(avatarKey)) {
      throw DomainException.invalid("avatarKey", "is not a known avatar");
    }
    return avatarKey;
  }

  private static String validColor(String color) {
    if (color != null && !COLOR.matcher(color).matches()) {
      throw DomainException.invalid("avatarColor", "must look like #RRGGBB");
    }
    return color;
  }

  private static Guest toGuest(GuestIdentityEntity e) {
    return new Guest(
        e.getId(), e.getNickname(), e.getAvatarKey(), e.getAvatarColor(), e.getCreatedAt());
  }
}
