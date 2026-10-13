# DinoParty — Architecture

Status: design document (no application code exists yet). Requirements source: `docs/MASTER_PROMPT.md`. Open decisions: `docs/decisions-and-risks.md`.

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
| `guest` | `GuestIdentity`, `GuestSession`, token issue/hash/verify, profile edit, `AuthenticatedGuest` principal (the Spring Security filter lives in `web`, next to the problem+json entry point) | `common` |
| `room` | room lifecycle, codes, membership, capacity, ready, host transfer, settings, room mailbox runtime | `common`, `guest`, `engine` (api) |
| `engine` | game SPI (`GameModule`, `GameDefinition`, `GameContext`), registry, phase/timer scheduler, scoring helpers, vote service, snapshot contract | `common` |
| `games.drawguess` | Draw & Guess rules, words, scoring, stroke model | `engine`, `drawing` |
| `games.telephone` | chain allocation, phases, album reveal | `engine`, `drawing` |
| `drawing` | shared stroke model, validation/limits, bounded stroke buffer, (de)serialization | `common` |
| `realtime` | STOMP config, auth interceptor, destination conventions, envelope, sequencing, presence tracking, command routing | `room`, `engine`, `guest` |
| `history` | persistence of `GameSession`, `GameRound`, `GameSubmission`, `GameResult`; results query | `common`, `room`, `engine` |
| `web` | REST controllers, DTOs, exception mapper, OpenAPI | all service APIs (never entities) |
| `health` | `/api/v1/health` + Actuator customizations | `common` |

Rules: controllers and STOMP handlers contain **no** business logic; they validate, map to commands, and call services. JPA entities never leave `*.persistence`. Game modules never import `room`, `realtime`, `web` or JPA.

### 3.2 Room runtime and concurrency

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
- `STARTING`: short server countdown (3 s) after host start; cancellable only by player count falling below minimum.
- Join allowed only in `WAITING` (reject `ROOM_IN_PROGRESS` otherwise; spectators out of MVP scope).
- Capacity enforced inside the mailbox, so it cannot be bypassed by concurrent joins.
- Host privileges (`settings`, `start`, `kick`, `close`) are checked against the authenticated guest id, never a client-sent id.

### 3.4 Game engine SPI (summary)

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
- Rate limits (token bucket via `RateLimiter` port): guest creation per IP, join attempts per guest/IP, strokes, guesses/chat per guest.
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
| `game_session` | `id`, `room_id`, `game_key`, `status`, `settings` JSONB, `started_at`, `finished_at`, `session_number` |
| `game_round` | `id`, `session_id`, `round_no`, `phase`, `drawer_guest_id` NULL, `word` NULL, `started_at`, `ended_at`, `meta` JSONB |
| `game_submission` | `id`, `session_id`, `round_id` NULL, `author_guest_id`, `kind` (`TEXT`/`DRAWING`/`GUESS`), `chain_index` NULL, `step_no` NULL, `text` NULL, `payload` JSONB/`bytea` NULL, `submitted_at` |
| `game_result` | `id`, `session_id`, `guest_id`, `rank`, `score`, `detail` JSONB |

Notes: live room state is **not** persisted every tick; the DB is updated at lifecycle boundaries (room create/close, session start/finish, round end, telephone submission, results). Persistence runs after the mailbox commits state, via an `@Async`/virtual-thread writer; failure to persist history must not crash the live game (logged + retried; documented).
Indexes: unique on `room.code`, `guest_session.token_hash`; FK indexes; `game_result(session_id, rank)`.
Old data cleanup: scheduled job purges expired sessions and old closed rooms (retention configurable).

### 3.7 Configuration

`application.yml` + profiles `dev`, `test`, `prod`. All secrets/URLs via env vars (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `CORS_ALLOWED_ORIGINS`, `APP_PUBLIC_URL`, `TOKEN_TTL`, …) documented in `.env.example`. JSON logging in prod; Actuator health/info/metrics (metrics not publicly routed).

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
- Prod: backend + Postgres + Caddy via `deploy/compose.prod.yml`; frontend on Cloudflare Pages; details in `docs/deployment.md` (Phase 8).

## 7. Observability

Structured logs with `roomCode`, `guestId`, `sessionId` MDC fields; Micrometer metrics (active rooms, connections, command latency, dropped events); health: liveness, readiness (DB). Optional dev-only OTLP export (backend, browser errors/fetch, PostgreSQL logs) through an OpenTelemetry Collector to a local Aspire Dashboard (`OTEL_ENABLED`, default off; see `docs/observability.md`).

## 8. Known limitations (documented to users/devs)

- Active rooms and games live in memory; a backend restart ends them.
- Single instance only; no horizontal scaling.
- No accounts; guest identity is per browser token.

## 9. Extensibility: adding a game

Backend: a new `games.<key>` package implementing `GameModule`, a Flyway data migration inserting a `game_definition` row, and settings schema. Frontend: a `features/<key>/` folder and one entry in the game plugin registry. The engine, rooms, realtime layer and persistence stay unchanged. The shared `drawing` and engine `VoteService` modules are reusable by new games. Worked example: `docs/fake-artist-proposal.md`.
