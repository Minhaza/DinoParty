package com.dinoparty.realtime;

import com.dinoparty.common.DomainException;
import com.dinoparty.common.ErrorCode;
import com.dinoparty.common.RateLimitedException;
import com.dinoparty.common.RateLimiter;
import com.dinoparty.common.Timestamps;
import com.dinoparty.engine.CommandSpec;
import com.dinoparty.engine.GameRegistry;
import com.dinoparty.room.RoomCodes;
import com.dinoparty.room.RoomRef;
import com.dinoparty.room.RoomService;
import com.dinoparty.session.SessionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates and dispatches client commands. It owns only the transport concerns (size, rate limit,
 * duplicate detection, envelope validation, error mapping); what a command means is decided by the
 * room services it calls. It never throws: every failure becomes a {@code GAME_ERROR} (or an entry
 * on the errors queue) so one bad frame cannot disturb the connection.
 */
@Component
class CommandRouter {

  private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);
  private static final Pattern TYPE = Pattern.compile("[A-Z_]{1,40}");

  /**
   * Game commands of later phases; answered with INVALID_ACTION until a game module that declares
   * them is registered (registered ones take the game path, see {@link #handleGameCommand}).
   */
  private static final Set<String> RESERVED =
      Set.of(
          "CHOOSE_WORD",
          "DRAW_STROKE",
          "DRAW_UNDO",
          "DRAW_CLEAR",
          "SUBMIT_GUESS",
          "TELEPHONE_SUBMIT_TEXT",
          "TELEPHONE_SUBMIT_DRAWING",
          "REVEAL_NEXT",
          "REVEAL_PREV");

  /** Client-facing validation failure; the message is authored here and never echoes input. */
  private static final class InvalidCommand extends RuntimeException {
    InvalidCommand(String message) {
      super(message, null, false, false);
    }
  }

  private record Command(String type, String actionId, String phaseId, JsonNode payload) {}

  private final RoomService rooms;
  private final RoomEventPublisher publisher;
  private final SnapshotService snapshots;
  private final SessionService sessions;
  private final GameRegistry games;
  private final ActionDeduper deduper;
  private final RateLimiter rateLimiter;
  private final RealtimeProperties properties;
  private final JsonMapper json;
  private final Clock clock;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;

  CommandRouter(
      RoomService rooms,
      RoomEventPublisher publisher,
      SnapshotService snapshots,
      SessionService sessions,
      GameRegistry games,
      ActionDeduper deduper,
      RateLimiter rateLimiter,
      RealtimeProperties properties,
      JsonMapper json,
      Clock clock,
      ObservationRegistry observations,
      MeterRegistry meters) {
    this.rooms = rooms;
    this.publisher = publisher;
    this.snapshots = snapshots;
    this.sessions = sessions;
    this.games = games;
    this.deduper = deduper;
    this.rateLimiter = rateLimiter;
    this.properties = properties;
    this.json = json;
    this.clock = clock;
    this.observations = observations;
    this.meters = meters;
  }

  void handle(UUID guestId, String rawCode, byte[] frame) {
    String code = RoomCodes.normalize(rawCode).orElse(null);
    String type = "unknown";
    Observation observation =
        Observation.createNotStarted("dinoparty.ws.command", observations).start();
    try (MDC.MDCCloseable g = MDC.putCloseable("guestId", guestId.toString());
        MDC.MDCCloseable r = code == null ? null : MDC.putCloseable("roomCode", code)) {
      if (code == null) {
        error(guestId, null, null, ErrorCode.ROOM_NOT_FOUND, "No room with that code exists.");
        count(type, "rejected");
        return;
      }
      if (frame.length > properties.maxMessageBytes()) {
        error(guestId, code, null, ErrorCode.PAYLOAD_TOO_LARGE, "The command is too large.");
        count(type, "rejected");
        return;
      }
      Command early = parseOrNull(frame);
      Optional<CommandSpec> gameSpec =
          early == null ? Optional.empty() : games.commandSpec(early.type());
      if (gameSpec.isPresent()) {
        type = early.type();
        observation.lowCardinalityKeyValue("command.type", type);
        observation.highCardinalityKeyValue("room.code", code);
        handleGameCommand(guestId, code, early, gameSpec.get());
        return;
      }
      try {
        rateLimiter.check("ws-command:" + guestId, properties.commandLimitPer10s(), 10);
      } catch (RateLimitedException e) {
        errorsQueue(guestId, code, null, ErrorCode.RATE_LIMITED, "Too many commands.", true);
        count(type, "rate-limited");
        return;
      }
      RoomRef room = rooms.find(code).filter(r2 -> rooms.isMember(guestId, r2.code())).orElse(null);
      if (room == null) {
        error(guestId, code, null, ErrorCode.NOT_IN_ROOM, "You are not a member of this room.");
        count(type, "rejected");
        return;
      }
      Command command;
      try {
        command = parse(frame);
      } catch (InvalidCommand e) {
        privateError(guestId, room, null, ErrorCode.VALIDATION_FAILED, e.getMessage(), false);
        count(type, "rejected");
        return;
      }
      type =
          RESERVED.contains(command.type()) || isKnown(command.type()) ? command.type() : "unknown";
      observation.lowCardinalityKeyValue("command.type", type);
      observation.highCardinalityKeyValue("room.code", code);
      execute(guestId, room, command, type);
    } catch (RuntimeException e) {
      observation.error(e);
      log.error("Command handling failed", e);
      errorsQueue(guestId, code, null, ErrorCode.SERVER_ERROR, "Something went wrong.", true);
      count(type, "error");
    } finally {
      observation.stop();
    }
  }

  private void execute(UUID guestId, RoomRef room, Command command, String metricType) {
    rooms.inRoom(
        room.code(),
        () -> {
          if (command.actionId() != null
              && !deduper.firstSeen(room.code(), guestId, command.actionId())) {
            privateError(
                guestId,
                room,
                command.actionId(),
                ErrorCode.DUPLICATE_ACTION,
                "This action was already received.",
                false);
            count(metricType, "duplicate");
            return null;
          }
          try {
            dispatch(guestId, room, command);
            count(metricType, "accepted");
          } catch (DomainException e) {
            if (e.code() == ErrorCode.SERVER_ERROR) {
              errorsQueue(guestId, room.code(), command.actionId(), e.code(), e.getMessage(), true);
            } else {
              privateError(
                  guestId, room, command.actionId(), e.code(), e.getMessage(), isRetryable(e));
            }
            count(metricType, "rejected");
          } catch (InvalidCommand e) {
            privateError(
                guestId,
                room,
                command.actionId(),
                ErrorCode.VALIDATION_FAILED,
                e.getMessage(),
                false);
            count(metricType, "rejected");
          }
          return null;
        });
  }

  private void dispatch(UUID guestId, RoomRef room, Command command) {
    switch (command.type()) {
      case "SET_READY" -> {
        JsonNode ready = command.payload().get("ready");
        if (ready == null || !ready.isBoolean()) {
          throw new InvalidCommand("payload.ready must be a boolean.");
        }
        rooms.setReady(guestId, room.code(), ready.asBoolean());
        ack(guestId, room, command);
      }
      case "START_GAME" -> {
        sessions.start(room.code(), guestId);
        ack(guestId, room, command);
      }
      case "REMATCH_VOTE" -> {
        JsonNode vote = command.payload().get("vote");
        if (vote == null || !vote.isBoolean()) {
          throw new InvalidCommand("payload.vote must be a boolean.");
        }
        sessions.rematchVote(room.code(), guestId, vote.asBoolean());
        ack(guestId, room, command);
      }
      case "REQUEST_SNAPSHOT" -> {
        snapshots.sendTo(guestId, room.code(), command.actionId());
      }
      case "PING" -> {
        publisher.publishPrivate(
            room.code(),
            room.id(),
            guestId,
            "PONG",
            command.actionId(),
            Map.of("serverNow", Timestamps.format(clock.instant())));
      }
      default ->
          throw new DomainException(ErrorCode.INVALID_ACTION, "This action is not available.");
    }
  }

  private void ack(UUID guestId, RoomRef room, Command command) {
    publisher.publishPrivate(
        room.code(),
        room.id(),
        guestId,
        "ACTION_ACK",
        command.actionId(),
        Map.of("actionId", command.actionId(), "accepted", true));
  }

  /**
   * A game command takes its own path: per-type size and rate limits from the module's spec instead
   * of the generic bucket, and authorization from the in-memory roster instead of the database, so
   * a stroke costs no query.
   */
  private void handleGameCommand(UUID guestId, String code, Command command, CommandSpec spec) {
    String type = command.type();
    if (command.payload().toString().length() > spec.maxPayloadBytes()) {
      errorsQueue(
          guestId,
          code,
          command.actionId(),
          ErrorCode.PAYLOAD_TOO_LARGE,
          "The command is too large.",
          false);
      count(type, "rejected");
      return;
    }
    try {
      int perSecond = Math.max(spec.burst(), (int) Math.ceil(spec.ratePerSecond()));
      rateLimiter.check("ws-game:" + guestId + ":" + type, perSecond, 1);
    } catch (RateLimitedException e) {
      errorsQueue(
          guestId, code, command.actionId(), ErrorCode.RATE_LIMITED, "Too many commands.", true);
      count(type, "rate-limited");
      return;
    }
    rooms.inRoom(
        code,
        () -> {
          try {
            sessions.authorize(code, guestId);
          } catch (DomainException e) {
            errorsQueue(guestId, code, command.actionId(), e.code(), e.getMessage(), false);
            count(type, "rejected");
            return null;
          }
          RoomRef room =
              sessions
                  .activeRoomId(code)
                  .map(id -> new RoomRef(id, code))
                  .or(() -> rooms.find(code))
                  .orElse(null);
          if (room == null) {
            error(
                guestId,
                code,
                command.actionId(),
                ErrorCode.ROOM_NOT_FOUND,
                "No room with that code exists.");
            return null;
          }
          if (!deduper.firstSeen(code, guestId, command.actionId())) {
            privateError(
                guestId,
                room,
                command.actionId(),
                ErrorCode.DUPLICATE_ACTION,
                "This action was already received.",
                false);
            count(type, "duplicate");
            return null;
          }
          try {
            Map<String, Object> payload =
                json.convertValue(
                    command.payload(), new tools.jackson.core.type.TypeReference<>() {});
            sessions.submit(code, guestId, type, command.phaseId(), payload);
            ack(guestId, room, command);
            count(type, "accepted");
          } catch (DomainException e) {
            if (e.code() == ErrorCode.SERVER_ERROR) {
              errorsQueue(guestId, code, command.actionId(), e.code(), e.getMessage(), true);
            } else {
              privateError(
                  guestId, room, command.actionId(), e.code(), e.getMessage(), isRetryable(e));
            }
            count(type, "rejected");
          }
          return null;
        });
  }

  private Command parseOrNull(byte[] frame) {
    try {
      return parse(frame);
    } catch (InvalidCommand e) {
      return null;
    }
  }

  private Command parse(byte[] frame) {
    JsonNode root;
    try {
      root = json.readTree(frame);
    } catch (RuntimeException e) {
      throw new InvalidCommand("The command is not valid JSON.");
    }
    if (root == null || !root.isObject()) {
      throw new InvalidCommand("The command must be a JSON object.");
    }
    JsonNode typeNode = root.get("type");
    if (typeNode == null || !typeNode.isString() || !TYPE.matcher(typeNode.asString()).matches()) {
      throw new InvalidCommand("type must be 1-40 characters of A-Z and _.");
    }
    String type = typeNode.asString();
    String actionId = null;
    JsonNode actionNode = root.get("actionId");
    if (actionNode != null && !actionNode.isNull()) {
      actionId = uuidOrNull(actionNode);
      if (actionId == null) {
        throw new InvalidCommand("actionId must be a UUID.");
      }
    } else if (!"PING".equals(type)) {
      throw new InvalidCommand("actionId is required.");
    }
    JsonNode phaseNode = root.get("phaseId");
    String phaseId = null;
    if (phaseNode != null && !phaseNode.isNull()) {
      phaseId = uuidOrNull(phaseNode);
      if (phaseId == null) {
        throw new InvalidCommand("phaseId must be a UUID or null.");
      }
    }
    JsonNode payload = root.get("payload");
    if (payload == null || payload.isNull()) {
      payload = json.createObjectNode();
    } else if (!payload.isObject()) {
      throw new InvalidCommand("payload must be an object.");
    }
    return new Command(type, actionId, phaseId, payload);
  }

  private static String uuidOrNull(JsonNode node) {
    if (!node.isString() || node.asString().length() != 36) {
      return null;
    }
    try {
      return UUID.fromString(node.asString()).toString();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean isKnown(String type) {
    return "SET_READY".equals(type)
        || "REQUEST_SNAPSHOT".equals(type)
        || "PING".equals(type)
        || "START_GAME".equals(type)
        || "REMATCH_VOTE".equals(type);
  }

  private static boolean isRetryable(DomainException e) {
    return e.code() == ErrorCode.RATE_LIMITED;
  }

  /** Errors for a guest we have confirmed is a member: the private room stream. */
  private void privateError(
      UUID guestId, RoomRef room, String actionId, ErrorCode code, String message, boolean retry) {
    publisher.publishPrivate(
        room.code(),
        room.id(),
        guestId,
        "GAME_ERROR",
        actionId,
        errorPayload(actionId, code, message, retry));
  }

  private void error(UUID guestId, String code, String actionId, ErrorCode error, String message) {
    errorsQueue(guestId, code, actionId, error, message, false);
  }

  private void errorsQueue(
      UUID guestId,
      String roomCode,
      String actionId,
      ErrorCode code,
      String message,
      boolean retry) {
    publisher.publishError(
        guestId, roomCode, actionId, errorPayload(actionId, code, message, retry));
  }

  private static Map<String, Object> errorPayload(
      String actionId, ErrorCode code, String message, boolean retryable) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("actionId", actionId);
    payload.put("code", code.name());
    payload.put("message", message);
    payload.put("retryable", retryable);
    return payload;
  }

  private void count(String type, String result) {
    meters.counter("dinoparty.ws.commands", "type", type, "result", result).increment();
  }
}
