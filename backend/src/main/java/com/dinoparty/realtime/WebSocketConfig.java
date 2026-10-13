package com.dinoparty.realtime;

import com.dinoparty.common.CorsProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over a native WebSocket at {@code /ws} with the in-memory simple broker (single instance,
 * docs/architecture.md §1). Heartbeats are 10 s each way; a client that stops answering is
 * disconnected by the broker and enters the presence grace period.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private static final long HEARTBEAT_MS = 10_000;

  private final RealtimeProperties properties;
  private final CorsProperties cors;
  private final HandshakeGuard handshakeGuard;
  private final StompSecurityInterceptor securityInterceptor;
  private final OutboundSubscriptionGuard outboundGuard;
  private final WebSocketSessionRegistry sessionRegistry;
  private final ThreadPoolTaskScheduler scheduler;

  WebSocketConfig(
      RealtimeProperties properties,
      CorsProperties cors,
      HandshakeGuard handshakeGuard,
      StompSecurityInterceptor securityInterceptor,
      OutboundSubscriptionGuard outboundGuard,
      WebSocketSessionRegistry sessionRegistry,
      @Qualifier("realtimeScheduler") ThreadPoolTaskScheduler scheduler) {
    this.properties = properties;
    this.cors = cors;
    this.handshakeGuard = handshakeGuard;
    this.securityInterceptor = securityInterceptor;
    this.outboundGuard = outboundGuard;
    this.sessionRegistry = sessionRegistry;
    this.scheduler = scheduler;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.setErrorHandler(new SafeStompErrorHandler(sessionRegistry, scheduler));
    // Frames of one connection are processed in the order they were sent (SUBSCRIBE before SEND,
    // SET_READY true before SET_READY false).
    registry.setPreserveReceiveOrder(true);
    registry
        .addEndpoint("/ws")
        // Never a wildcard: browsers must come from the configured frontend origins.
        .setAllowedOrigins(cors.allowedOrigins().toArray(String[]::new))
        .addInterceptors(handshakeGuard);
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry
        .enableSimpleBroker("/topic", "/queue")
        .setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
        .setTaskScheduler(scheduler);
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
    // Keep events in publication order: seq numbers are only meaningful if delivery follows them.
    registry.setPreservePublishOrder(true);
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(securityInterceptor);
  }

  @Override
  public void configureClientOutboundChannel(ChannelRegistration registration) {
    registration.interceptors(outboundGuard);
  }

  @Override
  public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
    registration
        .setMessageSizeLimit(properties.maxMessageBytes())
        .setSendBufferSizeLimit(512 * 1024)
        .setSendTimeLimit(10_000)
        // A socket that never sends CONNECT must not hold resources.
        .setTimeToFirstMessage(10_000)
        .addDecoratorFactory(sessionRegistry::decorate);
  }
}
