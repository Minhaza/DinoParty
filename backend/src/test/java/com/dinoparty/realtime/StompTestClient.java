package com.dinoparty.realtime;

import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal raw STOMP 1.2 client over {@code java.net.http.WebSocket}. Raw on purpose: tests need to
 * send malformed frames, forged headers and oversized messages, and to see exactly what the server
 * puts on the wire (ERROR frames, close codes).
 */
final class StompTestClient implements AutoCloseable {

  record Frame(String command, Map<String, String> headers, String body) {
    String header(String name) {
      return headers.get(name);
    }
  }

  /** A MESSAGE frame whose body is a contract envelope. */
  record Message(String destination, JsonNode envelope) {
    String type() {
      return envelope.get("type").asString();
    }

    long seq() {
      return envelope.get("seq").asLong();
    }

    JsonNode payload() {
      return envelope.get("payload");
    }
  }

  /** The HTTP upgrade itself was refused (bad origin, throttling). */
  static final class HandshakeRejected extends RuntimeException {
    final int status;
    final String retryAfter;

    HandshakeRejected(int status, String retryAfter) {
      super("Handshake rejected with HTTP " + status);
      this.status = status;
      this.retryAfter = retryAfter;
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private final List<Frame> frames = new CopyOnWriteArrayList<>();
  private final List<Message> messages = new CopyOnWriteArrayList<>();
  private final CompletableFuture<int[]> closed = new CompletableFuture<>();
  private final StringBuilder partial = new StringBuilder();
  private volatile String closeReason;
  private WebSocket socket;

  /** Opens the WebSocket only (no STOMP frame yet). */
  static StompTestClient open(int port, String origin) {
    StompTestClient client = new StompTestClient();
    WebSocket.Builder builder = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5));
    if (origin != null) {
      builder.header("Origin", origin);
    }
    try {
      client.socket =
          builder
              .buildAsync(URI.create("ws://localhost:" + port + "/ws"), client.new Listener())
              .orTimeout(5, TimeUnit.SECONDS)
              .join();
    } catch (java.util.concurrent.CompletionException e) {
      if (e.getCause() instanceof java.net.http.WebSocketHandshakeException rejected) {
        HttpResponse<?> response = rejected.getResponse();
        throw new HandshakeRejected(
            response.statusCode(), response.headers().firstValue("Retry-After").orElse(null));
      }
      throw e;
    }
    return client;
  }

  /** Opens the socket and performs STOMP CONNECT with a bearer token. */
  static StompTestClient connect(int port, String token) {
    StompTestClient client = open(port, null);
    client.sendFrame("CONNECT", connectHeaders(token), null);
    client.awaitFrame(f -> f.command().equals("CONNECTED"));
    return client;
  }

  static Map<String, String> connectHeaders(String token) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("accept-version", "1.2");
    headers.put("heart-beat", "0,0");
    if (token != null) {
      headers.put("Authorization", "Bearer " + token);
    }
    return headers;
  }

  void sendFrame(String command, Map<String, String> headers, String body) {
    StringBuilder sb = new StringBuilder(command).append('\n');
    headers.forEach((k, v) -> sb.append(k).append(':').append(v).append('\n'));
    sb.append('\n');
    if (body != null) {
      sb.append(body);
    }
    sb.append('\0');
    socket.sendText(sb.toString(), true).orTimeout(5, TimeUnit.SECONDS).join();
  }

  void subscribe(String id, String destination) {
    sendFrame("SUBSCRIBE", Map.of("id", id, "destination", destination), null);
  }

  /** Subscribes to the three streams of a room and waits until they are active. */
  void subscribeRoom(String code) {
    subscribe("pub-" + code, "/topic/rooms/" + code);
    subscribe("prv-" + code, "/user/queue/rooms/" + code);
    subscribe("err-" + code, "/user/queue/errors");
  }

  void command(String code, String json) {
    sendFrame(
        "SEND",
        Map.of(
            "destination", "/app/rooms/" + code + "/command", "content-type", "application/json"),
        json);
  }

  void command(String code, String type, String actionId, String payload) {
    String id = actionId == null ? "" : ",\"actionId\":\"%s\"".formatted(actionId);
    command(code, "{\"type\":\"%s\"%s,\"payload\":%s}".formatted(type, id, payload));
  }

  Frame awaitFrame(Predicate<Frame> predicate) {
    return await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> frames.stream().filter(predicate).findFirst().orElse(null), f -> f != null);
  }

  Message awaitMessage(Predicate<Message> predicate) {
    return await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> messages.stream().filter(predicate).findFirst().orElse(null), m -> m != null);
  }

  Message awaitType(String type) {
    return awaitMessage(m -> m.type().equals(type));
  }

  List<Message> messages() {
    return new ArrayList<>(messages);
  }

  List<Message> ofType(String type) {
    return messages.stream().filter(m -> m.type().equals(type)).toList();
  }

  List<Message> onStream(String stream, String code) {
    return messages.stream()
        .filter(m -> m.envelope().get("stream").asString().equals(stream))
        .filter(m -> code.equals(m.envelope().get("roomCode").asString()))
        .toList();
  }

  List<Frame> frames() {
    return new ArrayList<>(frames);
  }

  /** Waits for the server (or us) to close the WebSocket; returns the close code. */
  int awaitClose() {
    try {
      return closed.get(5, TimeUnit.SECONDS)[0];
    } catch (Exception e) {
      throw new AssertionError("WebSocket was not closed by the server", e);
    }
  }

  boolean isClosed() {
    return closed.isDone();
  }

  String closeReason() {
    return closeReason;
  }

  /** Abrupt end of the connection without a STOMP DISCONNECT, like a browser tab dying. */
  void drop() {
    socket.abort();
  }

  @Override
  public void close() {
    if (!closed.isDone()) {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    }
  }

  private void accept(String text) {
    for (String raw : text.split("\0", -1)) {
      String chunk = raw.stripLeading();
      if (chunk.isEmpty()) {
        continue; // heartbeat newline or trailing split
      }
      int headerEnd = chunk.indexOf("\n\n");
      String head = headerEnd < 0 ? chunk : chunk.substring(0, headerEnd);
      String body = headerEnd < 0 ? "" : chunk.substring(headerEnd + 2);
      String[] lines = head.split("\n");
      Map<String, String> headers = new LinkedHashMap<>();
      for (int i = 1; i < lines.length; i++) {
        int colon = lines[i].indexOf(':');
        if (colon > 0) {
          headers.putIfAbsent(lines[i].substring(0, colon), lines[i].substring(colon + 1));
        }
      }
      Frame frame = new Frame(lines[0], headers, body);
      if (frame.command().equals("MESSAGE") && body.startsWith("{")) {
        messages.add(new Message(headers.get("destination"), JSON.readTree(body)));
      }
      frames.add(frame);
    }
  }

  private final class Listener implements WebSocket.Listener {
    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        String text = partial.toString();
        partial.setLength(0);
        accept(text);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      closeReason = reason;
      closed.complete(new int[] {statusCode});
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      closed.complete(new int[] {-1});
    }
  }
}
