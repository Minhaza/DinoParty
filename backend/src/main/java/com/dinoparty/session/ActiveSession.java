package com.dinoparty.session;

import com.dinoparty.common.RandomSource;
import com.dinoparty.engine.DeadlineBook;
import com.dinoparty.engine.GameModule;
import com.dinoparty.engine.GameSetup;
import com.dinoparty.engine.GameState;
import com.dinoparty.engine.PlayerInfo;
import com.dinoparty.engine.ScoreLedger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the engine knows about the one game of a room. Plain mutable state: it is only ever
 * touched inside the room mailbox, so it needs no locking and is never exposed outside this
 * package.
 */
final class ActiveSession {

  final String code;
  final UUID roomId;
  final GameModule module;

  /** Identifies this object for scheduler wake-ups, so a wake-up of an older session is ignored. */
  final UUID instanceId;

  SessionStatus status = SessionStatus.STARTING;
  UUID sessionId;

  /** Settings and module setup are frozen when the host starts (before the countdown). */
  Map<String, Object> settings = Map.of();

  GameSetup setup = GameSetup.NONE;
  RandomSource random;
  Instant startedAt;
  final Map<UUID, RosterSlot> roster = new LinkedHashMap<>();
  List<UUID> order = List.of();

  /** The room's host, kept in step with the room's HostChanged events (no lookup per call). */
  UUID host;

  GameState state;
  ScoreLedger ledger;

  String phase;
  UUID phaseId;

  /** Id announced to the module in its GameContext; becomes phaseId if its call changes phase. */
  UUID nextPhaseId;

  Instant phaseStartedAt;
  Instant deadlineAt;
  final DeadlineBook deadlines = new DeadlineBook();
  int completedRounds;

  final Map<UUID, Boolean> votes = new LinkedHashMap<>();
  List<Map<String, Object>> standings = List.of();
  String endReason;

  DeadlineScheduler.Handle handle;
  Instant armedAt;

  ActiveSession(String code, UUID roomId, GameModule module, UUID instanceId) {
    this.code = code;
    this.roomId = roomId;
    this.module = module;
    this.instanceId = instanceId;
  }

  List<PlayerInfo> rosterInfos() {
    List<PlayerInfo> infos = new ArrayList<>();
    order.forEach(g -> infos.add(roster.get(g).info));
    return infos;
  }
}
