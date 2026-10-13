package com.dinoparty.realtime;

import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.RateLimitedException;
import com.dinoparty.common.RateLimiter;
import com.dinoparty.guest.AuthenticatedGuest;
import com.dinoparty.guest.GuestService;
import com.dinoparty.room.RoomCodes;
import com.dinoparty.room.RoomService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

/**
 * Server-side authentication and authorization of every inbound STOMP frame.
 *
 * <ul>
 *   <li>{@code CONNECT}: resolves the bearer token to the guest principal, then removes the header
 *       so the token never travels further or shows up in logs.
 *   <li>{@code SUBSCRIBE}: only the three contract destinations, and room destinations only for
 *       current members (checked inside the room mailbox so it cannot race a leave).
 *   <li>{@code SEND}: only {@code /app/rooms/{code}/command}; membership and roles are enforced by
 *       the command handler against the database, never against client data.
 *   <li>Anything else (ACK, transactions, frames before CONNECT) is refused.
 * </ul>
 */
@Component
class StompSecurityInterceptor implements ChannelInterceptor {

  private static final Logger log = LoggerFactory.getLogger(StompSecurityInterceptor.class);
  static final String CLIENT_ADDRESS = "clientAddress";
  private static final String BEARER = "Bearer ";
  private static final int MAX_TOKEN_LENGTH = 128;
  private static final String CODE = "([A-Z0-9]{6})";
  private static final Pattern PUBLIC_TOPIC = Pattern.compile("^/topic/rooms/" + CODE + "$");
  private static final Pattern PRIVATE_QUEUE = Pattern.compile("^/user/queue/rooms/" + CODE + "$");
  private static final Pattern COMMAND = Pattern.compile("^/app/rooms/" + CODE + "/command$");
  private static final String ERRORS_QUEUE = "/user/queue/errors";
  private static final java.time.Duration CLOSE_DELAY = java.time.Duration.ofMillis(250);

  private final GuestService guests;
  private final RoomService rooms;
  private final SubscriptionRegistry subscriptions;
  private final RateLimiter rateLimiter;
  private final RealtimeProperties properties;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final MessageChannel clientOutbound;
  private final WebSocketSessionRegistry sessions;
  private final ThreadPoolTaskScheduler scheduler;
  private final SessionReplacement replacement;

  StompSecurityInterceptor(
      GuestService guests,
      RoomService rooms,
      SubscriptionRegistry subscriptions,
      RateLimiter rateLimiter,
      RealtimeProperties properties,
      ObservationRegistry observations,
      MeterRegistry meters,
      @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutbound,
      WebSocketSessionRegistry sessions,
      @Qualifier("realtimeScheduler") ThreadPoolTaskScheduler scheduler,
      SessionReplacement replacement) {
    this.guests = guests;
    this.rooms = rooms;
    this.subscriptions = subscriptions;
    this.rateLimiter = rateLimiter;
    this.properties = properties;
    this.observations = observations;
    this.meters = meters;
    this.clientOutbound = clientOutbound;
    this.sessions = sessions;
    this.scheduler = scheduler;
    this.replacement = replacement;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    StompCommand command = accessor == null ? null : accessor.getCommand();
    if (command == null) {
      return message; // heartbeat
    }
    try {
      switch (command) {
        case CONNECT, STOMP -> authenticate(accessor);
        case SUBSCRIBE -> authorizeSubscribe(accessor);
        case SEND -> authorizeSend(accessor);
        case UNSUBSCRIBE -> {
          requireGuest(accessor);
          subscriptions.remove(accessor.getSessionId(), accessor.getSubscriptionId());
        }
        case DISCONNECT -> {}
        default -> throw new RealtimeException(ErrorCode.INVALID_ACTION, "Unsupported frame.");
      }
      return message;
    } catch (RealtimeException e) {
      reject(accessor, e);
      return null; // the frame goes no further
    }
  }

  /**
   * Answers a refused frame with a STOMP ERROR carrying only our safe message and code, then closes
   * the socket shortly afterwards so the client can read it. Sent through the outbound channel, the
   * supported route for server-initiated ERROR frames.
   */
  private void reject(StompHeaderAccessor request, RealtimeException e) {
    String sessionId = request.getSessionId();
    if (sessionId == null) {
      return;
    }
    StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
    error.setSessionId(sessionId);
    error.setMessage(e.getMessage());
    error.setNativeHeader("code", e.code().name());
    if (request.getReceipt() != null) {
      error.setReceiptId(request.getReceipt());
    }
    error.setLeaveMutable(true);
    clientOutbound.send(MessageBuilder.createMessage(new byte[0], error.getMessageHeaders()));
    scheduler.schedule(
        () -> sessions.close(sessionId, CloseStatus.POLICY_VIOLATION.withReason(e.code().name())),
        scheduler.getClock().instant().plus(CLOSE_DELAY));
  }

  private void authenticate(StompHeaderAccessor accessor) {
    Observation observation =
        Observation.createNotStarted("dinoparty.ws.connect", observations).start();
    try {
      String address = clientAddress(accessor);
      Optional<AuthenticatedGuest> guest = resolve(accessor.getFirstNativeHeader("Authorization"));
      // The credential must not outlive this frame, whatever the outcome.
      accessor.removeNativeHeader("Authorization");
      if (guest.isEmpty()) {
        meters.counter("dinoparty.ws.connections", "result", "rejected").increment();
        try {
          rateLimiter.check("ws-auth-fail:" + address, properties.authFailureLimitPerMinute(), 60);
        } catch (RateLimitedException e) {
          throw new RealtimeException(ErrorCode.RATE_LIMITED, "Too many failed attempts.");
        }
        log.info("STOMP CONNECT rejected: missing or invalid credentials");
        throw new RealtimeException(ErrorCode.UNAUTHENTICATED, "Authentication failed");
      }
      replacement.announce(guest.get().guestId());
      accessor.setUser(new GuestPrincipal(guest.get(), accessor.getSessionId()));
      meters.counter("dinoparty.ws.connections", "result", "accepted").increment();
    } catch (RuntimeException e) {
      observation.error(e);
      throw e;
    } finally {
      observation.stop();
    }
  }

  private Optional<AuthenticatedGuest> resolve(String header) {
    if (header == null || !header.startsWith(BEARER)) {
      return Optional.empty();
    }
    String token = header.substring(BEARER.length()).trim();
    if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
      return Optional.empty();
    }
    return guests.authenticate(token);
  }

  private void authorizeSubscribe(StompHeaderAccessor accessor) {
    GuestPrincipal user = requireGuest(accessor);
    String destination = accessor.getDestination();
    String sessionId = accessor.getSessionId();
    String subscriptionId = accessor.getSubscriptionId();
    if (destination == null || sessionId == null || subscriptionId == null) {
      throw new RealtimeException(ErrorCode.FORBIDDEN, "Subscription refused.");
    }
    if (subscriptions.count(sessionId) >= properties.maxSubscriptionsPerSession()) {
      throw new RealtimeException(ErrorCode.FORBIDDEN, "Too many subscriptions.");
    }
    if (ERRORS_QUEUE.equals(destination)) {
      subscriptions.authorize(
          sessionId,
          subscriptionId,
          new SubscriptionRegistry.Subscription(user.guestId(), null, destination));
      return;
    }
    String code = match(PUBLIC_TOPIC, destination);
    if (code == null) {
      code = match(PRIVATE_QUEUE, destination);
    }
    if (code == null) {
      throw new RealtimeException(ErrorCode.FORBIDDEN, "Subscription refused.");
    }
    String roomCode = code;
    // Inside the mailbox so the check and the registration cannot interleave with a leave.
    Boolean allowed =
        rooms.inRoom(
            roomCode,
            () -> {
              if (!rooms.isMember(user.guestId(), roomCode)) {
                return false;
              }
              subscriptions.authorize(
                  sessionId,
                  subscriptionId,
                  new SubscriptionRegistry.Subscription(user.guestId(), roomCode, destination));
              return true;
            });
    if (!allowed) {
      throw new RealtimeException(ErrorCode.NOT_IN_ROOM, "Subscription refused.");
    }
  }

  private void authorizeSend(StompHeaderAccessor accessor) {
    requireGuest(accessor);
    String destination = accessor.getDestination();
    if (destination == null || match(COMMAND, destination) == null) {
      throw new RealtimeException(ErrorCode.FORBIDDEN, "Destination refused.");
    }
  }

  private static GuestPrincipal requireGuest(StompHeaderAccessor accessor) {
    if (accessor.getUser() instanceof GuestPrincipal guest) {
      return guest;
    }
    throw new RealtimeException(ErrorCode.UNAUTHENTICATED, "Authentication failed");
  }

  private static String match(Pattern pattern, String destination) {
    Matcher matcher = pattern.matcher(destination);
    if (!matcher.matches()) {
      return null;
    }
    // Only canonical (uppercase) codes: the codes are used verbatim as stream keys.
    return RoomCodes.normalize(matcher.group(1)).filter(matcher.group(1)::equals).orElse(null);
  }

  private static String clientAddress(StompHeaderAccessor accessor) {
    Map<String, Object> attributes = accessor.getSessionAttributes();
    Object address = attributes == null ? null : attributes.get(CLIENT_ADDRESS);
    return address == null ? "unknown" : address.toString();
  }
}
