package com.dinoparty.realtime;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * Last line of defence on the way to the client: a room's public stream is only delivered to
 * subscriptions that are still authorized. This is what stops a member who left or timed out from
 * hearing the room, even though their socket keeps its old subscription.
 *
 * <p>Messages on the outbound channel still carry simp headers (they become STOMP frames later), so
 * they are inspected through {@link SimpMessageHeaderAccessor}'s static getters.
 */
@Component
class OutboundSubscriptionGuard implements ChannelInterceptor {

  private static final String ROOM_TOPIC_PREFIX = "/topic/rooms/";

  private final SubscriptionRegistry subscriptions;

  OutboundSubscriptionGuard(SubscriptionRegistry subscriptions) {
    this.subscriptions = subscriptions;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    MessageHeaders headers = message.getHeaders();
    if (SimpMessageHeaderAccessor.getMessageType(headers) != SimpMessageType.MESSAGE) {
      return message;
    }
    String destination = SimpMessageHeaderAccessor.getDestination(headers);
    if (destination == null || !destination.startsWith(ROOM_TOPIC_PREFIX)) {
      return message;
    }
    String sessionId = SimpMessageHeaderAccessor.getSessionId(headers);
    String subscriptionId = SimpMessageHeaderAccessor.getSubscriptionId(headers);
    boolean allowed =
        sessionId != null
            && subscriptionId != null
            && subscriptions.isAuthorized(sessionId, subscriptionId);
    return allowed ? message : null;
  }
}
