package com.dinoparty.realtime;

import java.security.Principal;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

/**
 * Single STOMP entry point for client commands. The frame is handed over unparsed so validation
 * errors are answered in the contract's error format instead of a framework exception.
 */
@Controller
class CommandController {

  private final CommandRouter router;

  CommandController(CommandRouter router) {
    this.router = router;
  }

  @MessageMapping("/rooms/{code}/command")
  void command(
      @DestinationVariable("code") String code, Message<byte[]> message, Principal principal) {
    // The inbound interceptor guarantees a GuestPrincipal; anything else is dropped defensively.
    if (principal instanceof GuestPrincipal guest) {
      router.handle(guest.guestId(), code, message.getPayload());
    }
  }
}
