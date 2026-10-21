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

Playwright E2E (since P5-05): `frontend/e2e/*.e2e.ts`, run with `npm --prefix frontend run e2e`. It needs the backend on `:8080` with its database (for example `scripts/dev.sh`, or the compose `db` plus `spring-boot:run -Dspring-boot.run.profiles=dev`); the config starts the Vite dev server unless one is already running. Chromium only, one worker, `retries: 0`. Runs in CI since P7-03 (the `e2e` job in `.github/workflows/ci.yml`; see "P7-03 verification record"). Multi-user runs use one browser context per player; `e2e/support.ts` provides the canvas pixel probes, mouse/touch (CDP) drags and a WebSocket "network cut" built on `routeWebSocket`.

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
- `GET /games` and `GET /games/{gameKey}` are not implemented (game choices are hard-coded in `features/rooms/gameConfig.ts` and seeded in `game_definition`); the frontend game-info page is still a placeholder. *(Done in P7-01; see the P7-01 verification record.)*
- No `Idempotency-Key` handling: join and leave are naturally idempotent instead.
- `PATCH /guests/me` does not yet return `INVALID_PHASE` while a game is running (no games exist). Add it with the room status check in Phase 4. *(Done in P7-01; see the P7-01 verification record.)*
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

## Phase 4 verification record (shared game engine, backend)

Run on Windows 11, JDK 21.0.3, Docker Desktop (Testcontainers PostgreSQL 16). Frontend not touched and not re-run. Not run: Linux/CI.

| Check | Result |
|---|---|
| `./mvnw verify` in `backend/` | passed: 130 unit tests, 133 integration tests, Spotless check |

Coverage by layer:

- **Engine units** (`TurnOrderTest`, `ScoreLedgerTest`, `RankingTest`, `DeadlineBookTest`, `GameRegistryTest`): shuffle is a permutation for n = 1..12 over 50 seeds; skip-left; ledger totals equal the sum of deltas for random sequences; overflow and non-roster rejection; competition ranking 1,2,2,4; deadline ordering, replacement, phase drop; registry startup failures (duplicate key, command, event) and catalog checks.
- **Lifecycle and races with a manual clock, no sleeps** (`SessionServiceTest`, 44 tests): transition table rows including cancel, abort, finish, rematch, results timeout, shutdown, room close; `NOT_HOST`, `NOT_ENOUGH_PLAYERS`, idempotent start; stale, duplicate, spurious and after-abort wake-ups; an action exactly at the deadline is `STALE_ACTION` and 1 ms before is accepted; a phase changed by an action cancels the old deadline; the watchdog; a 200-thread storm racing a deadline (every command accepted or stale, ledger sum equals accepted commands); module faults (undeclared audience aborts with `INTERNAL_ERROR`, exception in `handle` is `SERVER_ERROR` and the game continues); secrecy of events and per-viewer snapshots; no leaked sessions or armed timers.
- **Room status machine** (`RoomLifecycleIT`): allowed edges, forbidden edges leave the room unchanged, `ROOM_IN_PROGRESS` on join during a game, ready cleared on reset.
- **Real STOMP with the test game** (`GameEngineIT`, `GameEngineSessionIT`): start, countdown, phases, scoring, finish, results rows, rematch and a second session; a secret is seen only by its owner until a later public event reveals it (three sockets); gapless room `seq`; non-host start, single-member start, outsider command, stale, missing `phaseId`, duplicate `actionId`, module rejection, other game's command, oversize payload, per-type rate limit; 8 concurrent starts give one countdown; leaving during the countdown cancels it; reconnect snapshots in `WAITING`, `PLAYING`, `RESULTS` and after the reset; REST start/kick/close with non-host rejections and idempotency; 40 accepted game commands executed 0 Hibernate statements.
- **PostgreSQL** (`SessionPersistenceIT`): one `RUNNING` session per room (database constraint); rounds and results written and read through `GET /sessions/{id}/results`; `RESULTS_NOT_READY`, `NOT_IN_ROOM`, 401; **database outage** simulated by renaming `game_result` mid-session: the recorder call returns immediately, the session stays `RUNNING`, results appear after the table returns; **restart recovery** (`RUNNING` becomes `INTERRUPTED/SERVER_RESTART`, `PLAYING` room becomes `WAITING`, ready cleared, results endpoint answers 200 with empty standings, second run is a no-op).
- **Architecture** (`ArchitectureTest`): engine depends only on `common`; games only on the engine; session reaches sockets and storage only through ports; history does not depend on transport or rooms; `room` does not know the engine; no `Instant.now`, `new Random` or `Math.random` in engine/session/games.

### Not verified / known limitations (Phase 4)

- **No frontend work**: task 4.8 is not done. The lobby Start button reaches a real backend start, but no production game module exists, so `draw-guess` and `telephone` answer `GAME_NOT_FOUND`.
- **Process kill not exercised**: recovery is tested by seeding the rows a dead process leaves; the outage test renames a table rather than stopping the database.
- **Not implemented** (the first four were done in P7-01): `GET /games*`, `RoomDto.lastSession`, the `PATCH /guests/me` lock, orphan-room sweep, typed payload binding, the 320 KiB frame cap (D20), the snapshot replay-equivalence property test, `Retry-After` on `RESULTS_NOT_READY`.
- **`GAME_FINISHED.resultsReady` is always `false`**; clients poll `GET /sessions/{id}/results`.
- **Per-type rate limiting** is a fixed one-second window, not a token bucket, so a burst can straddle two windows.
- **Single instance only**: recovery treats every `RUNNING` row as orphaned.
- **Timing-dependent tests** use real 600 ms rounds in `GameEngineIT`; they passed on every full run here but are the likeliest to flake on a slow CI machine. The no-database-statements test waits for background activity of earlier tests to settle.
- **Test contexts**: the Phase 4 ITs add one cached Spring context; the test profile now sets `hikari.minimum-idle=1` so the shared PostgreSQL container does not run out of connections.

### Phase 4 final integration verification (2026-10-14, all branches merged)

Run on `main` + this branch (docs only), Windows 11, JDK 21.0.3, Node 22, Docker Desktop.

| Check | Result |
|---|---|
| `./mvnw verify` in `backend/` | passed: 130 unit, 133 integration, Spotless |
| `npm ci`, `lint`, `typecheck`, `test -- --run`, `build` in `frontend/` | passed: 20 files, 113 tests; Vite build succeeded |

Checklist mapped to existing tests (no new defects found, no code changed):

| # | Requirement | Evidence |
|---|---|---|
| 1 | Only the host starts | `SessionServiceTest`, `GameEngineIT` (`NOT_HOST` for non-host and non-member, REST and WS) |
| 2 | Minimum players; ready | `NOT_ENOUGH_PLAYERS` tested; `ready` is advisory by design (`[C-4]`, D15), so it is deliberately **not** required |
| 3-4 | One session per room; concurrent starts | DB constraint in `SessionPersistenceIT`; 8 concurrent starts give one countdown in `GameEngineIT` |
| 5 | Authenticated and validated actions | outsider, wrong game, oversize, rate-limit and missing `phaseId` cases in `GameEngineIT` |
| 6 | Duplicate/stale action IDs | duplicate `actionId` and stale `phaseId` tests; 200-thread storm in `SessionServiceTest` |
| 7-8 | Timers exactly once; timer vs action races | manual-clock tests: replaced/duplicate/after-abort wake-ups, exactly-at-deadline |
| 9-10 | Room scoping, secrecy, reconnect snapshots | three-socket secrecy test; per-viewer snapshots in `WAITING`/`PLAYING`/`RESULTS`/after reset |
| 11-12 | Durable results; rematch | `SessionPersistenceIT`, rematch loop with second session in `GameEngineSessionIT` |
| 13 | Test-only game proves lifecycle | test game module drives start, phases, finish, rematch over real STOMP |

Not verified in this pass: Playwright E2E (not present until Phase 8), Linux/CI, a live process kill, and the browser UI against a real game (no production game module exists). Full Draw & Guess and Telephone Drawing gameplay is **not** implemented.

## Phase 5 verification record (P5-02, Draw & Guess backend)

Run on Windows 11, JDK 21.0.3, Docker Desktop 29.8 (Testcontainers PostgreSQL 16). `./mvnw verify` (Spotless check, unit tests, integration tests): **BUILD SUCCESS, 198 unit tests and 137 integration tests, 0 failures, 0 skipped.** Frontend not touched and not re-run. Not run: Linux/CI, Playwright.

New in P5-02 (68 unit, 4 integration):

| Test | Count | Covers |
|---|---|---|
| `drawing.CanvasBufferTest` | 11 | `[DG-14]` every budget at its boundary, ordering/style, implicit close, undo/clear refunds, rejection leaves the buffer equal |
| `drawing.StrokeBatchTest` | 3 | `DRAW_STROKE` schema validation (no client data echoed) |
| `games.drawguess.DrawGuessRulesTest` | 22 | `[DG-3]` hint, `[DG-4]` normalization, `[DG-5]` close, `[DG-12]`/`[DG-13]` examples and rounding, `[DG-15]` word pool (sizes, charset, blocklist, no repeats, exhaustion, seeded determinism), settings defaults |
| `room.DrawGuessSettingsSchemaTest` | 2 | module settings bounds = room schema (drift guard) |
| `session.DrawGuessSessionTest` | 27 | module in the real engine with a manual clock: registration/start, player-count bounds, drawer rotation and skipped turns, word-choice authorization and auto-pick, secrecy of word/choices in events/snapshots/errors/round records, guess classification and scoring, timer expiry and exactly-at-deadline `STALE_ACTION`, hints, strokes/undo/clear, canvas full, concurrent correct guesses, reconnect snapshots, drawer/guesser leaving (D24, D25, D30, `[C-1]`), final standings and rematch, maximal snapshot < 256 KiB, random-interleaving invariants (secrecy, ledger = deltas) |
| `session.SessionServiceTest` (+1) | 1 | engine SPI: private events of a new phase carry its `phaseId` (`GameContext.nextPhaseId`) |
| `architecture.ArchitectureTest` (+2) | 2 | `drawing` depends on `common` only; `games.drawguess` on engine/drawing/common only |
| `realtime.DrawGuessIT` | 3 | real STOMP: full 3-player game with role errors, stale/duplicate, secrecy across sockets, refresh mid-round, concurrent correct guesses, drawer leaving, results REST with round meta and standings detail, rematch and new session; maximal canvas (12 000 points) snapshot delivered under 256 KiB; module `PAYLOAD_TOO_LARGE` as private `GAME_ERROR` and transport cap on the errors queue; catalog defaults = module defaults |
| `realtime.RealtimeCommandIT` (+1, 1 changed) | 1 | registered Draw & Guess commands answer `INVALID_PHASE` while no game runs (they were `INVALID_ACTION` before the module existed) |

### Not verified / known limitations (Phase 5 backend)

- No frontend, browser or Playwright verification (Antigravity tasks 5.10–5.13).
- Not built: shared contract JSON fixtures (5.8), the snapshot/event replay-equivalence property test, a 10-player fan-out load test with mailbox hold times, and an explicit "zero Hibernate statements per Draw & Guess command" assertion (the engine-level test covers the shared code path only).
- Engine semantics inherited from Phase 4: rate limits are a fixed one-second window of `max(burst, ceil(rate))` (e.g. 40 strokes/s, 5 guesses/s) and payload caps count characters of the serialized payload, not bytes.
- A wrong guess containing the answer inside a sentence is broadcast as is (design §13 H4).
- The 8 KiB stroke cap and 64 KiB frame cap are respected by construction; a 223 KB snapshot frame is delivered in the IT on this machine only.

## Phase 4 Frontend coverage (game-session infrastructure)

Vitest + Testing Library (`npm --prefix frontend test`):

| Area | Tests |
|---|---|
| Server clock sync | `src/lib/serverClock.test.ts` (5 tests): offset tracking from server timestamps, monotonic drift correction, target remaining time and seconds calculation, clock reset |
| Game session store | `src/stores/gameSessionStore.test.ts` (8 tests): wholesale snapshot application, game started/aborted/finished reductions, rematch vote aggregation, server restart interrupted session detection and clearance, scores and standings synchronization |
| Server countdown presentation | `src/features/session/useServerCountdown.test.ts` (4 tests): high-frequency smooth ticker, mm:ss formatting, screen reader milestone announcements (60s, 30s, 15s, 10s, 5s..1s), urgent threshold styling (<= 5s), completion callback trigger |
| Reusable session components | `src/features/session/SessionComponents.test.tsx` (9 tests): `ServerCountdown` badge and ARIA attributes, `PhaseIndicator` polite announcements and round badges, `PlayerRoster` avatars/nicknames/host crown/turn indicator/kick dialog, `Scoreboard` medal rankings, `RematchControls` vote toggle and ready ratio, `GameStartingOverlay` 3-second countdown, `GameInterruptedBanner` dismissal and server restart banner |
| Game session page & routing | `src/pages/GameSessionPage.test.tsx` (5 tests): loading and room connection states, non-member preview gate, playing stage presentation with phase/countdown, results phase with standings and rematch voting, player leave dialog and navigation |

### Phase 4 Frontend verification record

Run on Windows 11, Node 22.

| Check | Result |
|---|---|
| `npm --prefix frontend run lint` | passed: 0 errors, 0 warnings |
| `npm --prefix frontend run typecheck` | passed: strict TypeScript compilation (`tsc -b`) |
| `npm --prefix frontend test -- --run` | passed: 20 test files, 113 tests |
| `npm --prefix frontend run build` | passed: Vite production build succeeded |


## Phase 5 integration verification record (P5-04, Draw & Guess multiplayer)

Run 2026-10-14 on Windows 11, JDK 21.0.3, Node 22, Docker Desktop 29.8, on `main` (P5-02 + P5-03 merged) plus this branch. No backend or contract change was needed; the integration defects were all on the client side.

### Defects found and fixed (frontend)

| Defect | Effect before the fix | Fix |
|---|---|---|
| Drawer echo suppression only worked for batch 0 (`applyDrawingUpdated` was called without the guest id) | The drawer's own strokes got their points duplicated from batch 1 on | Echoes are recognized by `strokeId` (contract §3.9); points of a local stroke are never re-applied |
| Snapshot strokes were marked "batch 999999 processed" | After a reconnect, the rest of a stroke that was still open in the snapshot was dropped | Closed strokes ignore late batches; open ones keep accepting (stream `seq` already dedupes) |
| `sendCommand` threw while the socket was down, uncaught inside the 50 ms batch timer | Errors on every tick; drawing broke after a reconnect | Commands return `false` instead; the stroke continues under a new `strokeId` from the last sent point |
| Rejected batches (`INVALID_ACTION`/`VALIDATION_FAILED`/`RATE_LIMITED`/`PAYLOAD_TOO_LARGE`) were only shown as an error banner | Drawer kept sending a dead stroke; canvas diverged from the server | Command errors are matched by `actionId`: restart the stroke or show "canvas full", and resync the canvas from a snapshot; stale-phase races are silent |
| No client per-stroke limit | A long gesture was rejected at 2000 points | Strokes are split at 2000 points (≤ 200 per batch); near-duplicate points are dropped |
| An in-progress stroke kept sending after `DRAWING` ended | A burst of `STALE_ACTION` errors | Stroke stops when the phase or role changes |
| Drawer fallback `order[0]` while the drawer was unknown | A wrong player could get drawer controls | Only the server's `drawerGuestId` grants drawer UI |
| `WORD_CHOICES` were cleared if processed before their `PHASE_STARTED` | Drawer could miss the choices | Choices are kept when their `phaseId` matches |
| Chat survived into the next round; timestamps were client time | Live view differed from the snapshot (the server clears chat per round) | Chat is cleared at `WORD_CHOICE`; envelope `ts` is used |
| `REMATCH_VOTES_UPDATED` was merged instead of replaced | Votes of disconnected members lingered | The full tally replaces the old one |

### Automated checks

| Check | Result |
|---|---|
| `./mvnw -f backend verify` | passed: 335 tests (198 unit, 137 integration), 0 failures, Spotless clean (baseline; no backend change) |
| `npm --prefix frontend run lint` / `typecheck` / `build` | passed |
| `npm --prefix frontend test` | passed: 33 files, 180 tests (new: `stores/roomStore.drawguess.test.ts`, P5-04 blocks in `drawGuessStore.test.ts` and `DrawCanvas.test.tsx`) |

Server-side authorization (drawer identity, `IS_DRAWER`, `NOT_DRAWER`, stale/duplicate actions, secrecy, timers, rematch) is covered by `DrawGuessIT` and `DrawGuessSessionTest` from P5-02 and was re-run in the backend verify above.

### Browser verification (real backend, three independent Chromium contexts)

A manual Playwright script (not committed; Phase 8 adds the E2E suite) ran against `spring-boot:run` with a throwaway PostgreSQL container and the Vite dev server. All checks below passed:

| Scenario | Result |
|---|---|
| Host starts; all three players land on the game page; only the drawer gets word choices | passed |
| Word is visible to the drawer only, during `DRAWING` and after guesser refresh | passed |
| Player A draws, players B and C see pixel-identical canvases; undo is synchronized | passed |
| Guesser drags on the canvas: nothing is sent or drawn anywhere | passed |
| Raw STOMP `DRAW_STROKE` from a non-drawer: `GAME_ERROR NOT_DRAWER`, nothing drawn | passed |
| Guesser refresh mid-round restores the full canvas | passed |
| Drawer refresh mid-round restores word and canvas; new strokes reach everyone | passed |
| Wrong guess appears as chat for the others; correct guess makes the guesser a solver, scores update on every client, the word is not broadcast | passed |
| Solver disconnected (session replaced, socket closed) and reconnected: canvas and solver state restored | passed |
| Guesser offline (`setOffline`) while the drawer draws, back online: catches up | passed |
| Drawer goes offline in the middle of a stroke and keeps drawing: the stroke continues, and every client canvas equals the server canvas after resync | passed |
| `DRAWING` timer (30 s) expires: round ends, word revealed to everyone | passed |
| Drawer rotates over three rounds, each starts with an empty canvas; all-guessed ends a round early | passed |
| Full game to results: identical standings for every player | passed |
| Host rematch: all players back in the lobby, room `WAITING`, scores and ready cleared; second game starts clean | passed |
| No uncaught page errors in any context | passed |

### Not verified / known limitations

- **Offline emulation does not drop the socket.** Chromium's `setOffline` left the existing WebSocket open, so the server never marked the guesser `DISCONNECTED`. That check failed as a test artifact, and grace-period expiry (`LEFT`) was not exercised in the browser. The backend covers it in `DrawGuessSessionTest`/`DrawGuessIT`.
- During a reconnect mid-stroke, the drawer's local preview can briefly double-draw the points buffered while offline. Its canvas matches the server again after the next snapshot.
- Only the client splits strokes at the 2000-point limit. The live canvas budget (12 000 points / 300 strokes) is enforced only by the server, and the client reacts to `PAYLOAD_TOO_LARGE`.
- The drawer canvas keeps per-point preview data in Zustand and redraws everything on each change (P5-03 design). This is fine at the budget sizes, but it is not the imperative-only rendering `AGENTS.md` asks for.
- The word-choice overlay has no `role="dialog"`. (Seven P5-03 files that were not Prettier-clean, which would fail CI's `format:check`, were reformatted in this change.)
- Not run: Linux/CI, Firefox/WebKit, mobile touch input, the committed Playwright suite (Phase 8).

## Phase 5 integration verification record (P5-05, Draw & Guess)

Run 2026-10-14 on Windows 11, JDK 21.0.3, Node 22.18, Docker Desktop 29.8, Playwright 1.64 (Chromium 156), against `main` at `4174511` plus this branch. No production code changed: the backend and frontend behaved correctly; the changes are tests, the E2E harness and docs.

### Defects found and fixed

| Defect | Effect | Fix |
|---|---|---|
| `DrawGuessIT.aFullGame…` kept awaiting events on the host's socket after dropping it. The play order is random, so in about a third of runs the host was the guesser who "refreshed" | Baseline `verify` failed (`ConditionTimeout` at the `ROUND_ENDED` wait); 1 of 2 isolated reruns failed too. A debug dump showed the server had delivered every event to the live sockets: a test defect, not a product bug | The test re-points `host` to the new socket when the host is the one reconnecting; 6 consecutive `DrawGuessIT` runs green afterwards |

No product defect was found in this pass.

### Automated tests executed

| Check | Result |
|---|---|
| `./backend/mvnw -f backend verify` (before the fix) | **failed**: 198 unit passed; 137 IT, 1 error (flaky `DrawGuessIT`, above) |
| `./backend/mvnw -f backend verify` (after) | passed: 198 unit, 139 integration, 0 failures, 0 skipped, Spotless clean |
| `DrawGuessIT` alone, 6 consecutive runs after the fix | 6 × 5 tests passed |
| `npm --prefix frontend run format:check` / `lint` / `typecheck` / `build` | passed (build prints the existing > 500 kB chunk warning) |
| `npm --prefix frontend test` | passed: 33 files, 180 tests |
| `npm --prefix frontend run e2e` | passed 4 of 4 runs (1 single + `--repeat-each=3`), about 60 s each |

New backend ITs (`realtime.DrawGuessIT`, real STOMP):

- `scoresComeOnlyFromTheServerAndADrawerReconnectKeepsTheRound`: the drawer's socket dies mid-`DRAWING` and comes back (`PLAYER_DISCONNECTED`/`RECONNECTED`); the same phase, word and drawer role remain, and a new stroke is acked. A `SET_SCORE` command → `INVALID_ACTION`. A correct guess carrying forged `scoreDelta`/`totalScore`/`guestId`/`order` is scored by the server for the sender only (100–500). Resending its `actionId` → `DUPLICATE_ACTION`. The word again under a new id becomes restricted solver chat: no second `GUESS_ACCEPTED`, and the unsolved guesser never sees the word.
- `aRematchStartsFreshAndLeavesThePreviousResultsUntouched`: 2-player game to `GAME_FINISHED`; stored results = broadcast standings; rematch → new session with zero scores, round 1 and an empty canvas; after scoring in the new session, `GET /sessions/{first}/results` is byte-identical and the room has exactly one `FINISHED` session.

### Backend checklist → evidence

| Requirement | Evidence |
|---|---|
| Only the active drawer can draw | `DrawGuessIT` full game (`NOT_DRAWER` for stroke/undo/choose), `DrawGuessSessionTest` DG14/DG2; E2E guesser drag sends nothing |
| Only eligible guessers can guess | `IS_DRAWER`, `INVALID_PHASE` after the round, solver re-guess → restricted chat (new IT), D25 disconnected guesser |
| Secret word never leaks | DG18 tests, IT socket-level secrecy before `ROUND_ENDED` and for unchosen words, new IT; E2E checks the guesser's DOM before/after refresh |
| Scores not client-controllable | new IT (no score command, forged fields ignored); ledger = deltas invariant in `DrawGuessSessionTest` |
| Concurrent correct guesses | `C7_…` (5 threads) and the IT's 2-socket race: distinct orders, one `ROUND_ENDED` |
| Duplicate actions | `DUPLICATE_ACTION` for a stroke (existing) and a correct guess (new), no extra points |
| Stale actions after transitions | `STALE_ACTION` for a late `CHOOSE_WORD` (IT) and a guess exactly at the deadline (unit) |
| Timer authoritative | DG9/DG12 with a manual clock; E2E round 2 ends by the server's 30 s deadline with nobody guessing |
| Disconnects don't corrupt the game | DG10/D24/D25/DG16/C1 unit tests; IT guesser refresh and drawer socket drop; E2E network cut and both refreshes |
| Rematch fresh, old results intact | DG11 unit, new IT, E2E rematch (new session, zero scores, empty canvas, old results unchanged) |

### Browser scenarios executed (committed suite `frontend/e2e/drawguess.e2e.ts`)

Two players against the dev backend (`spring-boot:run`, compose PostgreSQL) and Vite: a desktop Chrome context (mouse, 1280×860) and a Pixel 7 context (touch via CDP touch events, DPR 2.625, 412 px wide). The drawer is random, so each run drew one round with the mouse and one with touch. Steps, all asserted in every run:

1. Lobby: both ready up, the host starts, both land on `/play`.
2. Only the drawer gets word choices, sees the word and the toolbar. The guesser sees the hint, never the word text. The countdowns on both screens are within 2 s of each other.
3. Red, then blue crossing strokes: both canvases show blue at the crossing (stroke order kept) and red elsewhere. The mobile backing store is scaled by its DPR.
4. Guesser drags on its canvas: neither canvas changes.
5. Mobile layout: no horizontal scroll, the canvas is in the viewport and wider than 250 px.
6. Guesser reload mid-`DRAWING`: canvas restored, word still hidden.
7. Network interruption: the guesser's STOMP socket is cut on both ends and reconnects are refused. The guesser shows "Reconnecting" and the drawer's scoreboard shows the guesser "Offline". The drawer draws a green stroke meanwhile. After the network is restored, the guesser recovers and its canvas contains the green stroke and the earlier ones.
8. Drawer reload: word, toolbar and canvas restored.
9. Wrong guess appears in the drawer's feed. The correct guess (different case) ends the round on both screens with the word revealed, and the server's scores for both players appear on both scoreboards.
10. Round 2: roles rotate, the other input type draws, the guesser receives it, nobody guesses, and the server timer ends the round (word revealed on both).
11. Results: identical "Final Standings" rows on both screens.
12. Rematch (guest votes, host starts rematch): both screens return to a lobby with ready cleared. The second game starts with a new session, zero scores and empty canvases, and the first session's stored results are unchanged.
13. No uncaught page errors in either context.

### Not verified

- Three or more browsers. A drawer or guesser *leaving* (grace expiry → `LEFT`) in the browser is covered by backend tests only.
- Raw wrong-role STOMP commands from a browser page (P5-04 did this ad hoc; the committed suite only checks that the guesser UI sends nothing).
- Firefox/WebKit, real mobile devices (touch was emulated through CDP in Chromium), axe/keyboard walk-through, Linux/CI (E2E was wired into CI in P7-03).
- Load: 10-player fan-out, mailbox hold times, zero-Hibernate-statements per command (as in P5-02).

### Remaining risks

- E2E relies on a manually started backend and database; *(superseded: the `e2e` CI job was added in P7-03).*
- The E2E pixel checks assume the canvas background `#FFFDF8` and the palette in `coords.ts`; a design change there must update `e2e/support.ts`.
- The known P5-04 limitations still apply: the brief local double-draw after a reconnect mid-stroke, the canvas budget enforced only by the server, per-point preview data in Zustand, and no `role="dialog"` on the word-choice overlay.

## WP-01 verification record (word catalog and database, backend)

Command: `./mvnw -f backend verify` (Windows 11, Java 21, Docker 29.8, Testcontainers `postgres:16-alpine`) — **BUILD SUCCESS**: surefire 223 tests, failsafe 171 ITs, 0 failures, 0 errors, 0 skipped; Spotless check passed.

New tests:

| Test | Covers |
|---|---|
| `CatalogSelectionTest` (unit) | Locale normalization (`EN`, `en_US`, `PT-br`, 3-letter languages), malformed tags rejected with `fieldErrors[locale]`, fallback chain tag → language → `en`; `MIXED` is a filter over all stored difficulties and never a stored value; case-insensitive difficulty parsing; unknown difficulties rejected |
| `WordCatalogSeedIT` | Flyway `V5`/`V6` applied; nine categories in order with English labels, no Random Mix row; ≥ 150 `EASY`, ≥ 150 `MEDIUM`, ≥ 100 `HARD`, every category × difficulty cell populated; [DG-15] format, blocklist, no duplicates or simple plurals; stable md5-derived ids; every legacy word present with a consistent tier (D32); DB rejects duplicates ignoring spaces/hyphens (`ice-cream`, `hotdog`), `MIXED`, unknown categories, bad shapes/case, non-ASCII English and malformed locales; same text allowed in another locale; accented text allowed outside `en` |
| `WordCatalogApiIT` | 401 on all three endpoints without a token; categories/difficulties/counts response contracts (§2.3); default counts equal the database; filtering by category × difficulty (incl. `random-mix`, case-insensitive); service `words(...)` honours the same filters; no response contains a word; 400 `VALIDATION_FAILED` per bad parameter; `en-US`/`de`/`pt-BR` fall back to `en`; an added `fr` catalog is served for `fr-CA`/`FR` with per-category label fallback; inactive words and categories excluded |
| `ArchitectureTest` | `words` depends on `common` only; `words.persistence` private |

Not verified: no frontend or browser check (no UI in scope); gameplay still reads the resource lists, so no game test exercises the catalog yet; word drawability/appropriateness was reviewed by hand, not by players.

## WP-02 verification record (word filtering and game integration, backend)

Command: `./mvnw -f backend verify` (Windows 11, Java 21, Docker 29.8, Testcontainers `postgres:16-alpine`) — **BUILD SUCCESS**: surefire 265 tests, failsafe 179 ITs, 0 failures, 0 errors, 0 skipped; Spotless check passed.

New or changed tests:

| Test | Covers |
|---|---|
| `WordPoolServiceTest` (unit, in-memory providers) | One category; several categories × difficulty; each of `EASY`/`MEDIUM`/`HARD`; `MIXED`; empty filters and Random Mix = every active category; unknown/deactivated category → `VALIDATION_FAILED settings.wordCategories`; custom words never filtered by category/difficulty or missing metadata; combined pool order and `[DG-4]` de-duplication (`Ice-Cream`/`icecream`/`CAT`/`café`/`cafe`); missing, unknown and other-owner packs → `settings.wordPackIds`; unusable entries not counted; `WORD_POOL_TOO_SMALL` with `details {required, available}` (duplicates and empty filters); exactly one draw suffices; frozen against catalog and pack edits; no word in `toString`; draw without repeats, exhaustion fallback, per-seed determinism |
| `DrawGuessWordSelectionTest` (module in the real engine, manual clock) | Too-small pool and invalid settings reject the start with nothing initialized (no status change, event, session, timer, history row) and no word in the error; offered words obey category + difficulty filters over a whole game; combined games; settings and pack edits during countdown/game ignored, `GAME_STARTED.settings` frozen; rematch uses a fresh used set and the current settings; exhaustion repeats instead of stopping; pool words reach only the drawer before `ROUND_ENDED` |
| `SessionServiceTest` (+3) | Engine `prepare` hook: rejection initializes nothing, a crash is `SERVER_ERROR`, settings and setup frozen at host start |
| `DrawGuessRulesTest` | Pool duplicate key = `[DG-4]` correct-guess equivalence (`WordKey`); legacy lists (now test fixtures) well formed; settings parsing for the new keys and every difficulty, lenient fallback |
| `DrawGuessSettingsSchemaTest` (+2) | Room schema accepts every value the module reads (sources, `[]`, Random Mix, nine categories, 10 packs) and rejects malformed ones (case, duplicates, Random Mix combined, non-lists, bad/uppercase UUIDs, too many) |
| `DrawGuessWordSelectionIT` (STOMP + REST, test `CustomWordProvider` bean) | Settings validated on `PATCH` and defaults filled; REST start `409 WORD_POOL_TOO_SMALL` with `details`, room stays `WAITING`; STOMP `START_GAME` → private `GAME_ERROR` with `details`, no words, only to the host; built-in game choices are catalog words of the selected categories/difficulty (checked against PostgreSQL); custom game frozen at start; only the drawer's socket receives `WORD_CHOICES` |
| `WordPoolIT` (PostgreSQL) | Pool size equals `counts` for every category × difficulty and Random Mix (653); inactive words/categories excluded; unknown pack ids are rejected with `settings.wordPackIds` (stored packs: `WordPackPoolIT`) |
| `DrawGuessIT` | `theModuleDefaultsEqualTheCatalogRow` now checks the `V7` defaults; fixed a pre-existing order-dependent flake (when the host was the guesser who reconnected, its new socket never saw round 1's `WORD_CHOICE`, so waiting for the second one timed out) |

Not verified: no frontend or browser check (no UI change in scope; the frontend's `SettingsMap` type does not list string arrays yet, see design §19); custom packs only through test providers here (stored packs: WP-03 below); no load measurement of the start-time pool query (≈ 650 rows, inside the room mailbox).

## WP-03 custom word packs and import (backend)

Contract: api-contracts §2.5, decision D34. Migration `V8__word_packs.sql`. New dependency: Apache Commons CSV 1.14.1 (RFC 4180 parser).

| Test | Covers |
|---|---|
| `WordRulesTest` (unit) | Trim, NFC, whitespace collapse; defaults `Custom`/`MEDIUM`; difficulty case-insensitive, `MIXED` rejected; accents preserved, duplicate key ignores case (`ß`=`SS`, NFKC) but keeps accents; 80 code points (emoji), huge input; control, bidi, zero-width, BOM, private-use, lone surrogate, `<` `>` rejected; ZWJ allowed |
| `ImportAnalyzerTest` (unit) | TXT: blank lines, CRLF/CR, BOM, physical line numbers, accents/scripts, case-insensitive dedupe in file and against the pack, invalid rows without content echo, empty/blank-only, invalid UTF-8, bounded error lists with exact counts, row and byte limits, digest. CSV: BOM, any column order, quotes/commas/escaped quotes, missing optional columns, blank rows, invalid difficulty, blank word, extra cells, bad headers (unknown, duplicate, no `word`, empty cell, none), unterminated quote, header only, multi-line record line number, extension routing |
| `WordPackApiIT` | 401 on every endpoint; CRUD; validation field errors; 20-pack limit; member sees names/counts only and gets `NOT_HOST` for words and every write; non-member `NOT_IN_ROOM`; host transfer moves rights; cross-room isolation (other room's pack → `WORD_PACK_NOT_FOUND`, other room's code → `NOT_IN_ROOM`); no pack word in room or snapshot payloads; add/remove/page words, defaults and duplicates, indexed field errors, 100-per-request and 1 000-per-pack limits (`details`); cascade on room delete |
| `WordPackImportIT` | Preview counts, bounded lists, nothing persisted; confirm stores in file order and reports counts; CSV with BOM/Unicode/quotes round trip; stale confirm after a pack change, different file, forged or empty digest, double confirm, preview of another pack; over-capacity import rejected whole; bad files (extension, empty, headers, quotes, latin-1, no part) → structured errors; 1 MiB accepted, 1 MiB + 1 and > 10 000 rows → 413; imported content never in the logs |
| `WordPackConcurrencyIT` | 6 simultaneous confirms of one preview import once (5 × `IMPORT_STALE`); overlapping imports and adds never duplicate and keep `word_count` equal to the rows; 10 concurrent adds of one word store it once; 30 concurrent creates stop at 20; concurrent adds stop at 1 000 |
| `WordPackRollbackIT` | A unique-key failure on the last insert of an import leaves no entry, count or version change; the same preview then confirms |
| `WordPackPoolIT` | Host's pack feeds the pool (only `[DG-15]`-usable words, `[DG-4]` duplicates removed); another room's pack, a non-host member, a deleted or unknown pack are `settings.wordPackIds`; empty pack → `WORD_POOL_TOO_SMALL`; `combined` merges built-in and pack words |
| `ArchitectureTest` (+3) | `wordpacks.persistence` private; `wordpacks` knows nothing of web/realtime/session/games; `words` never depends on `wordpacks` |

Not verified: no frontend or browser check (no UI in scope); no malware scanning, no antivirus or content moderation of imported words; multipart behaviour is exercised through MockMvc (the container-level limits `spring.servlet.multipart.*` are configured but not hit by a real HTTP client here). Words longer than 20 characters or containing digits are stored but never drawn (`[DG-15]` shape), and no response flags them yet.

## P6-02 verification record (Dino Telephone steps, backend)

Scope: `games.telephone` (`PROMPT`/`DRAW`/`DESCRIBE`); reveal, persistence and album REST are P6-03. Decisions D35–D48 were implemented as recommended.

### Automated tests
- Unit: `ChainAllocatorTest` (`[TP-1]` for n = 3…12, every E ≤ n), `TelephoneSettingsTest` (`[TP-2]`), `TelephoneTextTest` (`[TP-14]`), `PromptPoolTest` (`[TP-3]`), `TelephoneModuleTest` (registration next to Draw & Guess, frozen EASY ∪ MEDIUM pool), `SettingsSchemaTest` (`entriesPerChain`, `allowSkipTimeout` only `true`).
- Session tests with a manual clock (`TelephoneSessionTest`): full 4-, 5- and 8-player games, `[TP-4]` early end and DISCONNECTED/LEFT handling, `[TP-5]` timeout fills, `[TP-7]` finality, `[TP-8]` secrecy (public events, other snapshots), `[TP-9]` drafts/budgets/snapshot restore, `[TP-10]`/`[C-1]` leaving and early end, `[C-6]` staleness, maximal-draft and maximal-source snapshots < 262 144 B, and a seeded random-interleaving invariant walk (12 seeds).
- Real STOMP (`TelephoneIT`): a complete 4-player game where each entry reaches only its next holder, error/authorization cells (wrong phase kind, empty text, already submitted, stale, outsider), private 12 000-point draft restore and delivery of the maximal task, catalog defaults equal `TelephoneSettings.defaults()`.
- ArchUnit rule for `games.telephone`; `RealtimeCommandIT` now treats the Telephone commands as registered.
- `./mvnw -f backend verify`: 228 integration tests plus unit tests, 0 failures, BUILD SUCCESS (Docker PostgreSQL via Testcontainers).

### Defect found and fixed
- `SessionService.apply` evaluated `s.ledger::apply` even for transitions without scores, so any **unscored** game failed at its first phase (`NullPointerException` when the countdown completed). Fixed with a guard; covered by every Telephone test (Telephone is the first unscored game).

### Not verified / limitations
- No reveal yet: the game finishes when the last step ends and the entries are only held in memory (lost with the session) until P6-03.
- Rematch (new shuffle) and a restart mid-game were not exercised for Telephone in this task.
- No frontend or browser verification (backend-only task).

## P6-04 verification record (Dino Telephone multiplayer integration)

Scope: the P6-02 backend steps and the step UI played together over the real STOMP transport; reveal and albums remain P6-03. Flow, acknowledgement table, reconnect matrix and isolation: `telephone-design.md` §15.

### Automated tests
- Backend, real STOMP (`TelephoneIT`, 10 tests): 3-player start refused (`NOT_ENOUGH_PLAYERS`, no session, no task); every holder gets exactly one private `PROMPT` task (never on the room topic) and a snapshot with the server deadline; the `PROMPT` deadline (real 20 s, awaited, no sleep) ends the step `TIME` with the unsent draft or an auto prompt as the next source; two concurrent final submissions end the step exactly once; a refresh mid-`DESCRIBE` restores task, draft and deadline without the chain's prompt, and after submitting the snapshot says `submitted` and a resubmit is `ALREADY_SUBMITTED`; a full game reaches `RESULTS` with empty standings, 4 rounds and no entry content in results or snapshots; a rematch gives a new `sessionId`, new tasks and clean snapshots, and the old `phaseId` is `STALE_ACTION`; plus the P6-02 cases.
- Frontend (Vitest): `telephoneStore.test.ts` (snapshot without the pending submission → retryable error and a late ACK still confirms; watchdog expiry; player copy per error code, server message never shown), `TelephoneStage.test.tsx` (10 s watchdog with fake timers → retry enabled and resync requested), `roomStore.telephone.test.ts` (duplicate private task ignored by `seq`; a late task of the previous session never reaches the rematch session; `GAME_FINISHED` drops task and draft; entering another room resets every game store and stream position).
- Browser (`frontend/e2e/telephone.e2e.ts`, Playwright, 4 contexts: 3 desktop mouse + 1 Pixel 7 touch, against the dev backend on :8080): host picks Dino Telephone in the create-room UI; 3 players join; start; a refresh mid-writing restores the server-held draft; 4 different prompts; each page shows exactly one prompt that is not its own and the 4 assignments are distinct; a refresh mid-drawing restores task and strokes; drawings sent (mouse and touch); every describer sees an inked drawing and no prompt; 4 descriptions; each last-step drawer sees exactly one description, not their own; results on every screen with no entries; rematch → lobby → new game with empty editors and a new session. 4 green runs (1 + `--repeat-each=2` + 1 after formatting), ≈ 40 s each, no page errors.
- Browser, submission recovery (`frontend/e2e/telephone-submit-recovery.e2e.ts`, 4 desktop contexts; page-level WebSocket routes drop chosen STOMP frames): a submit frame that never reaches the server → after the 10 s watchdog "No answer…" appears, the text is kept and a retry is accepted; an `ACTION_ACK` that never arrives → the watchdog's REST resync confirms the entry (waiting screen kept, no retry offered); a lost submit followed by a socket drop → the reconnect snapshot shows "not received", keeps the text and the retry is accepted; the last prompt then moves all 4 to step 2. Added in QA; 3 green runs, ≈ 30 s each.

### Commands run
- `./mvnw verify` (backend): unit 350, integration 234, 0 failures/errors/skips, BUILD SUCCESS (Docker PostgreSQL via Testcontainers).
- `npm --prefix frontend run format`, `format:check` (clean), `lint` (clean), `typecheck` (clean), `test` (44 files, 281 tests, all passed), `build` (success; the existing chunk-size warning only).
- `npx playwright test e2e/telephone.e2e.ts`: passed (see above).
- QA re-run (2026-10-16): `./mvnw verify` unit 350, integration 235, 0 failures/errors/skips; `TelephoneIT` 3 more isolated runs green (≈ 75 s each, with the dev backend running alongside); frontend `test` 281/281, `build` success; full Playwright suite (`drawguess`, `telephone`, `telephone-submit-recovery`) green against the dev backend. A raw-STOMP probe of the live backend confirmed: an outsider's room subscription is refused and its command is `NOT_IN_ROOM`, its snapshot `403`; empty, whitespace-only, control/format-only and 141-code-point texts and non-string `text` are `VALIDATION_FAILED`; > 1 KiB is `PAYLOAD_TOO_LARGE`; a null `phaseId` is `VALIDATION_FAILED`, an unknown or previous-step one `STALE_ACTION`, the wrong command for the step `INVALID_PHASE`; out-of-range, fractional or malformed stroke points, a bad colour, size 0, an unknown tool and a negative `batchNo` are `VALIDATION_FAILED`; a `guestId` in the payload is ignored (progress names the authenticated sender); a bidi override (`U+202E`) is stripped from the stored entry; no entry text appears on `/topic` frames, and each snapshot holds only its own holder's source.

### Not verified / known issues
- `e2e/drawguess.e2e.ts` had been failing on `main` since the layout redesign (#23); it was updated in this task to the new layout, without changing what it asserts: the roster is the `Game session players` list and a disconnected player carries the `Player disconnected` icon (no `Offline` label); below `lg` the spec switches to the `Players (n)` tab for roster, feed and guess input and back to `Game Area` for the stage, dispatching the tab click directly because the round-end and word-choice overlays cover the tab bar. Full E2E suite (`drawguess` + `telephone`) `--repeat-each=2`: 4/4 passed.
- 3-browser verification was replaced by 4 (Telephone's minimum, `[C-1]`); network loss during a submission is covered in the browser by `telephone-submit-recovery.e2e.ts` (dropped frames on one page), not by a real network outage.
- No reveal, albums, axe smoke or load check (P6-03, P6-06).

## P6-03 + P6-05 verification record (Dino Telephone reveal, albums and results)

Scope: the backend reveal, submission persistence and album endpoints (P6-03) and the reveal viewer and results screen (P6-05), done in one branch after D41–D45 were approved. Design: `telephone-design.md` §15.7.

- Backend unit and session (`TelephoneSessionTest`, 39 tests; `SessionServiceTest` +3):
  - T3: the reveal starts with entry (0,0) only, and no other album's prompt is public.
  - Host-only moves (`NOT_HOST`); malformed cursor `VALIDATION_FAILED`; a stale cursor or double click `STALE_ACTION`.
  - PREV across albums, without content, and a no-op at the start; T5: the last NEXT finishes with empty standings.
  - Idle auto-advance at 60 s, and 15 s while the host is disconnected (a host click racing it is stale).
  - A new host drives the reveal; an early end during the reveal.
  - The reveal snapshot holds cursor, `highWater` and only revealed entries.
  - The maximal reveal snapshot (8 players, 32 drawings of 12 000 points) stays < 262 144 B and omits older drawings.
  - Every cell is recorded once with author and fill.
  - SPI: `GameContext.host` follows `onHostChanged`; submissions reach the recorder before the finish.
- Persistence (`TelephoneAlbumsIT`, PostgreSQL, 5 tests): albums answer `RESULTS_NOT_READY` while running, 403 `NOT_IN_ROOM` for other rooms' members, 401 without auth, and 404 `SESSION_NOT_FOUND` for unknown or non-Telephone sessions. A finished session serves albums in step order with roster nicknames, placeholders and fills, and untrusted text verbatim. Missing albums are `ALBUM_NOT_FOUND`; members who joined later may read (D45). An early end gives `complete:false`; aborted sessions have no albums. A submission waits out a database outage, and the finish waits behind it.
- Real STOMP (`TelephoneIT`, 11 tests):
  - A four-player game keeps every entry with its next holder until `ALBUM_REVEALED`, then all 16 entries arrive in reveal order.
  - Host-only control and a stale double click.
  - Albums over REST are locked during the reveal.
  - A reconnect restores cursor and `highWater` without unrevealed albums.
  - Albums are readable after finishing and after the rematch reset.
  - `RealtimeCommandIT` treats `REVEAL_*` as registered.
- `./mvnw verify` (2026-10-16): unit 363, integration 240, 0 failures/errors/skips, Spotless clean.
- Frontend (Vitest, 345 tests in 50 files):
  - `revealStore.test.ts` (20): the store, local browsing, host move lock, stale and `NOT_HOST` handling, snapshot merge and reset rules.
  - `roomStore.telephone.test.ts` (+5): dispatch and resets across finish, rematch, new game and room change.
  - `TelephoneAlbum.test.tsx` (10): text, Unicode, RTL and HTML-like text rendered literally; drawings, eraser included, through the shared renderer; every badge; attribution of players who left with no guest id shown; step order; the timeline; replay skip and reduced motion.
  - `TelephoneRevealView.test.tsx` (13): position label; no unrevealed content; the host sends the server cursor once; finish; non-host restrictions; arrow-key browsing; previous album navigation; `aria-live`; reconnect with an omitted drawing; former-host notice; auto reveal and speed controls; auto reveal pause on previous.
  - `TelephoneResultsScreen.test.tsx` (8): shelf without scores; open, back and replay; retry while not ready; early end; empty; 403; session isolation.
  - `api/telephone.test.ts` (6); `strokeReplay.test.ts` (4); updated `TelephoneStage` and `GameStage` tests.
  - `format:check`, `lint`, `typecheck` and `build` are clean.
- Browser (Playwright, dev backend on :8080): `telephone.e2e.ts`, four players including a Pixel 7.
  - The steps as before, then the reveal opens on album 1's prompt only, and only the host has page controls.
  - A drawing is inked; a reload mid-reveal rejoins the same page; the phone has no horizontal overflow.
  - The host turns all 16 pages to the results; every player sees four albums and no standings, and an album opens with its 4 entries.
  - The rematch starts fresh.
  - 4 green runs after one fix: the test now paces host clicks under the 5/s reveal limit (an unpaced test hit `RATE_LIMITED`). `telephone-submit-recovery.e2e.ts` and `drawguess.e2e.ts` are green.
  - Screenshots were reviewed on desktop and phone; that review made the reveal controls non-sticky, because they covered the newest entry on phones.

Not verified: the axe accessibility smoke and the load check (P6-06), a non-host `REVEAL_NEXT` forged from the browser (covered by STOMP ITs instead), the auto-advance in a real browser (covered by session tests), a real network outage during the reveal, and Linux/CI.

## P7-01 verification record (shared platform and game registry, backend)

Design: `architecture.md` §3.8; contract: `api-contracts.md` §2.6 and "P7-01 contract changes".

### Automated tests

| Test | Covers |
|---|---|
| `engine.SettingsSchemaTest` (11, replaces `room.SettingsSchemaTest`) | defaults in declaration order; merge over defaults; unknown key, wrong type and out-of-range reported together with the former messages; inclusive int bounds, fractions refused; auto value; only-`true` boolean; category and UUID lists (case, duplicates, exclusive `random-mix`, non-list, too many); empty schema; duplicate keys / invalid default rejected at construction |
| `games.drawguess.DrawGuessSettingsSchemaTest` (5, moved from `room`), `games.telephone.TelephoneSettingsSchemaTest` (5) | module bounds = schema bounds, enum options = values the module reads, defaults complete and valid, `[TP-2]` entries 0/4–8, `[TP-5]` only `true` |
| `engine.GameRegistryTest` (+1) | key order, `definition(key)` with metadata and schema |
| `web.GameApiIT` (6) | `GET /games` without auth lists draw-guess, telephone and the test game in key order with metadata and bounds, no schema in summaries; draw-guess and telephone details (schema members per type, omitted members, defaults); unknown key `404 GAME_NOT_FOUND` problem+json; disabled row → `enabled:false` in list and detail, `POST /rooms` `404`; the test-scope `TestGameModule` is discovered from its module alone |
| `architecture.GameKeyLiteralTest` (1) | no game-key literal outside `com.dinoparty.games` except the allow-listed Telephone album endpoint |
| `session.LastSessionTest` (8) | none before a game and during the first countdown; `FINISHED`/`COMPLETED` in `RESULTS` and after the reset; previous one kept during the next countdown and cleared once running; cancelled countdown keeps it; abort → `ABORTED`/`NO_COMPLETED_ROUND`; after a restart read once and cached; never shown while a game runs; dropped on close |
| `session.SessionPersistenceIT` (+1, 1 extended) | after restart recovery, REST room and snapshot show `lastSession {INTERRUPTED, SERVER_RESTART}`; a room without games has `lastSession:null` |
| `realtime.GameEngineSessionIT` (1 extended) | over real STOMP: `lastSession` null while waiting and playing, the finished session in `RESULTS` and after the rematch reset |
| `guest.GuestProfileLockIT` (5) | allowed outside a room, in `WAITING`, and in `RESULTS` after a real game; `409 INVALID_PHASE` for every member in `STARTING` and `PLAYING`, profile unchanged; `400` before `409`; a change queued behind a mailbox command that starts the game waits for the mailbox and is refused |
| `realtime.OrphanRoomCleanupIT` (8) | stale room closed and both members can create new rooms; repeated sweeps change nothing (`closed_at` stable); recent room kept; connected member keeps it; dropped (in grace) and reconnected member keeps it; recent `last_seen_at` keeps it; `STARTING`/`PLAYING`/`RESULTS` never touched; the maintenance scheduler runs it by itself |
| `realtime.PresenceTrackerTest` (+2) | `isLive`: never connected, connected, in grace, grace over; a closed socket whose reconcile is still queued counts as live |

### Defect found and fixed

`OrphanRoomCleanupIT` exposed a window in presence: a closed socket is removed from the connection table at once, but its grace entry is added later by `reconcile`, which waits for the room mailbox, the same mailbox the sweep holds. A member who had been connected for over an hour in an idle lobby and dropped at that instant would have been treated as gone. `PresenceTracker` now counts unreconciled disconnects as live.

### Commands run (2026-10-18)

- `./mvnw verify` (backend): **BUILD SUCCESS**; unit (surefire) 385 tests, integration (failsafe) 260 tests, 0 failures, 0 errors, 0 skipped; Spotless check and ArchUnit green. This covers the Draw & Guess, Telephone, room, engine, STOMP, word-pack and REST suites.
- No frontend file changed, so no frontend command was run.

### Not verified / limitations

- No manual `curl` or browser check against a running backend; the API was exercised through MockMvc and real STOMP clients in the ITs only.
- Rate limiting unchanged (fixed window); only its documentation was corrected (`architecture.md` §3.5). No new rate-limit tests.
- `GET /games*` has no rate limit (public and read-only, like `/health`).
- `last_seen_at` is renewed only by `GET /guests/me`; see `architecture.md` §8.
- The idle-room sweep loads all candidate codes (`WAITING`, older than the TTL) per pass; fine at single-instance scale.
- The frontend still uses its hard-coded game list (`gameConfig.ts`, `GamesPage.tsx`); moving to `GET /games` is P7-02. *(Done in P7-02; see the P7-02 verification record.)*

## P7-02 verification record (dynamic game discovery and selection, frontend)

Contract: `api-contracts.md` §2.6 (consumed as built in P7-01, no contract change). `features/rooms/gameConfig.ts` is gone: game names, taglines, descriptions, player bounds, availability, setting labels, bounds and defaults now come from `GET /games` and `GET /games/{key}`. The frontend keeps only presentation and capability, in `features/games/gameModules.ts`:
- artwork and tone by `iconKey`, with a neutral fallback for unknown icons;
- which game keys it can play;
- which Draw & Guess setting keys its own lobby word panel edits.

### Behaviour

- **Server state.** The TanStack Query cache under `['games']` / `['games', key]` (5 min stale time) is the only copy of the game list; `useGames`, `useEnabledGames`, `useGameDetail` and `useGameName` all read it.
- **Disabled games** are hidden: the existing design has no unavailable state.
- **Enabled games without a frontend module** appear on the games page with fallback art and a "Not available in this version" badge. They have no play button and are not offered when creating a room.
- **Settings form.**
  - `int` fields become selects with values derived from `min`/`max`; the auto value is shown first as "Auto".
  - `enum` fields become selects; `bool` fields become a checkbox.
  - A `bool` with a single accepted option (`allowSkipTimeout`) and any `list` not claimed by a game panel are not shown; they keep their server value.
  - The `*Seconds` key suffix is shown as "N seconds".
- **Default room size.** The only mirrored rule is "8 clamped to the game bounds" (§2.2), in `defaultMaxPlayers`.
- **Errors.** A server `fieldErrors` entry for `maxPlayers` or a rendered `settings.<key>` appears under its field (`aria-invalid`, `aria-describedby`). Others are listed in the form summary.

### Automated tests

| Test | Covers |
|---|---|
| `api/games.test.ts` (5) | list incl. disabled, detail schema/defaults, key encoding + `404 GAME_NOT_FOUND`, unknown setting type dropped, malformed body → 502 |
| `features/games/useGames.test.tsx` (4) | one shared list request; enabled filter; neutral name fallback; detail fetched only for a selected key and once per key |
| `features/games/gameModules.test.tsx` (4) | playable keys, Draw & Guess panel keys, fallback visual and banner for unknown icon keys |
| `features/games/settings/schemaChoices.test.ts` (11) | int choices (small/large ranges keep bounds, default, current; auto first; no out-of-range), labels, read-only values, editable-field filter, player counts, default max players clamping |
| `features/games/settings/SettingsFields.test.tsx` (4) | schema labels/defaults/bounds, typed values for int/bool/enum, Auto + hidden fixed bool, per-field server error |
| `features/rooms/RoomForms.test.tsx` CreateRoomForm (9) | disabled and unplayable games hidden; POST = server defaults + edits; all scalar Draw & Guess settings; game switch resets to the new defaults with one detail request per game; telephone 4–8 players; submit disabled while the detail loads; field error under the field and unmapped error in the summary; `ALREADY_IN_ROOM`; list error retry |
| `features/rooms/__tests__/RoomSettingsForm.test.tsx` (+2) | lobby bounds/fields from the detail; PATCH body and per-field error |
| `pages/GamesPage.test.tsx` (4), `pages/HomePage.test.tsx` (3) | API names, taglines, descriptions, player ranges; disabled hidden; unplayable marked; play opens creation with the game preselected; retry |
| Existing `routes`, `LobbyPage`, `GameSessionPage` tests | now mock `GET /games*`; assertions unchanged |

### Commands run (2026-10-18)

- `npm run format`, then `format:check`: clean. `lint`: clean. `build` (`tsc -b` + Vite): green.
- `npm test` (Vitest): 57 files, 391 tests, all passed. In one earlier full run, `DrawCanvas.test.tsx` "splits a long gesture…" hit the default 5 s timeout under load. It passed alone and in the next full run; it does not touch P7-02 code.
- Playwright `e2e` against a live backend (dev profile, PostgreSQL in Docker): 3/3 passed (`drawguess`, `telephone`, `telephone-submit-recovery`). The telephone scenario creates its room through the new API-driven picker.
- Live `curl` of `GET /games`, `GET /games/telephone`, `GET /games/nope`: responses match the test fixtures; the unknown key answers `404 GAME_NOT_FOUND`.
- Browser check (Chromium, 1280×900, real backend), with screenshots reviewed:
  - home, games page and create page for both games;
  - switching games resets settings (Drawing time 80 → 90);
  - Telephone "Entries per album" offers "Auto" first;
  - a request rewritten to `drawSeconds: 999` shows the server message "must be a whole number from 30 to 180" under Drawing time;
  - the lobby settings save persisted `entriesPerChain: 6`;
  - no console errors besides that deliberate 400.

### Not verified / limitations

- No axe run, no phone-size or dark-theme screenshots, no Firefox/WebKit.
- The disabled and unknown game paths are covered by component tests only; the dev database has no disabled game.
- The games page fetches each enabled game's detail for its description; the summary DTO has none. The details are cached and reused by the create form.
- The `games/:gameKey` info page is still a placeholder, and `HowToPlayModal` copy is unchanged; both could use `description`/`howToPlay` later.
- Possible contract additions that would remove the two client-side conventions above: `defaultMaxPlayers` on the summary and a `unit` on `int` fields. Neither is requested yet.

## P7-03 verification record (E2E in CI, registry assertions)

Scope: the existing three Playwright specs became a CI gate; no gameplay or production code changed. The scenario audit (what is covered where) is in `docs/p7-03-hardening-plan.md` section 1; authorization, wrong-phase and Telephone-secrecy cases stay in the backend ITs and the raw-STOMP probe, not in Playwright.

### Changes

- CI: new `e2e` job (no new workflow, runs on every PR and on pushes to `main`). It starts a `postgres:16-alpine` service, builds the backend jar (`./mvnw -DskipTests package`; tests run in the `backend` job), starts it with `java -jar` and waits for `GET /api/v1/health` to report `UP`, installs Chromium and runs `npm run e2e` (Vite is started by `playwright.config.ts`). On failure it uploads `frontend/playwright-report/`, `frontend/test-results/` (traces, screenshots) and `backend.log` for 7 days. Triage: the trace shows the browser and STOMP frames, `backend.log` the server, Flyway and PostgreSQL side.
- `playwright.config.ts`: `forbidOnly` and the `github` reporter on CI; `reuseExistingServer` only outside CI.
- `telephone.e2e.ts`: the create-room step now checks the live `GET /games` and `GET /games/telephone`: every enabled game has a picker button with its `min–max players`, the chosen game has `aria-pressed="true"` and the other `false`, "Max players" spans the game's bounds, and each `int`/`enum` setting label from the schema is shown.
- `drawguess.e2e.ts`: the 500 ms sleep in "the guesser cannot draw" is replaced by asserting that the guesser's page sent no `DRAW_STROKE` frame (`NetworkSwitch.sentFrames()` in `support.ts`).

### Executed (Windows 11, JDK 21.0.3, Node 22, Docker Desktop, dev backend + Vite)

| Command | Result |
|---|---|
| `npm run format:check`, `lint`, `typecheck` | clean |
| `npm test` | 392 passed (57 files) |
| `npm run build` | success |
| `npm run e2e` | 3 of 3 passed (about 2.5 min) |
| `npm run e2e -- --repeat-each=2` | 6 of 6 passed (about 4.9 min) |

### Not verified

- The new `e2e` CI job has not run yet (needs the PR); its failure-artifact upload has not been exercised.
- `./mvnw verify` was not re-run: no backend file changed.
- Linux, Firefox/WebKit, axe (task 7.2) and mobile emulation (7.3) are unchanged.
- The disabled and unknown game paths remain component-test only: the real registry has no disabled game and no test-only endpoint was added.
- Vite prints `ws proxy error` lines at teardown when the last sockets close; they do not fail the run.

