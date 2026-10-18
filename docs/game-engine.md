# DinoParty — Shared Game Engine (Phase 4 design)

Status: **implemented in Phase 4** (see section 13 for what differs from this design). Written against the Phase 3 code (`room`, `realtime`) as it existed on `main`. Wire formats live in `docs/api-contracts.md` (single source of truth); gameplay rules in `docs/game-rules.md` (`[C-4]…[C-12]` are the engine rules); decisions that need approval are D13–D23 in `docs/decisions-and-risks.md` and are marked **(D#)** below. Nothing here changes Draw & Guess or Telephone gameplay; it defines the host they run in.

## 0. Starting point (what Phase 3 actually has)

Verified in code, and different from the original architecture sketch in places that matter:

- There is **no in-memory `RoomRuntime`**. Membership, `ready` and room status are DB-backed. `RoomMailbox` is a keyed, reference-counted `ReentrantLock` per room code (not a queue), and `RoomService.inRoom(code, action)` runs code under it. `RoomService` publishes `RoomEvent`s inside the mailbox; `realtime` maps them to envelopes with gapless per-stream `seq`.
- `RoomStatus` already has `STARTING/PLAYING/RESULTS`, but nothing moves a room out of `WAITING`. REST `start`, `kick`, `close` are in the contract but **not implemented**; `START_GAME` answers `INVALID_ACTION`.
- No `engine` package exists. Game settings schemas are hard-coded in `room.SettingsSchema`; `GameCatalog` reads `game_definition` rows. `GET /games*` is not implemented.
- `SnapshotDto.game` is `null`. `CommandRouter` verifies membership **in the database** for every command and applies one generic rate limit (60/10 s). `ActionDeduper` (256 ids / 5 min per guest and room) and presence/grace are in memory in `realtime`. The grace period is one value (30 s).
- The WebSocket frame cap is 64 KiB (`app.realtime.max-message-bytes`).

The design keeps all of this and adds the engine **inside the existing mailbox**, rather than introducing the `RoomRuntime` actor of the first sketch (architecture §3.2 is updated accordingly).

## 1. Modules and registration

### 1.1 Packages and dependencies

```
common ◄── guest ◄──────────────┐
   ▲                            │
   ├── engine   (SPI + pure runtime: no Spring Web/JPA, no room/realtime/guest)
   │      ▲
   ├── room  ───► engine (api)    ── owns membership, room status, REST-visible room state
   │      ▲
   ├── session ─► room, engine, guest, common   ── NEW: orchestrates one room's game lifecycle
   │      ▲            (defines ports: SessionEventSink, SessionRecorder)
   ├── realtime ─► session, room, engine, guest    ── implements SessionEventSink
   ├── history ──► session (SessionRecorder impl), engine, common   ── JPA for game_* tables
   ├── games.* ──► engine, drawing  (never room/session/realtime/web/JPA)
   └── web ─────► services only
```

Why a new `session` module: `engine` must stay pure and unit-testable without Spring, yet something has to combine "room membership + room status changes + engine state + realtime publishing + persistence". Putting that in `room` would make `room` depend on `realtime`/`history` (cycle); putting it in `realtime` would bury game lifecycle in the transport layer. `session` is the one place that knows both sides and talks to the outside only through ports. ArchUnit gets rules for: `engine` depends on `common` only; `games..` depends on `engine`/`drawing`/`common` only; `session` does not depend on `realtime`/`web`/`history`/JPA; `history` is the only user of `game_*` entities; nobody but `history` touches `history.persistence`.

### 1.2 Game registration

```java
public interface GameModule {
  GameDefinition definition();
  // lifecycle and behavior: §2 / §5
}

public record GameDefinition(
    String key,                        // "draw-guess"; matches game_definition.key
    String name, String tagline, String iconKey,
    int minPlayers, int maxPlayers,    // lobby bounds (what POST /rooms validates)
    int minActivePlayers,              // below this the engine ends the session [C-1]
    boolean scored,                    // false => no standings, PlayerDto.score null
    List<SettingField> settingsSchema, // moves here from room.SettingsSchema
    Map<String,Object> defaultSettings, // as built (P7-01): GameMetadata metadata + SettingsSchema settings (defaults inside)
    List<CommandSpec> commands,        // §5
    List<String> eventTypes)           // declared outbound types, checked in tests
```

- Modules are Spring `@Component`s. `GameRegistry` collects `List<GameModule>` at startup, indexes by `key`, and **fails startup** on duplicate keys, a command/event `type` declared by two modules (a game's types must be prefixed unless it is a reference game, api-contracts §3.6), or a key that violates `[a-z][a-z-]{1,39}`.
- `game_definition` rows (Flyway-seeded, `enabled` flag) stay the switch for *availability*; the module is the source of truth for *behavior and bounds*. A startup check compares both: an enabled row with no module → startup failure (a room could never start); a module with no row → logged and treated as unavailable (the data migration is part of "adding a game", architecture §9); bounds that differ → startup failure. This keeps the Phase 2 data and tests valid.
- `room.SettingsSchema` becomes a thin adapter over `GameRegistry` (same validation, same `fieldErrors`); the Phase 2 REST ITs must pass unchanged — task 4.1 acceptance. `GET /games` and `GET /games/{key}` (contract-only so far) are served from the registry + DB flag in the same task.
- A test-only module (`test` scope, key `test-game`, its row inserted by a test-only Flyway location) exercises the engine in Phase 4; no gameplay module ships in this phase.

### 1.3 SPI

```java
public interface GameModule {
  GameDefinition definition();
  Transition init(GameContext ctx, SessionRoster roster, Map<String,Object> settings);
  Transition handle(GameState state, PlayerAction action, GameContext ctx);        // may throw ActionRejected
  Transition onDeadline(GameState state, DeadlineFired deadline, GameContext ctx);
  Transition onPlayerStatus(GameState state, PlayerStatusChange change, GameContext ctx); // DISCONNECTED/RECONNECTED/LEFT
  GameView view(GameState state, Viewer viewer, GameContext ctx);                  // snapshot, §7
  GameOutcome outcome(GameState state, FinishReason reason, GameContext ctx);      // §6.4, called once
}

public record Transition(
    GameState next,                   // same instance allowed (mutable bounded state)
    Optional<PhaseChange> phase,      // new phase: name, round info, public data, deadlines
    List<OutboundEvent> events,       // ordered, with audience (§6)
    List<DeadlineRequest> deadlines,  // for the current/new phase, absolute Instant
    List<ScoreDelta> scores,          // (guestId, points, reason)
    List<RecordIntent> records,       // rounds/submissions to persist (§8)
    boolean finished) {}
```

`GameContext` offers: the injected `Clock`, a session-seeded `RandomSource` (all randomness, reproducible in tests; the seed never leaves the server), the frozen roster/order, the settings, and `ResourceLookup` for word lists. It deliberately offers **no** thread, scheduler, repository or publisher.

Contract between engine and module:

1. **Single-threaded.** All calls for one session happen under the room mailbox, one at a time. The module needs no locking and must not start threads or retain the context/state outside a call.
2. **Validate, then mutate.** `handle` either throws `ActionRejected(ErrorCode, reason)` having changed nothing, or returns a transition. The engine does not roll back partial mutation; a module test per command asserts "rejection leaves state equal".
3. **State may be mutable** (bounded stroke buffer) but only inside these calls. Transitions are otherwise pure in `(state, input, ctx)`; the clock and RNG come from `ctx`, so no `Instant.now()`/`new Random()` (also enforced by an ArchUnit rule on `games..` and `engine..`).
4. **No I/O in a call.** No DB, no HTTP, no sleeping; resources are loaded at startup. Budget per call: well under 5 ms; the mailbox hold time metric (§3) warns above 50 ms.
5. **An unexpected exception** from `handle` is answered `SERVER_ERROR` to that player and the session continues. From `init`, `onDeadline`, `onPlayerStatus` or `view` it aborts the session with `INTERNAL_ERROR` (a game stuck with no deadline is worse than an aborted one); the room returns to `WAITING`, logged with `roomCode`/`sessionId` MDC. State after a thrown `handle` is considered suspect only if the module violates rule 2.

## 2. Session lifecycle

### 2.1 Two state machines

Room status (persisted in `room.status`, owned by `RoomService`, changed only through lifecycle methods called by `session` inside the mailbox) and session status (`ActiveSession` in memory; persisted in `game_session.status`).

```
Room:     WAITING ──start──► STARTING ──countdown──► PLAYING ──finish──► RESULTS ──reset──► WAITING
             ▲  ▲               │                      │                    │
             │  └─cancel────────┘                      │ abort/no round     │ close / everyone leaves
             └─────────────────────abort (no results)──┘                    ▼
                        any status ──host close / last member leaves──► CLOSED

Session:  (none) ──start──► RUNNING ──┬─ finish ──► FINISHED   (results written)
                                      ├─ abort  ──► ABORTED    (no results; reason)
                                      └─ restart ─► INTERRUPTED (set at next startup; §9)
```

| From → To | Trigger (always inside the mailbox) | Guards | Effects |
|---|---|---|---|
| `WAITING → STARTING` | host `START_GAME` / `POST /start` | caller is host; status `WAITING`; connected members ≥ `minPlayers`, ≤ `maxPlayers`; game enabled | persist status; schedule `START_COUNTDOWN` deadline (+3 s); `ROOM_STATUS_CHANGED` |
| `STARTING → STARTING` | repeated start | — | acknowledged, nothing changes (idempotent) |
| `STARTING → WAITING` | member leaves/times out/is kicked and connected < `minPlayers`; or host leaves with nobody able to host | evaluated on every membership event while `STARTING` | cancel deadline; `START_CANCELLED` |
| `STARTING → PLAYING` | `START_COUNTDOWN` fires | re-check connected ≥ `minPlayers` (else cancel as above); freeze roster; draw seed and `order` | insert `game_session` (§8) **synchronously**; `module.init`; persist status; events in the order of api-contracts §3.7 |
| `PLAYING → PLAYING` | module transition | phase change rules §4 | |
| `PLAYING → RESULTS` | `Transition.finished`, or active (non-`LEFT`) players `< minActivePlayers` with ≥ 1 completed round | — | `module.outcome` → ledger totals → `GAME_FINISHED`; enqueue result write (§8); room status; start `RESULTS_TIMEOUT` deadline (15 min, D19) |
| `PLAYING → WAITING` | no completed round and finish/under-minimum; internal error; graceful shutdown | — | `ABORTED`; `GAME_ABORTED`; `ready` cleared |
| `RESULTS → WAITING` | rematch rule (§10) or `RESULTS_TIMEOUT` | — | drop `ActiveSession`; clear ready/scores/votes |
| any `→ CLOSED` | host close; last member leaves | — | cancel all deadlines; session `ABORTED(ROOM_CLOSED)` if `RUNNING`; drop `ActiveSession` |

Invalid transitions are not exceptional paths: every lifecycle method begins with an explicit `require(status ∈ allowed)` and throws `INVALID_PHASE` otherwise; the table above is the test list (one test per row plus one per forbidden cell for the room machine).

### 2.2 Who calls what

`SessionService` (module `session`) is the only caller of the room lifecycle methods (`RoomService.beginStarting/beginPlaying/enterResults/resetToWaiting/close`, new, public, require being inside the mailbox) and the only owner of `ActiveSessions`. Inputs reach it from:

- REST/WS start, kick, close, rematch vote → controllers/`CommandRouter` → `SessionService` (still `inRoom` + existing transactions for room rows).
- `RoomEvent`s (`MemberLeft`, `HostChanged`, `Closed`) → a synchronous `@EventListener` in `session`; they are published inside the mailbox, so the roster stays consistent with membership without extra locking.
- Presence changes → `PresenceTracker` calls `SessionService.onConnection(code, guest, connected)` (new port call from `realtime`, already inside the mailbox).
- Deadlines → §3.

The in-game disconnect grace (60 s, game-rules) needs a second property next to `disconnectGrace`; `PresenceTracker` picks the value from the room status at disconnect time. This is the only change to presence.

## 3. Concurrency and state ownership

### 3.1 Rule

**One serialization point: the room mailbox** (`RoomService.inRoom`). Every mutation of room or game state — REST, STOMP command, presence, membership event, deadline — runs as one mailbox command. Nothing else mutates game state, and nothing else takes a lock that could be held across a mailbox entry (no `synchronized` ad hoc; `ActionDeduper`'s internal monitor is the single existing exception and is never held while entering the mailbox).

| State | Owner | Where it lives | Touched by |
|---|---|---|---|
| Membership, host, `ready`, room status/settings | `RoomService` | DB (`room`, `room_participant`) | mailbox only for writes |
| Active session: phase, game state, roster/order, ledger, deadline book, rematch votes | `ActiveSession` | memory, `ActiveSessions` map `roomCode → ActiveSession` | mailbox only |
| Presence/grace | `PresenceTracker` | memory | mailbox for effects; its maps are concurrent |
| Event `seq` counters | `RoomEventPublisher` | memory | published inside the mailbox |
| `actionId` windows | `ActionDeduper` | memory | called inside the mailbox |
| Rate-limit buckets | `RateLimiter` | memory | before the mailbox (cheap path) |
| History write queue | `SessionRecorder` | memory, bounded | producer inside mailbox, consumer on its own thread |

Reading `ActiveSession` outside the mailbox is forbidden (only metrics may read immutable counters). Snapshots, results-needing code and tests all go through `inRoom`.

### 3.2 Mailbox properties the engine depends on (and one change)

- It is a **lock, not a queue**: waiting commands block their (virtual) thread; with Java 21 virtual threads this is acceptable for ≤ 10 players per room. Ordering between *different* threads waiting for one room is whatever the lock grants. **Change (task 4.2):** make the lock fair (`new ReentrantLock(true)`), so `[C-7]` "dequeued order" is first-come-first-served and a deadline wake-up cannot starve behind a stroke flood. Same-connection order is already guaranteed by `preserveReceiveOrder` at the transport.
- Slot reference counting means an idle room holds no lock object; `ActiveSessions` entries are removed on `RESULTS→WAITING`, abort and close, and a test asserts `ActiveSessions.size()==0` after every lifecycle test (leak guard).
- **No lock nesting across rooms.** A command never enters a second room's mailbox. Deadline wake-ups enter exactly one.
- **No DB on the hot path.** Phase 3's router asks the database "is this guest a member?" per command. With strokes at up to 30/s per drawer that is unacceptable. While an `ActiveSession` exists, game commands are authorized against the in-memory roster (members only leave through `MemberLeft` → roster update, both inside the mailbox). The DB check remains for lobby commands and `SUBSCRIBE`. Acceptance test: N strokes cause zero DB statements (Hibernate statistics).
- Mailbox hold time is measured (`dinoparty.mailbox.hold`), with a WARN above 50 ms and a metric for waiters; both expose misbehaving modules.

### 3.3 Cross-room safety

`ActiveSessions` is a `ConcurrentHashMap` used only for lookup/insert/remove; no collection is shared between rooms, no session object is reachable from another room's state. Per-room random sources are independent instances seeded per session.

## 4. Actions, ids and sequencing

Four distinct counters/ids exist; they answer different questions and must not be conflated:

| Id | Set by | Question it answers | Scope | Where enforced |
|---|---|---|---|---|
| `actionId` | client (UUID per command) | "is this the same command again?" (idempotency) | per (guest, room), last 256 / 5 min | `ActionDeduper`, inside the mailbox, before the engine |
| `phaseId` | server (fresh UUID per phase) | "was this meant for the situation that still exists?" | per session | engine `ActionGate` |
| `seq` | server | "did I miss an event?" (output ordering) | per stream (`ROOM`, per-recipient `PRIVATE`) | publisher; clients detect gaps |
| *(none)* client-side order | transport | "in which order did one connection send?" | per connection | `preserveReceiveOrder`; modules needing gap detection (strokes) carry their own counter in the payload |

### 4.1 The validation pipeline (every game command, in this order)

1. **Transport** (before the mailbox, cheap): frame size, JSON, envelope, `type` known to the registry for this room's game (`INVALID_ACTION`/`VALIDATION_FAILED`), per-type payload bytes (`PAYLOAD_TOO_LARGE`), per-type rate limit (`RATE_LIMITED`). Unknown or other-game types never reach a module.
2. **Typed payload binding + Bean Validation** from the `CommandSpec` (§5) → `VALIDATION_FAILED`.
3. Enter the mailbox. **Membership**: in-memory roster during a session (`NOT_IN_ROOM` if not on the roster or `LEFT`).
4. **Dedupe** `(guest, actionId)` → `DUPLICATE_ACTION`. Recording happens here, so a command rejected later still burns its id; clients retry with a new id (contract, unchanged).
5. **Drain due deadlines** (§4.2). This makes steps 6–7 see the world as of `now`.
6. **Session/phase guard** (`ActionGate`): session exists and `RUNNING` (else `INVALID_PHASE`); `action.phaseId == session.phaseId` (else `STALE_ACTION`); the command type is allowed in the current phase per `CommandSpec.phases` (else `INVALID_PHASE`).
7. **Module `handle`**: role checks (`NOT_DRAWER`, `IS_DRAWER`), semantic validation, scoring. Throws `ActionRejected` or returns a `Transition`.
8. **Apply** the transition atomically (§4.3), publish, then send `ACTION_ACK` (existing behavior: after events).

Only authenticated identity is used; `PlayerAction.guestId` comes from the principal, never from payload.

### 4.2 Deadline-first rule (`[C-6]`)

Before any command, presence change or membership event is handled, the engine runs `session.drainDue(now)`: it executes every deadline with `at ≤ now` in `(at, registrationOrder)` order, each through `module.onDeadline` and the normal apply step (a deadline may start a new phase whose own zero-length deadline is also due — loop with a bound of 1000 iterations, then abort `INTERNAL_ERROR`). Consequences: a late action finds the next phase and gets `STALE_ACTION`; there is no window in which an action is accepted "after" its deadline, whatever the scheduler thread is doing; tests with a manual clock need no real scheduler.

### 4.3 Applying a transition

In order, in memory, all-or-nothing from the point of view of other mailbox commands:

1. `phase` present → new `phaseId`; **cancel every deadline of the old phase**; record `phaseStartedAt`, `deadlineAt` (the earliest `PHASE_END`-kind deadline, or the module-stated one).
2. Register the new deadlines in the deadline book (§5) and re-arm the wake-up.
3. Apply `scores` to the ledger.
4. Enqueue `records` to the `SessionRecorder` (§8) — an in-memory enqueue, never a DB call here.
5. Publish `events` through `SessionEventSink` in the transition's order, with the engine-generated `PHASE_STARTED` first among them if the phase changed.
6. If `finished`: run the finish path (§6.4, §2.1).

Anything that can fail (unknown deadline kind, event type not declared by the module, audience invalid, non-roster recipient, score on a non-roster guest) is checked **before** step 1 and treated as a module bug (`INTERNAL_ERROR`). Publishing failures are swallowed and logged like in Phase 3 (a failed send to one socket must not break the room); state is already committed, and the client resyncs through the gap rule.

## 5. Command specs and the deadline scheduler

### 5.1 `CommandSpec`

Declared by the module, enforced by the engine/router (item 1–2 of §4.1), so a game cannot forget a limit:

```java
record CommandSpec(String type, Class<?> payload, int maxPayloadBytes,
                   RateSpec rate,              // ratePerSecond, burst: one-second fixed window of max(burst, ceil(rate)) (architecture.md)
                   Set<String> phases,         // phase names where it is legal; empty = any
                   boolean needsPhaseId)       // true for all game commands
```

Defaults and tests: a module whose spec has no rate or no byte cap does not register (startup failure). The generic `command-limit-per-10s` bucket keeps protecting lobby commands; game commands use their own buckets (keyed `guest:type`) and are exempt from the generic one, otherwise `DRAW_STROKE` (≥ 20/s) could never work. Per-guest overall cap `engine.max-commands-per-second` (default 60) stays as a backstop across all types.

**Frame-size conflict (D20).** `TELEPHONE_SUBMIT_DRAWING` is allowed up to 256 KiB by game-rules, but the transport closes the socket at 64 KiB. Per-type caps cannot exceed the frame cap. Options in D20; recommendation: raise `max-message-bytes` to 320 KiB, keep the 32 KiB cap on `DRAW_STROKE` through its `CommandSpec`, and cap the rate of large frames per guest.

### 5.2 Deadline book and scheduler

Source of truth is **data inside the session**, not timer objects:

```java
record DeadlineEntry(String id, DeadlineKind kind, UUID phaseId, Instant at, long generation)
```

`ActiveSession.deadlines` holds the current entries (sorted by `at`). A module asks for deadlines in `Transition.deadlines` (`DeadlineRequest(id, kind, at)`); asking for an `id` that exists replaces it and bumps `generation` (reschedule); `DeadlineKind` is `PHASE_END` (advertised in `deadlineAt`), `INTERNAL` (hint reveal, summary pause), or engine-owned (`START_COUNTDOWN`, `RESULTS_TIMEOUT`). A phase change removes every entry of the old phase automatically; session end removes all.

`DeadlineScheduler` is a small port:

```java
interface DeadlineScheduler {
  Handle arm(String roomCode, Instant at);     // wake up once at `at`
  interface Handle { void cancel(); }
}
```

Production implementation: one daemon `ScheduledThreadPoolExecutor` (1 thread, `removeOnCancelPolicy`), whose task does nothing but `rooms.inRoom(code, () -> sessions.wake(code))` on a virtual thread. **At most one armed handle per session**, aimed at the earliest entry; re-armed after every change. The test implementation is `ManualScheduler` driven by a manual `Clock`: `advance(Duration)` runs due wake-ups synchronously on the caller thread.

**Stale-timer protection (layered):**

1. A wake-up carries no state; `wake` just calls `drainDue(now)`, which reads the book. A late, duplicate or spurious wake-up finds nothing due and does nothing.
2. A cancelled/replaced deadline is gone from the book, so even if its OS timer fires, no entry is due.
3. `onDeadline` receives `DeadlineFired(id, phaseId, generation)`; the engine only calls it for entries that still exist and belong to the current phase, and the module may additionally compare against its own state.
4. `wake` for an unknown room/session, or one with a different `sessionId` than the handle was armed for, is a no-op (handles store `sessionId`).
5. **Watchdog**: every 5 s (`@Scheduled`, same pattern as `RealtimeMaintenance`) every active session whose earliest deadline is overdue by > 1 s is drained and a `dinoparty.engine.deadline.missed` counter increments (should be 0). This covers a swallowed executor exception.
6. Early firing (system clock stepped back) is handled by the `at ≤ now` check; the session simply re-arms.

Metrics: `dinoparty.engine.deadline.lag` (fire time − `at`), active sessions, armed handles. Clock jumps are accepted risk (wall clock `Clock` bean, as everywhere).

Required race tests (manual clock, no sleeps): action exactly at `at` → `STALE_ACTION`; action 1 ms before → accepted; deadline replaced while pending; phase changed by an action cancels the old deadline (old wake-up fires → no effect); two wake-ups for one deadline → one `onDeadline`; wake-up arriving after session abort → no-op; 200-thread storm of actions racing one deadline → exactly one winner order, all outcomes consistent with the mailbox order.

## 6. Game boundaries: phases, turns, scoring, results, events

### 6.1 What the engine owns vs what the module owns

| Concern | Engine | Game module |
|---|---|---|
| Room status, countdown, results timeout, rematch, close | ✔ | — |
| Roster, shuffled `order`, session seed/RNG | ✔ | uses them |
| Phase *identity* (`phaseId`, one current phase, cancelling old deadlines, `PHASE_STARTED`) | ✔ | decides *when* to change and the phase name/public data |
| Phase *content*, round/turn logic, who may do what | — | ✔ (`[DG-*]`, `[TP-*]`) |
| Turn order | `TurnOrder` helper (shuffle once, `next(skipLeft)`) | decides what a turn means |
| Score ledger, ranking, `PlayerDto.score` | ✔ (integers, applied from `ScoreDelta`) | computes the deltas (`[DG-12]`, `[DG-13]`) |
| Disconnect/leave **detection**, grace, `LEFT` status, `minActivePlayers` | ✔ | `onPlayerStatus`: what happens to the held turn |
| Action authorization (identity, membership, phase, dedupe) | ✔ | role (`NOT_DRAWER`) and semantics |
| Standings, persistence, results API | ✔ | `outcome`: standings detail, round records |
| Rate limits, payload caps | ✔ (enforces) | declares them |

### 6.2 Turn order and rotation

`SessionRoster` = ordered list of `PlayerSlot(guestId, joinedAt, status ∈ ACTIVE|DISCONNECTED|LEFT)` frozen at start; `order` is a permutation made once by `TurnOrder.shuffle(players, ctx.random())`. Both games use the same `order` (`[DG-1]`, `[TP-1]` "shuffled once at game start"). Left players keep their slot (index stability matters for telephone chains) with status `LEFT`; `TurnOrder.nextActive(from)` skips them. Nobody is ever inserted.

### 6.3 Scoring

Modules emit `ScoreDelta(guestId, points, reasonCode)` inside a `Transition`; the ledger sums per session and keeps per-round deltas for the round record. Invariants (unit/property tested): totals equal the sum of deltas; deltas only for roster members; a `LEFT` player keeps their score; no API writes totals; integer overflow guarded (`Math.addExact` → abort). Ranking is `Ranking.of(totals, module.tieBreak())` → competition ranking (`[C-8]`).

### 6.4 Results boundary

`finished=true` (or an engine-forced finish) → engine calls `module.outcome(state, reason)` **once**, receiving no mutable engine objects, returning `GameOutcome(List<StandingDetail> detail, List<RoundRecord> rounds)`. The engine merges ledger totals + ranking + nickname/avatar snapshots, builds `GAME_FINISHED`, hands `SessionResult` to the recorder, and moves the room to `RESULTS`. A module never sees `RoomService`, never writes the DB, never publishes.

### 6.5 Public vs private payloads

Every `OutboundEvent` has an `Audience`:

| Audience | Delivery | Typical use |
|---|---|---|
| `PUBLIC` | `/topic/rooms/{code}`, one `ROOM` seq | `PHASE_STARTED`, `GUESS_ACCEPTED`, `ROUND_ENDED`, `DRAWING_UPDATED` |
| `PRIVATE(guestId)` | that member's private stream, one private `seq` | `WORD_CHOICES`, `DRAWER_WORD`, `TELEPHONE_TASK`, `GUESS_FEEDBACK` |
| `PRIVATE_TO(set)` | one private delivery per recipient | `[DG-7]` chat among correct guessers + drawer |

There is **no "public except X"**: a topic cannot exclude a subscriber, and per-recipient fan-out would cost N private seqs per event. Where the sender receives its own public event it is told to ignore it (the `DRAWING_UPDATED` contract change). Rules enforced by the engine and covered by contract tests:

1. The public payload is built from state **after** the transition and contains only fields the module marked public in its event class (`@PublicField` / a separate `Public` record per event; no reflection over the full state).
2. Secrets (word, choices, telephone source) exist only in private events and in `view(state, viewer)` for that viewer. An event type is declared `PUBLIC` or `PRIVATE` in `GameDefinition.eventTypes`; publishing a type on the wrong audience is a module bug (`INTERNAL_ERROR`, caught by tests).
3. Order inside one transition is preserved per stream. When a phase begins, the public `PHASE_STARTED` is published before the private events of that phase, so a client never holds a secret without knowing the phase it belongs to.
4. A secret becomes public only through a later transition (e.g. `ROUND_ENDED` reveals the word) — never by widening an earlier event.
5. Secrecy test harness (task 4.4): run a full test-game session with 3 recorders (host, other member, outsider) and assert that every secret token appears only in its owner's private events/snapshot; applies later to both real games.

## 7. Reconnect snapshot

### 7.1 Generation

`SnapshotService` (realtime) already builds the room part inside the mailbox. Phase 4 adds `SessionService.gameSnapshot(code, viewer)`, called in the same `inRoom` block:

```
GameSnapshot = header(engine) ++ module.view(state, viewer, ctx)
header = sessionId, gameKey, status, order, phase, phaseId, phaseStartedAt, deadlineAt, scores,
         players[{guestId,status}], results?, rematch?          (api-contracts §2.1)
```

- Viewer-specific: `view` receives the viewer and returns private fields only for that viewer (drawer's word, a telephone player's held entry and draft flag). A member not on the roster (impossible during a session) or a non-member gets nothing.
- Built **after** `drainDue(now)`, so a snapshot never shows an overdue phase.
- `seq` values come from the publisher inside the same block; `serverNow` as in Phase 3. The REST variant does not consume a private `seq`; the STOMP variant does (unchanged).
- Size bound: `view` must stay under 256 KiB serialized (strokes buffer is bounded by `[DG-14]`); the engine logs and truncates nothing — exceeding it is a module bug caught by a test with a maxed-out buffer.
- Snapshots are **complete**: a client holding only the snapshot plus events with `seq >` the snapshot's reaches the same state as one that never disconnected. This replay-equivalence is a property test on the test game (fold events vs. snapshot at every step, per viewer).

### 7.2 Restoration

Server side nothing is "restored": the state never left memory. A reconnecting member's connection is re-attached by presence (`PLAYER_RECONNECTED`, which cancels the grace), the engine calls `onPlayerStatus(RECONNECTED)` (modules may re-send nothing; the snapshot carries what they need), and the client asks for `REQUEST_SNAPSHOT`. The client side (AG) replaces the room **and** game stores wholesale (`AGENTS.md` §4), resets `lastSeq` to `snapshot.seq`, re-derives countdowns from `deadlineAt` and `serverNow`, then applies later events. Commands sent while disconnected are never replayed by the client; the server never queues anything for an absent player.

Edge cases fixed by this design: reconnect during `STARTING` (snapshot shows `room.status=STARTING`, `game:null`, `startsAt` in the room part is derived from the countdown deadline); reconnect during `RESULTS` (snapshot header carries `results` and `rematch`, so a refresh does not lose the final screen); reconnect after the room reset (snapshot has `game:null`, `lastSession` for the notice); reconnect after the client's grace expired (the member is gone: `NOT_IN_ROOM`, and the client falls back to the lobby/join flow).

## 8. Persistence boundaries

### 8.1 Principle

The database is the system of record for **who played, what was submitted and how it ended** — never for live state. Live state is not written per event, per stroke or per tick (MASTER_PROMPT). Writes happen at lifecycle boundaries only, from the mailbox as in-memory intents, drained by a writer thread, **except one synchronous insert**.

### 8.2 What is written when

| Moment | Row | How | If it fails |
|---|---|---|---|
| `STARTING → PLAYING` | `game_session` (`RUNNING`, roster snapshot, seed, `session_number`) | **synchronous, inside the start transaction** | the start fails: room back to `WAITING`, `SERVER_ERROR` to the host. Rationale: a durable `sessionId` is the FK target of everything else and the restart-recovery anchor |
| Round end | `game_round` (+ `score_deltas`, `meta`) | `RecordIntent`, async, idempotent upsert on `(session_id, round_no)` | retried with backoff (1 s…30 s, 10 attempts); then logged `ERROR` and counted (`dinoparty.history.dropped`); game continues |
| Accepted submission (Telephone) | `game_submission` | async, unique on `(session_id, author_guest_id, chain_index, step_no)` | same |
| Finish | `game_result` rows + `game_session` → `FINISHED` | async, **in one transaction**, highest priority | retried the same way; results endpoint answers `RESULTS_NOT_READY` until written; `GAME_FINISHED.resultsReady` tells the client |
| Abort / close / shutdown | `game_session` → `ABORTED` (+ `end_reason`) | async | same; recovery at next start also fixes it (§9) |
| Restart recovery | `game_session` → `INTERRUPTED` | startup task, synchronous | startup fails loudly only if the DB is unreachable (already a startup failure) |

`SessionRecorder` is a port in `session` implemented by `history`: a bounded `BlockingQueue` (capacity 10 000 records) consumed by one virtual thread. A full queue drops the **oldest non-result** record and counts it; result/finish records are never dropped. Shutdown drains the queue (bounded wait 10 s). Draw & Guess guesses and strokes are never recorded (D5, resolved: no artwork persistence in the MVP); Telephone submissions are, as one JSONB/`bytea` payload each at accept time.

### 8.3 Schema (migration `V4`, owned by `history`; replaces the sketch in architecture §3.6)

```
game_session  (id uuid PK, room_id FK, game_key FK, session_number int, status varchar(12) CHECK in
               ('RUNNING','FINISHED','ABORTED','INTERRUPTED'), end_reason varchar(32),
               settings jsonb, roster jsonb /* ordered [{guestId,nickname,avatarKey,avatarColor}] snapshot */,
               seed bigint, started_at, finished_at,
               UNIQUE(room_id, session_number))
               + partial UNIQUE(room_id) WHERE status='RUNNING'   -- at most one running session per room, DB-enforced
game_round    (id, session_id FK, round_no int, started_at, ended_at, end_reason varchar(32),
               score_deltas jsonb, meta jsonb, UNIQUE(session_id, round_no))
game_submission (id, session_id FK, round_id FK NULL, author_guest_id, kind ('TEXT','DRAWING'),
               chain_index NULL, step_no NULL, text NULL, payload jsonb/bytea NULL, timed_out boolean,
               submitted_at, UNIQUE(session_id, author_guest_id, chain_index, step_no))
game_result   (id, session_id FK, guest_id, nickname varchar, rank int, score int, detail jsonb,
               UNIQUE(session_id, guest_id))
```

Changes against the earlier sketch: `game_round` loses `drawer_guest_id`/`word`/`phase` (game-specific data goes in `meta`, written only at round end so a word never sits in a row while the round is live); `game_submission.kind` drops `GUESS` (D5); `game_result` stores the nickname snapshot (guests may rename later); `game_session` gains `end_reason`, `roster`, `seed` (never exposed by any DTO) and the partial unique index. Foreign keys to `guest_identity` are intentionally absent on `game_result.guest_id`/`author_guest_id` so retention cleanup of guests never destroys history (decision D21: guest purge keeps history rows). `room_id` is `ON DELETE RESTRICT`; closed-room cleanup deletes sessions first (retention job, post-MVP).

### 8.4 Module boundaries

`history` owns entities/repositories (ArchUnit: only `history` uses `history.persistence`); `session` sees only `SessionRecorder`, `SessionHistoryQuery` (for `lastSession` and `GET /sessions/{id}/results`, read-only DTOs). Game modules never see either. `GET /sessions/{id}/results` authorizes with `RoomService` membership of the session's `room_id` (`NOT_IN_ROOM` otherwise), reads only the DB (so it also works after the room reset), and answers `RESULTS_NOT_READY` while rows are missing.

## 9. Server restart behavior (needs approval — D13)

Fact: all of `ActiveSession` (state, deadlines, votes, seq counters, presence, dedupe) is in memory. A process restart destroys it; MASTER_PROMPT accepts that for the first deployment. Rooms, members and room rows survive in PostgreSQL, which creates the inconsistent state "room says `PLAYING`, nobody is running it" that the engine must resolve explicitly, not leave to chance.

**Options**

| | A. Interrupt and return to lobby (recommended) | B. Checkpoint and resume | C. Close everything |
|---|---|---|---|
| Behavior | Session → `INTERRUPTED`; room → `WAITING` with members kept and `ready` cleared | Serialize each `GameState` + deadline book at every phase boundary; rebuild after restart, re-arm deadlines (some already past) | Close all non-`CLOSED` rooms |
| Cost | Small: one startup task | Large: versioned state serialization per module, replay of strokes lost anyway, past-deadline policy, security of stored secrets (words in DB), schema per game | Smallest |
| Player experience | "Game interrupted by a server restart", same lobby, can start again | Best, but strokes/drafts lost and timers jump | Everyone must create/join again |
| Fits MASTER_PROMPT | ✔ | exceeds MVP | ✔ but unfriendly |

**Recommended behavior (A), precisely:**

1. At startup, **before** the STOMP endpoint accepts connections (`SmartInitializingSingleton` ordered before `WebSocketConfig`; failing readiness until done), `RestartRecovery` runs in one transaction: `UPDATE game_session SET status='INTERRUPTED', end_reason='SERVER_RESTART', finished_at=now WHERE status='RUNNING'`; `UPDATE room SET status='WAITING' WHERE status IN ('STARTING','PLAYING','RESULTS')`; `UPDATE room_participant ... ready=false` for those rooms. Completed rounds already persisted stay; no `game_result` rows are invented for an interrupted session, so `GET …/results` for such a session answers `200` with `status:"INTERRUPTED"`, `standings:[]` and the persisted rounds (api-contracts `ResultsDto.status`), not `RESULTS_NOT_READY`. `RESULTS`-state rooms lose nothing: their `game_result` rows were written at finish (or are lost with the write queue, in which case the session becomes `INTERRUPTED` too).
2. Counters restart at 1; clients reconcile through the existing snapshot rule. The first snapshot shows `WAITING`, `game:null`, `lastSession{INTERRUPTED, SERVER_RESTART}` (api-contracts §3.8). No event is replayed.
3. Presence is empty after a restart (Phase 3 limitation). Members who never reconnect are not timed out. Recovery therefore also **stamps `last_seen`-based expiry**: rooms whose every member's `guest_session.last_seen_at` is older than `app.room.orphan-ttl` (default 1 h) are closed by the maintenance sweep. (Small, optional task 4.7; without it, ghost rooms persist exactly as Phase 3 already documents.) *Built in P7-01 as `room.OrphanRoomCleanup` with property `app.room-orphan-ttl`; it also requires that no member joined within the TTL and that none is live (connected or in grace), applies to `WAITING` rooms only, and closes with `ROOM_CLOSED{IDLE}` (architecture.md §3.8).*
4. **Graceful shutdown** (`SIGTERM`, deploys): a `SmartLifecycle` bean with the earliest stop phase aborts every `RUNNING` session (`GAME_ABORTED{SERVER_SHUTDOWN}`, room → `WAITING`), drains the recorder queue, then lets the existing socket-closing run. A crash gets the same result one start later via recovery.
5. Single-instance assumption made explicit: recovery treats **every** `RUNNING` session as orphaned. Starting a second instance, or an overlapping blue/green deploy, against the same database would corrupt live games. `deploy/compose.prod.yml` must stop the old container before starting the new one (Phase 8 note) and the doc says so.
6. Clients: `STALE_ACTION` for the vanished `phaseId`; the AG shell shows a "game interrupted" banner from `lastSession`.

Open question inside D13: should an interrupted game with ≥ 1 persisted completed round show those rounds as partial results to players? Recommended **no** for the MVP (history only); revisit with B.

## 10. Rematch and room reset

State: `RematchVotes` (in `ActiveSession`, only meaningful in `RESULTS`): `Map<guestId, boolean>` for connected roster members still in the room.

Rules (`[C-3]`, `[C-10]`, `[C-11]`):

- `REMATCH_VOTE{vote}` accepted only while `RESULTS` (`INVALID_PHASE` otherwise); one vote per member, changeable; deduped by `actionId`; no `phaseId`. Each change emits `REMATCH_VOTES_UPDATED` with the full tally.
- **Reset triggers** (first wins): (a) host votes `true`; (b) every *connected* member of the room has voted `true` (disconnected members are ignored, so an AFK-disconnected player cannot block); (c) `RESULTS_TIMEOUT` (15 min). A `false` vote never blocks a reset; a member who does not want to continue simply leaves.
- Members leaving during `RESULTS` drop out of the tally; if the host leaves, hosting transfers (`[C-2]`) and the new host's vote counts as (a). If all members leave the room closes as usual.
- **Reset (all in one mailbox command):** `ActiveSession` removed and its handles cancelled; ledger/votes discarded; room → `WAITING` (persisted); every member's `ready=false`; members, host, game key, settings and `maxPlayers` unchanged; `ROOM_STATUS_CHANGED{WAITING, reason}`. `room.sessionId` returns to `null`; `game_session` for the finished game stays `FINISHED`. The host may edit settings or even change the game again (normal `WAITING` rules). Members who left or timed out during the game may join again (capacity permitting), as Phase 2 join rules apply.
- The next start inserts `session_number + 1` with a new seed and shuffle; nothing from the previous session is reachable (no cross-session state, tested by running two sessions back to back and comparing `order` distribution and absence of carried scores).
- A rematch vote while some member is mid-reconnect is safe: their snapshot contains `rematch` if still `RESULTS`, else `game:null` on the reset room.

## 11. Observability and limits (engine additions)

Logs carry `roomCode`, `guestId`, `sessionId`, `phaseId` in MDC for every mailbox command. Metrics: `dinoparty.engine.sessions.active`, `…commands{type,outcome}`, `…deadline.lag`, `…deadline.missed`, `dinoparty.mailbox.hold`, `dinoparty.history.queue.size`, `…dropped`. Config under `app.engine.*`: `start-countdown` (3 s), `results-timeout` (15 min), `in-game-disconnect-grace` (60 s; lives with realtime props), `max-commands-per-second`, `watchdog-interval`, `recorder.queue-capacity`, `recorder.retry`. Limits: sessions per process unbounded by design (rooms are bounded by guests); no per-process cap in the MVP, documented.

## 12. Testing the engine

| Layer | Tests |
|---|---|
| Unit (no Spring) | transition table per row; `ActionGate` pipeline ordering; ledger/ranking (competition ranking, ties, overflow); `TurnOrder` (permutation, skip-left); deadline book (replace, phase cancel, ordering, bounded drain loop); `RematchVotes`; `Transition` validation failures; secrecy of public payloads; module contract (rejection leaves state equal) run against the test game |
| Property | for random action/deadline interleavings with a manual clock: exactly one current phase; no deadline of an old phase in the book; ledger sum = sum of deltas; replay-equivalence of snapshot and event stream for every viewer |
| Integration (PostgreSQL) | `V4` constraints (one `RUNNING` per room, unique round/result); recorder retry and "persistence down does not break the live game" (stop the container mid-session); restart recovery on a database prepared with `RUNNING`/`PLAYING` rows; results endpoint states (`RESULTS_NOT_READY` → 200, `INTERRUPTED`) |
| WS IT (test game) | start → countdown → phases → finish → rematch; `STARTING` cancel on leave; unauthorized `START_GAME`; stale/late action; duplicate id; reconnect snapshot in each room status; secrecy across three sockets; kick/close during each status; concurrent start requests (exactly one `STARTING`); no DB statements per game command |
| Architecture | the ArchUnit rules of §1.1 plus "no `Instant.now`/`new Random`/`Thread`/`synchronized` in `engine`/`games`" |

Verification before the phase is declared done is listed in `docs/implementation-plan.md` (Phase 4 exit).

## 13. Implementation notes (what was built, and where it differs)

Built and verified (see `docs/testing.md`, "Phase 4 verification record"): modules `engine`, `session`, `history`; migration `V4`; test-scope game `test-game` (`src/test/java/com/dinoparty/games/testgame`; its catalog row lives in `src/test/resources/db/test-migration`, outside `db/migration`, so it can never reach a real database). It is not a product feature and has no UI.

Differences from the design above, all deliberate:

- **SPI shape.** `Transition` has a separate `closing` list (events of the ending phase, published before `PHASE_STARTED`) next to `events` (published after it); this guarantees the order of api-contracts 3.7 without the module knowing about `PHASE_STARTED`. Commands carry a `Map<String,Object>` payload that the module validates; typed binding of `CommandSpec.payload` was not built. `GameModule.init(ctx)` takes no roster or settings parameters: both are in `GameContext`.
- **Ports.** `session` defines `RoomLifecycle` (room status, mailbox, membership view), `SessionEventSink`, `SessionRecorder` and `DeadlineScheduler`; tests run the whole engine against in-memory fakes with a manual clock. `SessionHistoryQuery` is `history.SessionHistory`, used directly by the REST controller.
- **Room status changes** go through `RoomService.changeStatus` (explicit transition table, `REQUIRES_NEW` transaction because it can be called from an after-commit listener) and publish `RoomEvent.StatusChanged`.
- **Deadlines** are `DeadlineBook` entries inside the session; the countdown and the results timeout are engine deadlines in the same book. One timer thread only posts a `DeadlineWake`; the work runs on a virtual thread inside the mailbox. The watchdog runs from `RealtimeMaintenance` (every sweep, tolerance 1 s) instead of a separate 5 s task.
- **Authorization of game commands** uses the in-memory roster (`SessionService.authorize`) and is tested to cost zero Hibernate statements per accepted command; lobby commands and `SUBSCRIBE` still check the database.
- **Persistence.** `JdbcSessionRecorder` uses `JdbcClient` (no JPA entities for `game_*`). One writer thread with retry and backoff: results and abort markers retry until they succeed or the application stops; rounds give up after `max-attempts` and are counted; round records are dropped oldest-first when the queue is full. `game_submission` is created by Telephone in Phase 6 (`V10`, P6-03).
- **Startup.** `SessionLifecycleCallbacks` runs recovery in `afterSingletonsInstantiated` (before the web server starts) and aborts running games on a graceful stop. A catalog row without a module is only logged unless `app.engine.strict-registry=true`, because `draw-guess` and `telephone` are enabled in the database but have no module until Phases 5-6 (such a room answers `GAME_NOT_FOUND` on start).
- **Not built** (the first five were built in P7-01, architecture.md §3.8: the settings schema now lives on `GameDefinition` and `room.SettingsSchema` is gone): moving the settings schema behind the registry, `GET /games*`, `lastSession`, the `PATCH /guests/me` lock, the orphan-room sweep, the 320 KiB frame cap (D20), typed payload binding, the snapshot replay-equivalence property test, and the frontend plugin registry (task 4.8, Antigravity).
- **Grace.** `app.realtime.in-game-disconnect-grace` (default 60 s) is chosen by `PresenceTracker` while the room has a session.
- **SPI change in WP-02 (R21).** `GameModule.prepare(settings, host)` (default: `GameSetup.NONE`) runs when the host starts, inside the mailbox, before anything of the session exists; it is the only module hook allowed to read reference data, and a `DomainException` from it rejects the start with the room untouched (an unexpected exception is `SERVER_ERROR`). The engine freezes the room settings at that moment too (no longer at countdown end) and hands the returned immutable `GameSetup` to every call as `GameContext.setup`. Draw & Guess uses it to resolve and freeze its word pool (api-contracts §2.4). The test game freezes `secretPrefix` and can reject or crash its start; `SessionServiceTest` covers rejection, crash and the freeze.
- **SPI change in Phase 5 (R21).** `GameContext` gained `nextPhaseId`: the engine mints the id before every module call and uses it as the new `phaseId` if that call's transition starts a phase. Without it a module cannot put the `phaseId` into the private events of a phase it is starting (`WORD_CHOICES{phaseId}`, `DRAWER_WORD{phaseId}`, api-contracts §3.9), because the id was only created in `apply`. Deriving ids from the session's seeded RNG was rejected: phase ids are public and would expose the generator that also draws the words. The test game puts it into `TEST_SECRET`, and `SessionServiceTest.privateEventsOfANewPhaseCanCarryItsPhaseId` covers it.
- **SPI change in Phase 6 / P6-03 (R21).** Two additions for Dino Telephone. (1) `Transition.submissions: List<SubmissionRecord>` (`chainIndex, stepNo, authorGuestId, kind, text, strokes, fill, placeholder, timedOut, entriesPerChain, submittedAt`): `apply` hands each record to `SessionRecorder.submissionAccepted` before the round records and before a finish of the same transition. The recorder queues it as **critical** (retried until it succeeds, never dropped, D44); with one writer in queue order, a session's submissions are stored before its `FINISHED` status, so the album endpoints never see a finished session with missing entries. Table `game_submission` (Flyway `V10`, `docs/telephone-design.md` §12.2). (2) `GameContext.host`: the room's current host. `ActiveSession.host` is set when the countdown completes and updated by `onHostChanged(code, newHost)` from the room's `HostChanged` event, so host-only module commands (`REVEAL_NEXT`/`REVEAL_PREV`) need no database read per call. The test game gained `TEST_HOST_ONLY` and `TEST_SUBMIT`; `SessionServiceTest` covers the host change and the record order.
