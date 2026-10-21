package com.dinoparty.realtime;

import com.dinoparty.common.RateLimitedException;
import com.dinoparty.common.RateLimiter;
import java.net.InetSocketAddress;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Throttles WebSocket upgrades per client address and remembers that address for the STOMP layer
 * (which only sees frames). Forwarded headers are not read here, as for REST: behind a trusted
 * proxy Tomcat's RemoteIpValve has already rewritten the remote address (D49). The Origin allowlist
 * is enforced by the endpoint registration, before this runs.
 */
@Component
class HandshakeGuard implements HandshakeInterceptor {

  private final RateLimiter rateLimiter;
  private final RealtimeProperties properties;

  HandshakeGuard(RateLimiter rateLimiter, RealtimeProperties properties) {
    this.rateLimiter = rateLimiter;
    this.properties = properties;
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes) {
    InetSocketAddress remote = request.getRemoteAddress();
    String address = remote == null ? "unknown" : remote.getAddress().getHostAddress();
    try {
      rateLimiter.check("ws-connect:" + address, properties.connectLimitPerMinute(), 60);
    } catch (RateLimitedException e) {
      response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
      response.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()));
      return false;
    }
    attributes.put(StompSecurityInterceptor.CLIENT_ADDRESS, address);
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception) {}
}
