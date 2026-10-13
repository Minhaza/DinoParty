package com.dinoparty.realtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * Remembers the open WebSocket sessions so the server can close one on purpose (replaced tab,
 * rejected frame, revoked token). Spring's id of a WebSocket session is the STOMP session id.
 */
@Component
class WebSocketSessionRegistry {

  private static final Logger log = LoggerFactory.getLogger(WebSocketSessionRegistry.class);
  private static final String STARTED = "stompStarted";
  private static final Pattern STOMP_START = Pattern.compile("^(CONNECT|STOMP)\\r?\\n");

  private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

  WebSocketHandler decorate(WebSocketHandler handler) {
    return new WebSocketHandlerDecorator(handler) {
      @Override
      public void handleMessage(WebSocketSession session, WebSocketMessage<?> message)
          throws Exception {
        if (!Boolean.TRUE.equals(session.getAttributes().get(STARTED))) {
          String head = head(message);
          if (head.isEmpty()) {
            super.handleMessage(session, message); // heartbeat only
            return;
          }
          if (!STOMP_START.matcher(head).find()) {
            refuseBeforeConnect(session);
            return;
          }
          session.getAttributes().put(STARTED, Boolean.TRUE);
        }
        super.handleMessage(session, message);
      }

      @Override
      public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        super.afterConnectionEstablished(session);
      }

      @Override
      public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus)
          throws Exception {
        sessions.remove(session.getId());
        super.afterConnectionClosed(session, closeStatus);
      }
    };
  }

  /** The first real frame of a socket must be CONNECT/STOMP; anything else never reaches STOMP. */
  private static String head(WebSocketMessage<?> message) {
    Object payload = message.getPayload();
    String text;
    if (payload instanceof byte[] bytes) {
      text = new String(bytes, 0, Math.min(bytes.length, 16), StandardCharsets.UTF_8);
    } else if (payload instanceof ByteBuffer buffer) {
      ByteBuffer copy = buffer.duplicate();
      copy.limit(Math.min(copy.limit(), copy.position() + 16));
      text = StandardCharsets.UTF_8.decode(copy).toString();
    } else {
      String all = String.valueOf(payload);
      text = all.substring(0, Math.min(all.length(), 16));
    }
    return text.stripLeading();
  }

  private static void refuseBeforeConnect(WebSocketSession session) throws IOException {
    session.sendMessage(
        new TextMessage("ERROR\ncode:UNAUTHENTICATED\nmessage:Authentication failed\n\n\0"));
    session.close(CloseStatus.POLICY_VIOLATION.withReason("UNAUTHENTICATED"));
  }

  void close(String wsSessionId, CloseStatus status) {
    WebSocketSession session = sessions.get(wsSessionId);
    if (session == null) {
      return;
    }
    try {
      session.close(status);
    } catch (IOException | RuntimeException e) {
      log.debug("Closing WebSocket session {} failed: {}", wsSessionId, e.toString());
    }
  }

  int openSessions() {
    return sessions.size();
  }
}
