package com.dinoparty.web;

import com.dinoparty.guest.AuthenticatedGuest;
import com.dinoparty.guest.GuestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/guests")
class GuestController {

  private final GuestService guestService;

  GuestController(GuestService guestService) {
    this.guestService = guestService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  CreateGuestResponse create(
      @Valid @RequestBody CreateGuestRequest request, HttpServletRequest http) {
    return CreateGuestResponse.from(
        guestService.register(
            request.nickname(),
            request.avatarKey(),
            request.avatarColor(),
            ClientAddress.of(http)));
  }

  @GetMapping("/me")
  GuestDto me(@AuthenticationPrincipal AuthenticatedGuest caller) {
    guestService.renewSession(caller.sessionId());
    return GuestDto.from(guestService.get(caller.guestId()));
  }

  @PatchMapping("/me")
  GuestDto update(
      @AuthenticationPrincipal AuthenticatedGuest caller,
      @Valid @RequestBody UpdateGuestRequest request) {
    return GuestDto.from(
        guestService.updateProfile(
            caller.guestId(), request.nickname(), request.avatarKey(), request.avatarColor()));
  }
}
