package com.dinoparty.realtime;

import java.time.Duration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * Fallback for client frames that fail somewhere other than {@link StompSecurityInterceptor} (which
 * answers its own rejections): the {@code ERROR} carries only a safe message and code, never the
 * exception text, which could echo client input or internals.
 */
class SafeStompErrorHandler extends StompSubProtocolErrorHandler {

  private static final Duration CLOSE_DELAY = Duration.ofMillis(250);

  private final WebSocketSessionRegistry sessions;
  private final TaskScheduler scheduler;

  SafeStompErrorHandler(WebSocketSessionRegistry sessions, TaskScheduler scheduler) {
    this.sessions = sessions;
    this.scheduler = scheduler;
  }

  @Override
  public Message<byte[]> handleClientMessageProcessingError(
      Message<byte[]> clientMessage, Throwable ex) {
    Throwable cause = ex;
    while (cause != null && !(cause instanceof RealtimeException)) {
      cause = cause.getCause();
    }
    String code = cause instanceof RealtimeException r ? r.code().name() : "INVALID_ACTION";
    String text = cause instanceof RealtimeException r ? r.getMessage() : "Invalid frame";

    StompHeaderAccessor request =
        clientMessage == null
            ? null
            : MessageHeaderAccessor.getAccessor(clientMessage, StompHeaderAccessor.class);
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
    accessor.setMessage(text);
    accessor.setNativeHeader("code", code);
    if (request != null && request.getReceipt() != null) {
      accessor.setReceiptId(request.getReceipt());
    }
    accessor.setLeaveMutable(true);
    if (request != null && request.getSessionId() != null) {
      String sessionId = request.getSessionId();
      scheduler.schedule(
          () -> sessions.close(sessionId, CloseStatus.POLICY_VIOLATION.withReason(code)),
          scheduler.getClock().instant().plus(CLOSE_DELAY));
    }
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
