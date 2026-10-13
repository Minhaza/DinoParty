# DinoParty — Testing

Skeleton created in Phase 1; sections are filled as the phases land. Rules and requirements: `AGENTS.md` §6, plan: `docs/implementation-plan.md`.

## Backend (CC)

| Kind | Naming | Runner | Needs Docker |
|---|---|---|---|
| Unit | `*Test` | surefire (`./backend/mvnw -f backend test`) | no |
| Integration | `*IT` | failsafe (`./backend/mvnw -f backend verify`) | yes (Testcontainers `postgres:16-alpine`, one shared container per JVM via `PostgresTestConfig`) |
| Architecture | `ArchitectureTest` | surefire | no |

Current coverage (Phase 1): health service/probe units, `GET /api/v1/health` (200 and 503), Flyway baseline applied to an empty database, Actuator liveness/readiness, deny-by-default security (401 problem+json, non-health actuator endpoints denied), CORS preflight allow/deny from env allowlist, ArchUnit module and injection rules (violation proven to fail with a temporary forbidden dependency).

## Frontend (AG)

Vitest + Testing Library (`npm --prefix frontend test`). Current coverage (Phase 1): health API client (200, 503-as-DOWN, bad shape, error), system-status widget (UP/DOWN/unreachable), routing and placeholder labelling, theme resolution. Playwright E2E arrives with the phases that have flows to test.

## Phase 1 verification record

Run on Windows 11, JDK 21.0.3, Node 22, Docker Desktop.

| Check | Result |
|---|---|
| `./backend/mvnw -f backend verify` | passed: 8 unit tests, 10 integration tests, Spotless check |
| `npm --prefix frontend run lint` / `typecheck` / `test` / `build` / `format:check` | passed; 13 tests |
| `docker compose ... up -d db` | healthy; Flyway `V1` applied; data survived `down` then `up` |
| `spring-boot:run` (profile `dev`) against compose PostgreSQL | started, migrated, `GET /api/v1/health` → 200 `UP`/`UP` |
| Database stopped | `GET /api/v1/health` → 503 `DOWN`/`DOWN` after ~3 s; recovered to 200 after restart |
| Frontend dev server + Vite proxy to backend | `/api/v1/health` proxied; headless Edge screenshot showed "Backend UP" |

### Not verified / known limitations

- **Linux and CI:** not run. The workflow `.github/workflows/ci.yml` is untested until the first PR runs it. `backend/mvnw` has the executable bit set in git for Linux runners but that was not exercised.
- **`ddl-auto=validate`:** configured, but there are no JPA entities yet, so schema drift cannot fail startup until Phase 2 adds entities. Flyway migrations are applied and tested.
- **shadcn CLI:** `npx shadcn init` could not run (npm registry resolution failed for transitive dependencies). `components.json`, `lib/utils.ts`, the CSS-variable theme and the `Button`/`Card` components were written by hand in the CLI's output format. Later `npx shadcn add` should work once the registry resolves; re-check then.
- **Dependency pins:** `react`/`react-dom` pinned to 19.2.6, `@tailwindcss/vite`/`tailwindcss` to 4.3.0 and `vitest` to 4.x because the newest releases had unresolved transitive dependencies on the registry at the time.
- **`traceId`** in error bodies is a random UUID logged with 500 errors; there is no distributed tracing.
- Browser check was one headless Edge screenshot in dark mode (system theme); no cross-browser, mobile or accessibility (axe) runs.
- Docker-based production images, Playwright E2E and the WebSocket layer are later phases.

## Phase 2 coverage (guest identity and rooms)

Backend (`*Test` = no Docker, `*IT` = Testcontainers PostgreSQL; profile `test` relaxes throttles via `src/test/resources/application-test.yml`):

| Area | Tests |
|---|---|
| Token hashing, nickname/avatar rules | `TokenHasherTest` (known SHA-256 vector); nickname/avatar/color validation through `GuestIT` |
| Authentication | `GuestIT`: create -> `GET /guests/me` restores identity; raw token never stored (DB holds only a 64-char hash); unique opaque tokens; missing/invalid/oversized/non-Bearer token, expired and revoked sessions -> 401 problem+json; sliding expiry renewed by `GET /guests/me`; `PATCH` touches only the caller |
| Throttling | `InMemoryRateLimiterTest` (manual clock), `GuestRateLimitIT` (429 + `Retry-After`) |
| Authorization | `RoomIT`: every room endpoint -> 401 unauthenticated; non-host and non-member `PATCH settings` -> 403 `NOT_HOST`; outsiders only get a preview; a former host loses privileges |
| Rooms | `RoomIT`: create defaults, validation (`GAME_NOT_FOUND`, settings bounds with `fieldErrors`, maxPlayers bounds), `ALREADY_IN_ROOM`, idempotent/duplicate join, case-insensitive code, `ROOM_FULL`, `ROOM_IN_PROGRESS`, `ROOM_CLOSED`, malformed code -> 404, `INVALID_PHASE`, game switch, leave/rejoin, host transfer to the longest-standing player, last leave closes the room |
| Concurrency | `RoomConcurrencyIT`: 24 simultaneous joins into 5 seats -> exactly 4 succeed; same guest joining 12x -> one membership; guest racing two rooms -> exactly one wins; same guest creating 6 rooms -> one; concurrent leaves keep exactly one valid host. `RoomMailboxTest`: per-room mutual exclusion, no leaked slots |
| Settings / codes | `SettingsSchemaTest`, `RoomCodesTest` |
| Architecture | `ArchitectureTest` extended: `guest` and `room` do not depend on `web`/`health`, `common` depends on no module, `*.persistence` packages are private to their module |

Frontend (Vitest + Testing Library, `fetch` mocked by `src/test/mockApi.tsx`): API client bearer header and 401 token reset; guest create/update/validation (local and server `fieldErrors`); join form (format validation, error-code messages, "already in room" link, anonymous gate); create form (request body per game, valid player counts); lobby (player list with Host/You badges, host-only settings editor, preview join, full room, unknown room, leave, save settings, rejected session).

### Phase 2 verification record

Run on Windows 11, JDK 21.0.3, Node 22, Docker Desktop, Edge (headless).

| Check | Result |
|---|---|
| `./backend/mvnw -f backend verify` | passed: 26 unit tests, 44 integration tests, Spotless check |
| `npm --prefix frontend run lint` / `typecheck` / `test` / `build` | passed; 48 tests |
| Manual browser run (two isolated browser contexts, dev backend + Vite proxy, scripted with Playwright outside the repo) | create guest, create room, refresh keeps identity, second browser joins by invite link, host sees guest via polling, host changes max players and guest sees it, unknown code error, host leaves -> guest announced as host with settings editor, last leave -> "Room closed". One desktop-width screenshot reviewed |

### Not verified / known limitations (Phase 2)

- **Playwright E2E is not part of the repo yet** (Phase 8); the browser run above was an ad-hoc script. No mobile, cross-browser or axe accessibility run; keyboard flow and focus order were not manually walked.
- `GET /games` and `GET /games/{gameKey}` are not implemented (game choices are hard-coded in `features/rooms/gameConfig.ts` and seeded in `game_definition`); the frontend game-info page is still a placeholder.
- No `Idempotency-Key` handling: join and leave are naturally idempotent instead.
- `PATCH /guests/me` does not yet return `INVALID_PHASE` while a game is running (no games exist). Add it with the room status check in Phase 4.
- Rate limiting is per `remoteAddr` for guest creation and ignores `X-Forwarded-For`; behind Caddy every client would share one bucket until a trusted-proxy setting is added in the deployment phase. Limits are in memory (single instance).
- No nickname blocklist (A14): only length and a character allow-list.
- The frontend remembers the last room code in `localStorage` only as a "back to my room" hint. No endpoint returns a guest's current room, so after clearing browser storage a guest who is still in a room can only be freed by leaving from that browser; presence-based expiry arrives in Phase 3.
- `PlayerDto.ready/connection/score` are fixed placeholders until Phase 3+.
- Guest sessions are never purged (cleanup job is planned); expired rows stay in `guest_session`.
- CI was not run for this branch; the Linux path is unverified.

## Phase 3 coverage (real-time lobby and reconnection)

STOMP integration tests start the application on a random port (`@RealtimeIntegrationTest`: `RANDOM_PORT` + Testcontainers PostgreSQL) and talk to it with `StompTestClient`, a minimal raw STOMP 1.2 client on `java.net.http.WebSocket`. It is raw on purpose: tests must send malformed frames, forged headers and oversized messages, and read exact `ERROR` frames and close codes. The `test` profile shortens the grace period (1.5 s), sweep (100 ms) and session recheck (200 ms).

| Area | Tests |
|---|---|
| Sequencing and envelopes | `RoomEventPublisherTest` (envelope fields, independent gapless streams, 16 threads x 200 events arrive in `seq` order, offline guests consume no `seq`, failed send does not throw), `RealtimeLobbyIT` (events carry roomId/roomCode/ts/stream, gapless `1..n`, ordering of leave/host-change) |
| Authentication | `RealtimeSecurityIT`: invalid/missing/malformed/oversized/expired/revoked token -> `ERROR UNAUTHENTICATED` and close; frame before `CONNECT` refused; token absent from logs and frames at DEBUG framework logging; revoked session closes a live socket (`SESSION_EXPIRED`); newer connection replaces the older (`SESSION_REPLACED`) without a disconnect event |
| Subscribe/send authorization | `RealtimeSecurityIT`: non-members and members of other rooms cannot subscribe to public or private streams; only the three contract destinations are allowed (wildcards, lowercase codes, other users' queues refused); 8-subscription cap; clients cannot publish to `/topic/**` or any non-command destination; unsupported frames; non-member and cross-room commands answered `NOT_IN_ROOM` without changing state; Origin allowlist at the handshake |
| Secrecy | `RealtimeLobbyIT`: no events of other rooms, private snapshots not seen by other members, members who left stop receiving the public stream (found a real bug: the outbound guard originally inspected the wrong header type) |
| Validation, duplicates, size | `RealtimeCommandIT`: malformed envelopes and payloads -> `VALIDATION_FAILED` without echoing input, reserved/unknown commands -> `INVALID_ACTION`, client-supplied identity/role fields and spoofed STOMP headers ignored, repeated `actionId` not re-applied, same id from another guest is not a duplicate, 70 kB frame closes the connection (1009) |
| Rate limits | `RealtimeLimitsIT` (command flood limited and not executed, per-guest buckets, failed-auth throttling with valid tokens still working), `RealtimeHandshakeLimitIT` (429 + `Retry-After`) |
| Presence | `PresenceTrackerTest` (manual clock: grace start/cancel, replaced socket, reorder-safe reconcile, expiry, retry after failure, member moved rooms), `RealtimePresenceIT` (disconnect -> `PLAYER_DISCONNECTED` -> reconnect -> `PLAYER_RECONNECTED`; grace expiry -> `PLAYER_LEFT TIMEOUT`; host transfer prefers connected players; last player closes the room; REST-only members never time out; 6 flapping reconnects alternate strictly; 8-socket reconnect storm ends consistent) |
| Snapshots | `RealtimeLobbyIT`: STOMP snapshot equals REST snapshot, `seq.room` equals the last public event, `seq.private` equals its own envelope, catching up after a gap yields `snapshot.seq + 1`, snapshot consistent with state after every event |
| Concurrency | `RealtimeConcurrencyIT`: 9 concurrent joins -> 9 `PLAYER_JOINED` in a gapless sequence; 12 joiners into 3 free seats -> exactly 3 events; replaying the public stream after concurrent ready toggles and a leave reproduces the authoritative state; concurrent host leave and joins; 8 sockets x 10 concurrent commands each get exactly one ack |
| Room events and ready | `RoomEventsIT` (`@RecordApplicationEvents`): join/idempotent join, rejected join publishes nothing, ready only on real change and reset on rejoin, leave -> left/host-changed/closed order, settings, membership lookups |
| REST | `RealtimeLobbyIT`: `POST /rooms/{code}/ready` and `GET /rooms/{code}/snapshot` incl. 401/403 `NOT_IN_ROOM`/404/400 |
| Architecture | `ArchitectureTest`: `realtime` does not depend on `web`/`health` or persistence; `common`, `guest`, `room` do not depend on `realtime` |

### Phase 3 verification record

Run on Windows 11, JDK 21.0.3, Node 22.18, Docker Desktop, Edge (headless, driven by `playwright-core`). Not run: Linux/CI.

| Check | Result |
|---|---|
| `./backend/mvnw -f backend verify` | passed: 58 unit tests, 113 integration tests, Spotless check (also passed earlier on the backend branch) |
| `npm --prefix frontend run lint` / `typecheck` / `test` / `build` | passed; 75 tests (3 added in the integration pass) |
| Live multi-browser run: separate browser contexts, real backend + Vite proxy, throw-away scripts outside the repo (**not** committed E2E) | passed after the fixes below; details next |

Live run, observed after the fixes: host and joiner both show "Live"; a join appears in the other browser within milliseconds; ready toggles show in both lists and the host sees "All players ready"; a refresh restores guest and lobby with exactly one open socket and 3 subscriptions; closing the socket from the page reconnects with one open socket and 3 subscriptions, and later events apply once; raw frames sent from a browser socket: malformed JSON and a non-UUID `actionId` -> `VALIDATION_FAILED`, repeated `actionId` -> `DUPLICATE_ACTION`, `START_GAME` -> `INVALID_ACTION`, a 100 kB frame -> close 1009 and the client recovers; SUBSCRIBE to a room the guest is not in -> `ERROR NOT_IN_ROOM` and the socket is closed; closing the host's browser context -> others see "Disconnected" during grace, then after 30 s the host is removed and the longest-standing connected player becomes host; killing the backend shows the reconnect banner and, after restart, every client recovers without a reload and ready toggles propagate again; concurrent ready clicks from two browsers left both views with the same state (compared by eye, not asserted).

#### Integration defects found and fixed (frontend)

1. **Duplicate STOMP connections.** `RealtimeClient.connect()` had no guard and the provider effect calls it repeatedly (StrictMode, token change, `online` event). Each page opened 2-3 sockets, the server replaced and closed them, and other players saw the guest flap to "Disconnected" (observed in the first live run). `connect()` now does nothing while a client is active, a dead client is dropped before backoff, and callbacks of a superseded client are ignored (a late `SESSION_REPLACED` close could otherwise terminate the live client). Unit tests added; they were not run against the unfixed code, the defect itself was observed live.
2. **Snapshot dropped after a server restart.** `ROOM_SNAPSHOT` went through the seq dedupe check, so after a restart (counters restart at 1) the snapshot and every later event were ignored: the lobby showed "Live" but never updated. Snapshots now bypass dedupe/gap checks and replace `lastSeq`, as the contract says. Unit test added; the live restart scenario passes after the fix.

### Not verified / known limitations (Phase 3)

- **Heartbeat timeout** (server noticing a silent client after ~20 s) is configured (`heart-beat:10000,10000` is asserted in `CONNECTED`) but not exercised end to end; tests simulate lost clients by aborting the TCP connection.
- **No committed Playwright E2E** (Phase 8): the browser run used throw-away scripts, Edge only; no Firefox/WebKit, mobile, axe or keyboard walk-through. Cross-origin handshake rejection was tested with a non-browser client sending an `Origin` header, not from a real foreign-origin page.
- **Network interruption was simulated** by closing the socket from the page and by stopping the backend, not by an OS-level cut or the browser `offline` event; heartbeat timeout is still unexercised.
- **Host permissions over WebSocket** can only be partly tested: Phase 3 has no host-only WS command (`START_GAME`, kick, close answer `INVALID_ACTION` for everyone). `NOT_HOST` is enforced and tested on REST (settings, start); WS host commands arrive in Phase 4.
- The lobby "Start game" button calls REST start, which has no game behind it yet.
- `format:check` was not part of my local verification: on this Windows checkout (CRLF) Prettier flags every file, so only CI (LF) is meaningful. CI flagged 11 files; they were reformatted with `prettier --write` and lint/typecheck/tests re-run, but `format:check` itself was not re-run on LF here.
- **Task 3.6** (CI contract tests with shared JSON schemas) is not implemented.
- **Presence is in memory**: after a backend restart nobody is tracked until they reconnect, and members who never reconnect are not timed out. REST-only members (never opened a socket) are by design never timed out.
- **`ROOM_CLOSED` has no recipient yet**: rooms only close when the last member leaves; it becomes observable with host-initiated close.
- **No `START_GAME`, `kick`, `close`** (Phase 4); game commands are answered `INVALID_ACTION`.
- **Rate limits and the handshake limit use `remoteAddr`** (no forwarded headers), like REST: behind Caddy all clients share one bucket until a trusted-proxy setting exists (deployment phase).
- **STOMP `TRACE` logging prints raw headers** including `Authorization`. The default configuration pins the framework loggers to `INFO`; do not enable `TRACE` for `org.springframework.messaging` in production. Tests prove tokens are absent at `DEBUG`.
- Sequence counters and duplicate-action windows are per process and restart at 1 after a restart; clients must replace their remembered `lastSeq` with `snapshot.seq` (documented in the contract).
- Telemetry (spans/metrics) was checked by compiling and by the code path being exercised in tests, not by viewing the Aspire dashboard.
