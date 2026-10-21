package com.dinoparty.web;

import com.dinoparty.common.CorsProperties;
import com.dinoparty.guest.GuestService;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Deny-by-default: only health and guest creation are public; everything else needs a guest bearer
 * token resolved by {@link GuestTokenAuthenticationFilter}. CSRF is disabled on purpose: the API is
 * stateless, uses Bearer tokens and sets no cookies. With the single-origin deploy (D49), GETs
 * outside {@code /api}, {@code /actuator} and {@code /ws} are the public frontend files.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, ProblemJsonWriter problems, GuestService guestService, SpaProperties spa)
      throws Exception {
    // Parsed like MVC parses paths, so an odd spelling cannot reach a controller as a "file".
    PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
    RequestMatcher backend =
        new OrRequestMatcher(
            paths.matcher("/api/**"), paths.matcher("/actuator/**"), paths.matcher("/ws/**"));
    RequestMatcher frontendFile =
        request ->
            spa.enabled()
                && HttpMethod.GET.matches(request.getMethod())
                && !backend.matches(request);
    return http.csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .addFilterBefore(
            new GuestTokenAuthenticationFilter(guestService), AuthorizationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.GET, "/api/v1/health")
                    .permitAll()
                    // The WebSocket upgrade cannot carry an Authorization header; the STOMP
                    // CONNECT frame is authenticated by the realtime module instead.
                    .requestMatchers(HttpMethod.GET, "/ws")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/guests")
                    .permitAll()
                    // Game discovery is public so the landing page works before a guest exists.
                    .requestMatchers(HttpMethod.GET, "/api/v1/games", "/api/v1/games/*")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/health/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers(frontendFile)
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                        (request, response, e) ->
                            problems.write(
                                response,
                                ProblemFactory.create(
                                    HttpStatus.UNAUTHORIZED,
                                    "UNAUTHENTICATED",
                                    "Authentication required",
                                    "A valid guest token is required.")))
                    .accessDeniedHandler(
                        (request, response, e) ->
                            problems.write(
                                response,
                                ProblemFactory.create(
                                    HttpStatus.FORBIDDEN,
                                    "FORBIDDEN",
                                    "Forbidden",
                                    "You are not allowed to perform this action."))))
        .build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(properties.allowedOrigins());
    config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
    config.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
  }
}
