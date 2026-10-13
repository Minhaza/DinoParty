package com.dinoparty.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Caller address for throttling. Forwarded headers are deliberately ignored: they are
 * client-controlled unless a trusted proxy is configured (deployment phase).
 */
final class ClientAddress {

  private ClientAddress() {}

  static String of(HttpServletRequest request) {
    return request.getRemoteAddr();
  }
}
