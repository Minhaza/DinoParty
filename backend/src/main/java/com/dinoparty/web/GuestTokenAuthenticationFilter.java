package com.dinoparty.web;

import com.dinoparty.guest.GuestService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves {@code Authorization: Bearer <token>} to an {@code AuthenticatedGuest}. A missing or
 * invalid token simply leaves the request unauthenticated; the entry point answers 401. Created by
 * {@code SecurityConfig} (not a bean) so it only runs inside the security chain.
 */
final class GuestTokenAuthenticationFilter extends OncePerRequestFilter {

  private static final String PREFIX = "Bearer ";

  private final GuestService guestService;

  GuestTokenAuthenticationFilter(GuestService guestService) {
    this.guestService = guestService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith(PREFIX)) {
      guestService
          .authenticate(header.substring(PREFIX.length()).trim())
          .ifPresent(
              guest ->
                  SecurityContextHolder.getContext()
                      .setAuthentication(
                          UsernamePasswordAuthenticationToken.authenticated(
                              guest, null, List.of(new SimpleGrantedAuthority("ROLE_GUEST")))));
    }
    chain.doFilter(request, response);
  }
}
