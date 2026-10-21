package com.dinoparty.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Caller address for throttling. Forwarded headers are never read here: they are client-controlled.
 * Behind Render's proxy (D49) {@code server.forward-headers-strategy=native} makes Tomcat's
 * RemoteIpValve rewrite the remote address from X-Forwarded-For, trusting private-range proxies
 * only.
 */
final class ClientAddress {

  private ClientAddress() {}

  static String of(HttpServletRequest request) {
    return request.getRemoteAddr();
  }
}
