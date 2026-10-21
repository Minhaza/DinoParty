# DinoParty — Architecture

Status: design document; Phases 1–4 are implemented (Phase 4 = shared game engine, `docs/game-engine.md`, exercised by a test-scope game only; Draw & Guess and Telephone are not built). Requirements source: `docs/MASTER_PROMPT.md`. Open decisions: `docs/decisions-and-risks.md`.

## 1. System overview

```
 Browser (React/Vite, Cloudflare Pages)
   │  REST  https://api.<domain>/api/v1/**      (Bearer guest token)
   │  WSS   wss://api.<domain>/ws  → STOMP       (token in CONNECT header)
   ▼
 Caddy (HTTPS, WSS upgrade, security headers)         ── Oracle Always Free ARM64 VM
   ▼
 Spring Boot modular monolith (single instance)
   ├── PostgreSQL (durable: guests, rooms, sessions, results)    [Flyway-managed]
   └── In-memory active room state (authoritative; lost on restart — documented)
```

Redis is **not** a dependency. Ports exist (`RoomStateStore`, `RateLimiter`, `EventPublisher`) with in-memory adapters so Redis can be added later. A Redis adapter alone does **not** enable multi-instance gameplay (room mailboxes, timers and sockets are process-local).

## 2. Repository layout

```
DinoParty/
├─ AGENTS.md
├─ README.md                      (Phase 1)
├─ .env.example                   (Phase 1)
├─ docs/                          architecture, game-rules, api-contracts, implementation-plan, testing, deployment
├─ backend/                       Maven project (Java 21)
├─ frontend/                      Vite + React + TS
├─ deploy/                        compose.dev.yml, compose.prod.yml, Caddyfile, backup scripts
└─ .github/workflows/             ci.yml (+ deploy later)
```

## 3. Backend

### 3.1 Module boundaries (packages under `com.dinoparty`)

Single Maven module, strictly layered packages; boundaries enforced by **ArchUnit** tests. Each module exposes a small `api` surface; other modules must not touch its `internal` packages.

| Module | Responsibility | May depend on |
|---|---|---|
| `common` | error model, `Clock`/`IdGenerator` beans, validation helpers, rate-limit port | — |
| `guest` | `GuestIdentity`, `GuestSession`, token issue/hash/verify, profile edit, `AuthenticatedGuest` principal (the Spring Security filter lives in `web`, next to the problem+json entry point); defines the port `ProfileEditGuard` (profile lock, §3.8) | `common` |
| `room` | room lifecycle, codes, membership, capacity, ready, host transfer, settings, room mailbox, room status transitions (called by `session`), game availability (`GameCatalog`), profile lock (`RoomProfileLock` implements `guest.ProfileEditGuard`), idle-room sweep (`OrphanRoomCleanup`); defines the ports `GameDirectory` (game name, bounds, settings) and `MemberPresence` | `common`, `guest` (never `engine`/`session`/`history`, ArchUnit) |
| `engine` | game SPI (`GameModule`, `GameDefinition`, `CommandSpec`, `GameContext`), registry, phase identity, deadline book + `DeadlineScheduler` port, action gate, score ledger/ranking, turn order, rematch votes — pure, no Spring Web/JPA | `common` |
| `session` | Phase 4: one room's game lifecycle (start countdown, `ActiveSessions`, finish/abort/rematch/restart recovery, `lastSession`); defines ports `SessionEventSink`, `SessionRecorder`, `EndedSessionLookup`; bridges the registry to rooms (`RegistryGameDirectory` implements `room.GameDirectory`) and serves game discovery (`GameDiscovery`) | `common`, `guest`, `room`, `engine` |
| `games.drawguess` | Draw & Guess rules, scoring, stroke model; freezes its word pool at start through `words` (WP-02) | `engine`, `drawing`, `words` (api, never `words.persistence`) |
| `games.telephone` | chain allocation, phases, album reveal | `engine`, `drawing` |
| `drawing` | shared stroke model, validation/limits, bounded stroke buffer, (de)serialization | `common` |
| `words` | WP-01 built-in word catalog: categories, difficulties, locales, counts and filtered word reads (read-only reference data; `words.persistence` is private). WP-02 word pool: `WordPoolService` (selection, filters, de-duplication, start check), frozen `WordPool` (drawing), providers `BuiltInWordProvider` (the catalog) and `CustomWordProvider` (implemented by `wordpacks`) | `common` |
| `wordpacks` | WP-03 room-scoped custom word packs (D34): pack and entry storage (`wordpacks.persistence` is private), host-only management, TXT/CSV import parser and analyzer (preview/confirm by digest + pack version), `PackWordProvider` implementing `words.CustomWordProvider`; REST in `web.WordPackController` | `common`, `room` (api: `RoomService.accessOf`/`activeAccess`), `words` (api) |
| `realtime` | STOMP config, auth interceptor, destination conventions, envelope, sequencing, presence tracking (implements `room.MemberPresence`), command routing, maintenance sweeps; implements `SessionEventSink` | `room`, `session`, `engine`, `guest` |
| `history` | persistence of `game_session`, `game_round`, `game_submission`, `game_result`; implements `SessionRecorder`/`EndedSessionLookup`; results/album reads; restart recovery SQL | `common`, `session`, `engine` |
| `web` | REST controllers, DTOs, exception mapper, OpenAPI | all service APIs (never entities) |
| `health` | `/api/v1/health` + Actuator customizations | `common` |

Rules: controllers and STOMP handlers contain **no** business logic; they validate, map to commands, and call services. JPA entities never leave `*.persistence`. Game modules never import `room`, `session`, `realtime`, `web` or JPA.

### 3.2 Room runtime and concurrency

- **Phase 4 supersedes the `RoomRuntime` actor sketched here** (decision D14): the room mailbox below stays a keyed lock, and the game state lives in an `ActiveSession` owned by `session` and touched only inside that mailbox. Full design: `docs/game-engine.md` §2–§5. The bullets in this list describe the original intent and remain true for *serialization* and *commands*.
- `RoomRuntime` = one per active room, holding the authoritative `RoomState` and (when playing) the `GameState`.
- Each runtime owns a **mailbox**: a serialized command queue executed on virtual threads (Java 21) with one-at-a-time draining per room (e.g. a per-room `Semaphore(1)`-style drain loop). All of these enter as commands: REST join/leave/settings/start, STOMP actions, disconnect/reconnect, timer expirations.
- Handlers return a `CommandResult` containing: new events (public and private), persistence intents, and scheduling intents (deadlines). The runtime dispatches after state is committed. State machines are pure functions where possible (`(state, command, clock) → (state', events)`) for fast unit tests.
- `RoomRegistry` (`ConcurrentHashMap<RoomCode, RoomRuntime>`) creates/looks up runtimes. Room creation checks code uniqueness against the DB unique index (insert-then-retry).
- A `Clock` bean is injected everywhere; tests use a manual clock and a fake scheduler.
- Idle runtimes (CLOSED, or no connections for N minutes) are evicted.
- **Phase 3 implementation:** membership and the ready flag stay DB-backed (`room_participant.ready`, migration `V3`); `RoomService` publishes `RoomEvent`s (after commit, inside the mailbox) and exposes `inRoom(code, action)` so realtime work (presence, snapshots, command handling, sequence numbers) runs in the same per-room serialization. Presence, sequence counters and duplicate-action windows are in-memory in `realtime`.
- **Phase 2 implementation:** membership is still DB-backed. `RoomMailbox` serializes commands per room code (keyed lock, reference-counted), each command runs in one transaction holding the room row lock (`SELECT ... FOR UPDATE`), and the partial unique index above guards cross-room races. The in-memory `RoomRuntime` replaces the DB reads when realtime state arrives (Phase 3/4).

### 3.3 Room state machine

`WAITING → STARTING → PLAYING → RESULTS → (rematch) WAITING`, any state `→ CLOSED`.
- `STARTING`: short server countdown (3 s) after host start; cancellable only by player count falling below minimum. Full transition table, guards and effects: `docs/game-engine.md` §2.1.
- Join allowed only in `WAITING` (reject `ROOM_IN_PROGRESS` otherwise; spectators out of MVP scope).
- Capacity enforced inside the mailbox, so it cannot be bypassed by concurrent joins.
- Host privileges (`settings`, `start`, `kick`, `close`) are checked against the authenticated guest id, never a client-sent id.

### 3.4 Game engine SPI (summary — superseded by `docs/game-engine.md` §1.3)

The interface below is the original sketch; the refined SPI (`init`/`handle`/`onDeadline`/`onPlayerStatus`/`view`/`outcome`, `Transition` with phase, audiences, score deltas and record intents, `CommandSpec`) is in `docs/game-engine.md`.

```java
public interface GameModule {
  GameDefinition definition();                          // key, name, min/max players, setting schema, defaults
  GameState init(GameContext ctx, GameSettings settings, List<Player> players);
  Transition handle(GameState state, PlayerAction action, GameContext ctx); // validates; returns events, deadlines, score deltas
  Transition onDeadline(GameState state, DeadlineId id, GameContext ctx);
  Transition onPlayerDisconnected(GameState s, PlayerId p, GameContext ctx);
  Transition onPlayerReconnected(GameState s, PlayerId p, GameContext ctx);
  Snapshot snapshot(GameState state, PlayerId viewer);   // viewer-specific, no secrets for others
  GameResult finish(GameState state);
}
```

`GameContext` provides clock, RNG (seedable), word source, and settings. `Transition` = `{GameState next, List<OutboundEvent> events, List<DeadlineRequest> deadlines, boolean finished}`. Modules are Spring beans discovered by `GameRegistry`; adding a game = new module package + registration + frontend plugin (see §9 / `docs/fake-artist-proposal.md`). The engine owns: phase ids, deadline scheduling (via room mailbox), action-ID dedup, stale-action rejection, rematch/vote handling, score aggregation and final result handoff to `history`.

### 3.5 Security

- Spring Security stateless; custom `GuestTokenAuthenticationFilter` for REST, `ChannelInterceptor` for STOMP `CONNECT`/`SUBSCRIBE`/`SEND`.
- Token: 32 bytes from `SecureRandom`, base64url; server stores SHA-256 (hex) in `guest_session.token_hash` (unique). Sessions have `expires_at`, sliding `last_seen_at`, revocation.
- Authorization on `SUBSCRIBE`: a guest may subscribe to `/topic/rooms/{code}` only if a member of that room; `/user/queue/**` is implicitly principal-scoped.
- Authorization on `SEND`: `@MessageMapping` handlers resolve `Principal`→guest→room membership→role (host/drawer/etc.) inside the room mailbox.
- Rate limits are **fixed windows**, not token buckets: `RateLimiter` port, implemented in memory by `common.InMemoryRateLimiter` (one counter per key and window; single instance). Protected: guest creation per client IP (`POST /guests`), room join per guest, STOMP connects and authentication failures per client, STOMP commands per connection (`commandLimitPer10s`), every game command type per guest (`CommandSpec` rate/burst, a fixed one-second window of `max(burst, ceil(rate))`), word-pack writes and imports. A burst can straddle two windows; accepted for this phase.
- Validation: Jakarta Bean Validation on DTOs and STOMP payload classes; hard size limits via `WebSocketTransportRegistration` (message size, send buffer) and domain limits (points per batch, strokes per round).
- CORS and STOMP `allowedOriginPatterns` from environment allowlist. No cookies, so no CSRF surface; CSRF disabled deliberately and documented.
- Secrets only from environment; `.env.example` has placeholders.

### 3.6 Persistence

PostgreSQL, Flyway (`db/migration/V1__…`), `ddl-auto=validate`. UUID primary keys (generated app-side, UUIDv7 if library permits, else v4). Timestamps `timestamptz`.

| Table | Key columns / notes |
|---|---|
| `guest_identity` | `id`, `nickname`, `avatar_key`, `avatar_color`, `created_at`, `updated_at` |
| `guest_session` | `id`, `guest_id` FK, `token_hash` UNIQUE, `created_at`, `last_seen_at`, `expires_at`, `revoked_at` |
| `game_definition` | `key` UNIQUE (`draw-guess`, `telephone`), `name`, `min_players`, `max_players`, `enabled`, `default_settings` JSONB |
| `room` | `id`, `code` UNIQUE (case-normalized), `game_key` FK, `host_guest_id` FK, `status`, `max_players`, `settings` JSONB, `created_at`, `closed_at` |
| `room_participant` | `id`, `room_id`, `guest_id`, `joined_at`, `left_at`, UNIQUE(`room_id`,`guest_id`); partial UNIQUE(`guest_id`) WHERE `left_at IS NULL` (one active room per guest). The host is `room.host_guest_id`; there is no separate role column |
| `game_session` | `id`, `room_id`, `game_key`, `status` (`RUNNING/FINISHED/ABORTED/INTERRUPTED`), `end_reason`, `settings` JSONB, `roster` JSONB, `seed`, `started_at`, `finished_at`, `session_number`; partial UNIQUE(`room_id`) WHERE `RUNNING` |
| `game_round` | `id`, `session_id`, `round_no`, `started_at`, `ended_at`, `end_reason`, `score_deltas` JSONB, `meta` JSONB (game-specific, e.g. drawer, word; written at round end) |
| `game_submission` | `id`, `session_id`, `round_id` NULL, `author_guest_id`, `kind` (`TEXT`/`DRAWING`), `chain_index` NULL, `step_no` NULL, `text` NULL, `payload` JSONB/`bytea` NULL, `timed_out`, `submitted_at` |
| `game_result` | `id`, `session_id`, `guest_id`, `nickname`, `rank`, `score`, `detail` JSONB (no FK to `guest_identity`, D21) |
| `word_category` | `key` PK (stable, `animals`, `food-drinks`, …), `sort_order` UNIQUE, `active` (WP-01, `V5`) |
| `word_category_label` | PK(`category_key`, `locale`), `label` — localized category labels |
| `word_catalog_entry` | `id` UUID (stable: `md5('dinoparty-word:'‖locale‖':'‖text)`), `locale`, `display_text`, `category_key` FK, `difficulty` (`EASY/MEDIUM/HARD`), `active`; UNIQUE(`locale`, text without spaces/hyphens); seeded by `V6` |

Phase 4 persistence rules (what is written when, sync vs async, failure handling, restart recovery): `docs/game-engine.md` §8–§9; migration `V4` creates these four tables.

Notes: live room state is **not** persisted every tick; the DB is updated at lifecycle boundaries (room create/close, session start/finish, round end, telephone submission, results). Persistence runs after the mailbox commits state, via an `@Async`/virtual-thread writer; failure to persist history must not crash the live game (logged + retried; documented).
Indexes: unique on `room.code`, `guest_session.token_hash`; FK indexes; `game_result(session_id, rank)`.
Old data cleanup: scheduled job purges expired sessions and old closed rooms (retention configurable).

### 3.7 Configuration

`application.yml` + profiles `dev`, `test`, `prod`. All secrets/URLs via env vars (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `CORS_ALLOWED_ORIGINS`, `APP_PUBLIC_URL`, `TOKEN_TTL`, …) documented in `.env.example`. JSON logging in prod; Actuator health/info/metrics (metrics not publicly routed).

### 3.8 Game registry and shared platform services (P7-01)

**Ownership.** A game is described in exactly one place: its `GameModule`'s `GameDefinition`, which carries the key, player bounds, commands and events, the presentation metadata (`GameMetadata`: name, tagline, description, how-to-play steps, `iconKey`) and the host settings (`SettingsSchema` of typed `SettingField`s: `int` with an optional auto value, `bool` with optional restricted options, `enum`, `list` of category keys or UUIDs). `GameRegistry` collects the modules in key order and is the authority for which games exist. The `game_definition` row only decides **availability** (`enabled`) and anchors the foreign keys; its `name`, bounds and `default_settings` columns stay for the Phase 2 schema and are not read (the startup check still fails on differing bounds; `DrawGuessIT`/`TelephoneIT` compare the stored defaults with the module's).

```
GameModule.definition() -> GameRegistry -> RegistryGameDirectory (session) -> room.GameDirectory -> GameCatalog / RoomService (validation, defaults, bounds)
                                       -> GameDiscovery (session) + GameCatalog.enabledKeys() -> web.GameController -> GET /api/v1/games[/{key}] -> frontend (P7-02)
```

- **Settings.** Room create/update validates through `GameDirectory.resolveSettings`, i.e. the module's schema; error texts and `fieldErrors` (`settings.<key>`) are those of the former `room.SettingsSchema`, which no longer exists. A module's `*Settings.from` reads the bounds the schema is built from (drift tests `DrawGuessSettingsSchemaTest`, `TelephoneSettingsSchemaTest`).
- **Game API** (api-contracts §2.6): public, read-only, mapped from the definitions; disabled games are listed with `enabled:false`; unknown keys are `404 GAME_NOT_FOUND`.
- **Wiring.** `GameCatalog` resolves `GameDirectory` lazily (`ObjectProvider`): the modules depend on the word pools, which depend on rooms.
- **Extensibility check.** `GameKeyLiteralTest` fails when platform code outside `com.dinoparty.games` names a game key (one allow-listed exception: the Telephone-only album endpoints in `web.SessionController`). `GameApiIT` shows the test-scope `TestGameModule` being discovered from its module and availability row alone.

**`RoomDto.lastSession`.** `SessionService` keeps, per room and inside the mailbox, the newest session that is no longer running: set when a game finishes (`FINISHED`, finish reason) or is aborted (`ABORTED`, abort reason), cleared when the next session starts running, dropped when the room closes. When it is unknown in memory (after a restart) it is read once through `EndedSessionLookup` (implemented by `history.SessionHistory`: the newest non-`RUNNING` `game_session` row), which is how `INTERRUPTED`/`SERVER_RESTART` reaches clients. A failed read is not cached.

**Profile lock.** `GuestService.updateProfile` validates the fields, then runs the change through the `guest.ProfileEditGuard` port. `room.RoomProfileLock` finds the guest's room, enters its mailbox, refuses with `409 INVALID_PHASE` while the room is `STARTING` or `PLAYING`, and otherwise commits the change inside the mailbox, where every room status change also happens, so a start cannot interleave with a rename. It depends on repositories and the mailbox only (no `RoomService`, no bean cycle).

**Idle-room sweep.** `room.OrphanRoomCleanup` closes `WAITING` rooms in which, for `app.room-orphan-ttl` (default 1 h), nothing showed life: room created, a member joined, or a member's newest `guest_session.last_seen_at`; and no member `isLive` (connected, inside the disconnect grace, or with a closed socket whose presence update is still queued). Members are removed (freed for other rooms) and `ROOM_CLOSED {reason:"IDLE"}` is published. Each room is re-decided inside its mailbox under a row lock, so the sweep is idempotent and loses to a concurrent join or reconnect. Rooms with a game (`STARTING`/`PLAYING`/`RESULTS`) are never candidates. `RealtimeMaintenance` triggers it every `app.realtime.orphan-sweep-interval` (default 5 min) on its single-threaded fixed-delay scheduler; the first pass runs one interval after startup, not at startup, because restart recovery has just returned every room to `WAITING` and no client has reconnected yet. Playing a game is not itself a sign of life: a member of a room older than the TTL who has not called `GET /guests/me` within it must reconnect within that first interval after a restart, or the room is closed.

## 4. Real-time (WebSocket/STOMP) design

### 4.1 Transport and destinations

- Handshake: `GET /ws` (SockJS **not** used; native WebSocket; STOMP over it). Heartbeats 10 s/10 s.
- Auth: STOMP `CONNECT` header `Authorization: Bearer <token>` → `Principal(guestId)`. Invalid → `ERROR` frame and close.
- Client → server (`/app/…`): `/app/rooms/{code}/command` with a typed command envelope (single mapping + `type` dispatch keeps contract in one place; high-frequency `DRAW_STROKE` uses the same route).
- Server → clients:
  - Public: `/topic/rooms/{code}` (events safe for all room members).
  - Private: `/user/queue/rooms/{code}` (word choices, drawer word, telephone prior entry, personal errors/acks, snapshots).
  - Pre-room errors: `/user/queue/errors`.

### 4.2 Envelope

```json
{ "type": "PHASE_STARTED", "roomId": "uuid", "roomCode": "ABCD12",
  "ts": "2026-01-01T12:00:00.000Z", "seq": 42, "stream": "ROOM",
  "actionId": "client-uuid|null", "payload": { } }
```

- `seq`: monotonically increasing **per stream**: `ROOM` stream is per room; `PRIVATE` stream is per (room, recipient). Clients detect gaps and call `GET /rooms/{code}/snapshot` (or send `REQUEST_SNAPSHOT`) to resync.
- `ts`: server time; the client derives a clock offset for countdown rendering. **Deadlines are sent as absolute server timestamps** (`deadlineAt`) plus `serverNow` in snapshots.
- Commands carry a client-generated `actionId` (UUID) and the `phaseId` they target; the server deduplicates by `(guest, actionId)` over a bounded window and rejects stale `phaseId`.

### 4.2.1 Phase 3 realtime components (`com.dinoparty.realtime`)

| Component | Role |
|---|---|
| `WebSocketConfig`, `HandshakeGuard`, `WebSocketSessionRegistry` | `/ws` endpoint, Origin allowlist, handshake rate limit, first-frame check, ability to close a socket on purpose |
| `StompSecurityInterceptor` | CONNECT authentication (token header removed immediately), SUBSCRIBE/SEND authorization, safe ERROR frames |
| `SubscriptionRegistry`, `OutboundSubscriptionGuard` | records membership-checked subscriptions; stops delivery to members who left |
| `RoomEventBridge`, `RoomEventPublisher` | maps `RoomEvent`s to envelopes; per-stream gapless `seq`, published in order |
| `PresenceTracker`, `RealtimeMaintenance` | connected/disconnected state, grace period, host transfer via `RoomService.leave(..., TIMEOUT)`, stale-session cleanup |
| `CommandController`, `CommandRouter`, `ActionDeduper` | single `/app/rooms/{code}/command` entry, validation, rate limit, duplicate detection, dispatch to `RoomService` |
| `SnapshotService` | viewer-specific snapshots built inside the room mailbox (REST and STOMP) |

`realtime` depends on `room`, `guest` and `common` only (ArchUnit-enforced); `web` uses its `RoomDto`/`SnapshotService`.

Phase 4 changes in `realtime` (design: `docs/game-engine.md` §3–§5): `CommandRouter` validates game commands against the registry's `CommandSpec`s (type, typed payload, per-type byte cap and rate limit) and, while a session exists, authorizes them against the in-memory roster instead of the database; `PresenceTracker` forwards connection changes to `session` and picks the in-game grace; a `SessionEventSink` implementation turns engine events into published envelopes (public, private, private-to-set); `SnapshotService` adds the game part. `realtime` then also depends on `session` (and `engine` for the specs).

### 4.3 Connection/presence lifecycle

1. Client authenticates via REST, ensures room membership (REST join), opens STOMP, subscribes to both destinations, then requests/receives a `ROOM_SNAPSHOT` (private) and starts applying public events with `seq > snapshot.seq`.
2. `SessionConnected/Disconnected` events feed presence into the room mailbox; disconnect starts the grace timer; reconnect cancels it, emits `PLAYER_RECONNECTED`, and sends a viewer-specific snapshot including **current drawing state** (stroke buffer) and current phase/deadline.
3. Multiple tabs: latest connection wins for private queue; older connection receives `SESSION_REPLACED` and is closed (decision documented in api-contracts).

### 4.4 Drawing traffic

Normalized coordinates (0–1 in a logical 1600×1000-style aspect box; aspect ratio fixed), points batched client-side ~50 ms (`DRAW_STROKE` with `strokeId`, `tool`, `color`, `size`, `points[]`, `final`). Server validates drawer/phase, point count (≤ 200 per batch), strokes per round, payload bytes, rate; appends to the bounded stroke buffer; rebroadcasts to others (not echoed to sender). `UNDO`/`CLEAR` mutate the buffer and broadcast. Snapshot carries the full compacted buffer.

## 5. Frontend

### 5.1 Structure (`frontend/src`)

```
app/            router, providers (QueryClient, Theme), route guards, error boundary
pages/          Home, Games, GameInfo, CreateRoom, JoinRoom, Lobby, DrawGuessSession, TelephoneSession, Results, Settings, NotFound
features/
  guest/        profile form, avatar picker, useGuest hooks
  rooms/        create/join forms, lobby, player list, host controls
  games/        game registry (frontend plugin map: key → lazy session component + info)
  drawguess/    canvas board, toolbar, hint, guess feed, scoreboard
  telephone/    text/draw submission, waiting, album viewer
  drawing/      shared canvas engine (pointer events, DPR scaling, stroke batching, replay, undo) — pure utilities + hook
  realtime/     STOMP client wrapper, connection manager, event dispatcher
api/            typed REST client (fetch), query keys, DTO types (mirror docs/api-contracts.md)
stores/         Zustand stores (see 5.2)
components/ui/  shadcn/ui primitives
components/     Avatar, PlayerCard, Countdown, Scoreboard, Notifications, Tooltip, Dialog wrappers
lib/            utils, time-sync, env, a11y helpers
styles/         Tailwind v4 theme tokens (CSS variables, light/dark)
```

### 5.2 State ownership (no duplicated server truth)

| Concern | Owner | Notes |
|---|---|---|
| Guest identity (profile) | TanStack Query (`/guests/me`); token in a small `authStore` (Zustand, persisted) | Query is source of truth for profile |
| Room metadata before WS | TanStack Query (`/rooms/{code}`) | |
| Active room + presence + lifecycle | `roomStore` (Zustand), seeded from snapshot, mutated only by realtime events | Server snapshot replaces store wholesale |
| WS connection | `connectionStore` (status, attempt, lastSeq per stream) | |
| Game-specific state | `drawGuessStore`, `telephoneStore` | Reset on `GAME_STARTED`/snapshot |
| Drawing state | `drawingStore`/refs: committed strokes + in-progress stroke; canvas rendered imperatively | Not React state per point |
| Preferences | `prefsStore` (persisted): theme, sound, reduced motion override | |

Realtime flow: one `RealtimeClient` instance (module singleton managed by a `RealtimeProvider`) → `dispatcher` maps `type` → store reducers. Subscriptions are created in an effect keyed by room code and **always** unsubscribed in cleanup; reconnection re-subscribes then resyncs via snapshot; sends carry `actionId` to make retries idempotent.

### 5.3 Styling and a11y

Tailwind v4 with design tokens in CSS variables, shadcn/ui components themed to the DinoParty palette, `prefers-reduced-motion` honored, visible focus rings, keyboard-operable controls, `aria-live` regions for phase/guess announcements. Canvas has a text alternative for state (hint, current drawer).

## 6. Environments and delivery

- Dev: `docker compose -f deploy/compose.dev.yml up -d db` for Postgres; backend `./mvnw spring-boot:run` (profile `dev`), frontend `npm run dev` (Vite proxy `/api`, `/ws` → 8080). Full-stack compose profile added in Phase 8.
- CI (GitHub Actions): backend unit + IT (Testcontainers), frontend lint/typecheck/test/build, Playwright (later), Docker image build (linux/amd64 + arm64).
- Prod (decision D49): one Render Docker web service (`deploy/Dockerfile`, `render.yaml`) serves the SPA, `/api` and `/ws` from one origin, with Render PostgreSQL; details in `docs/deployment.md`. The earlier Caddy + Cloudflare Pages plan is parked.

## 7. Observability

Structured logs with `roomCode`, `guestId`, `sessionId` MDC fields; Micrometer metrics (active rooms, connections, command latency, dropped events); health: liveness, readiness (DB). Optional dev-only OTLP export (backend, browser errors/fetch, PostgreSQL logs) through an OpenTelemetry Collector to a local Aspire Dashboard (`OTEL_ENABLED`, default off; see `docs/observability.md`).

## 8. Known limitations (documented to users/devs)

- Active games live in memory; a backend restart ends them: sessions become `INTERRUPTED` and rooms return to `WAITING` with their members (decision D13, `docs/game-engine.md` §9). Single instance only, and the old process must be stopped before a new one starts.
- Single instance only; no horizontal scaling.
- No accounts; guest identity is per browser token.
- `guest_session.last_seen_at` is renewed only by `GET /guests/me` (and at creation). A REST-only member who never opens a socket and never calls `/me` for `app.room-orphan-ttl` can lose an idle `WAITING` room to the sweep.

## 9. Extensibility: adding a game

Backend: a new `games.<key>` package implementing `GameModule`, whose `GameDefinition` declares everything about the game (bounds, commands/events, `GameMetadata`, `SettingsSchema`), plus a Flyway data migration inserting its `game_definition` availability row. No platform list needs editing: rooms validate settings and the game API publishes the game from the registry (§3.8; `GameKeyLiteralTest` guards this). Frontend: a `features/<key>/` folder and one entry in the game plugin registry; game metadata and settings forms come from `GET /games` once P7-02 lands. The engine, rooms, realtime layer and persistence stay unchanged. The shared `drawing` and engine `VoteService` modules are reusable by new games. Worked example: `docs/fake-artist-proposal.md`.
