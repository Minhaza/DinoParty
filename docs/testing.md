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
