# DinoParty — Implementation Plan

Phases follow `MASTER_PROMPT.md` §18. Each phase ends with: run tests/builds → fix defects → verify flows → update docs → summarize → list limitations. A task is done only when its acceptance criteria are verified.

Owners: **CC** = Claude Code (backend, architecture, tests, infra/CI, contracts). **AG** = Antigravity (frontend, UI, browser testing). See §10 for the split and handoff rules.

Verification commands (once scaffolded; `<…>` = exact versions chosen in Phase 1):

```
Backend:   ./mvnw -f backend verify                      # unit + IT (Docker needed for ITs)
           ./mvnw -f backend test                        # unit only
Frontend:  npm --prefix frontend ci
           npm --prefix frontend run lint | typecheck | test | build
E2E:       npm --prefix frontend run e2e                 # Playwright, needs backend + db up
Stack:     docker compose -f deploy/compose.dev.yml up -d --build
Health:    curl -fsS http://localhost:8080/api/v1/health
```

## Dependency graph

```
P1 ──► P2 ──► P3 ──► P4 ──► P5 ──► P7 ──► P8
                       └────► P6 ──┘
```
P5 and P6 may run in parallel after P4 (both depend on the engine and the shared drawing module). P7 starts incrementally from P2 on the frontend side but its exit gate is after P5+P6.

---

## Phase 1 — Repository and application foundation

| ID | Task | Owner | Depends | Acceptance |
|---|---|---|---|---|
| 1.1 | Approve decisions D1–D12 | Lead/user | — | Written approval or amendments in `decisions-and-risks.md` |
| 1.2 | Backend scaffold: Maven wrapper, Spring Boot (verified version), Java 21 toolchain, packages per architecture, profiles | CC | 1.1 | `./mvnw -f backend verify` green on Windows and Linux |
| 1.3 | Flyway baseline `V1` (at least `guest_identity`, `guest_session`, `game_definition`; remaining tables may land in the phase that needs them but must exist by P2/P4) + `ddl-auto=validate` | CC | 1.2 | App fails startup on schema drift; migration applies to empty DB |
| 1.4 | Health: `GET /api/v1/health` (DB-aware) + Actuator liveness/readiness | CC | 1.2, 1.3 | Returns 200 `{status:"UP",db:"UP"}`; 503 when DB stopped; tested (unit + IT) |
| 1.5 | Error model (problem+json), CORS allowlist, Spring Security skeleton permitting health and denying others by default | CC | 1.2 | IT: unauthenticated call to protected stub → 401 problem+json; CORS preflight honors env allowlist |
| 1.6 | Frontend scaffold: Vite + React + TS strict, Tailwind v4, shadcn/ui init, Router, TanStack Query, Zustand, Lucide, Vitest + Testing Library, ESLint/Prettier | AG | 1.1 | `lint`, `typecheck`, `test`, `build` all green |
| 1.7 | Frontend shell: routes for all required pages as placeholders **clearly labelled "coming in Phase N"** (no fake functionality), theme tokens, layout, API client with typed `getHealth()` | AG | 1.6 | `/` renders; a "System status" widget calls `/api/v1/health` through the Vite proxy and displays UP/DOWN; component test with mocked fetch |
| 1.8 | `deploy/compose.dev.yml` (PostgreSQL 16 with named volume + healthcheck), `.env.example` | CC | 1.2 | `docker compose up -d db` healthy; backend connects with `.env` values |
| 1.9 | Docs: `README.md` (local setup, commands that actually run), `docs/testing.md` skeleton | CC (backend/infra), AG (frontend section) | 1.2–1.8 | A fresh clone follows README to bootable stack without undocumented steps |
| 1.10 | CI: GitHub Actions running backend verify and frontend lint/typecheck/test/build | CC | 1.2, 1.6 | Workflow green on PR |
| 1.11 | ArchUnit module-boundary test skeleton | CC | 1.2 | Test exists and passes; fails on forbidden import (proved by a temporary violation) |

### Phase 1 exit criteria (the "Definition of Done" for Phase 1)

1. **Bootable backend:** `./mvnw -f backend spring-boot:run` (profile `dev`) starts against the compose PostgreSQL, Flyway migrates, no errors in the log.
2. **Bootable database:** `docker compose -f deploy/compose.dev.yml up -d db` → healthy; data persists across `down`/`up` (named volume).
3. **Working health endpoint:** `curl -fsS localhost:8080/api/v1/health` → `200` JSON with `status`, `version`, `db:"UP"`; with DB stopped → `503` and `db:"DOWN"`. Actuator liveness/readiness reachable locally.
4. **Bootable frontend:** `npm --prefix frontend run dev` serves `http://localhost:5173`; the status widget shows backend health UP; `npm --prefix frontend run build` outputs `dist/`.
5. **Tests/builds:** backend `verify` and frontend `lint`, `typecheck`, `test`, `build` all pass locally and in CI.
6. **Documented local setup:** README prerequisites (JDK 21, Node LTS, Docker), `.env.example`, exact commands, ports, troubleshooting, and Windows notes — validated by following it from a clean clone.
7. **No secrets committed;** `.env` is gitignored; `.env.example` contains placeholders only.
8. **Scope guard:** no gameplay, guest or room features implemented; placeholder pages are labelled honestly.
9. Phase summary and known limitations recorded.

---

## Phase 2 — Guest identity and room management

| ID | Task | Owner | Depends | Acceptance |
|---|---|---|---|---|
| 2.1 | Migrations: `room`, `room_participant`; seed `game_definition` rows | CC | P1 | Migrations apply; Testcontainers IT verifies constraints (unique code) |
| 2.2 | Guest module: create/restore/update, token gen + hash, session expiry, rate limit, nickname validation | CC | 1.5 | Unit tests (hashing, validation); IT: create → `GET /guests/me`; invalid/expired token → 401; no raw token in DB |
| 2.3 | Room module: create, join, leave, settings, capacity, host transfer, code generation, concurrency-safe mailbox | CC | 2.1, 2.2 | Unit tests incl. concurrent join stress (N threads vs capacity); authorization tests (non-host `PATCH settings` → 403); `ROOM_FULL`, `ROOM_IN_PROGRESS`, invalid code |
| 2.4 | REST: `/guests*`, `/games*`, `/rooms*` per `api-contracts.md`, DTOs, OpenAPI | CC | 2.2, 2.3 | REST IT suite green; contract examples in docs match responses |
| 2.5 | Guest onboarding UI (nickname, avatar, color), token persistence, session restore on refresh, Settings page | AG | 2.4 | Component tests; browser check: refresh keeps identity; validation errors shown |
| 2.6 | Games catalog, game info, create room, join room (code + invite link `/join/:code`) pages with real API | AG | 2.4 | Browser check: create room in one browser, join by code and link from another; error states (invalid code, full) |
| 2.7 | Lobby (REST-polled for now): player list, host badge, settings form | AG | 2.4 | Polling via TanStack Query with 2 s interval, replaced in P3; works for 2 browsers |

Exit: two browsers can create/join a room via REST; all REST ITs and frontend tests pass; docs updated.

## Phase 3 — WebSocket lobby and reconnection

| ID | Task | Owner | Depends | Acceptance |
|---|---|---|---|---|
| 3.1 | STOMP config, `/ws`, CONNECT auth interceptor, subscribe authorization, origin allowlist | CC | P2 | WS IT: invalid token rejected; non-member cannot subscribe; cross-origin rejected |
| 3.2 | Envelope, per-stream sequencing, event publisher, presence tracking, grace timers, host transfer on expiry | CC | 3.1 | Unit + WS IT: join/leave/ready/host-changed events with correct `seq`; disconnect → `PLAYER_DISCONNECTED` → reconnect within grace → `PLAYER_RECONNECTED` |
| 3.3 | Snapshot service (viewer-specific) + `REQUEST_SNAPSHOT`, `GET /rooms/{code}/snapshot` | CC | 3.2 | IT: snapshot equals state after event sequence; gap-resync path covered |
| 3.4 | `RealtimeClient` (STOMP), connection store, dispatcher, auto-reconnect with backoff, resubscribe, dedupe by `seq`, listener cleanup | AG | 3.1 (contract) | Vitest with a mock STOMP transport: no duplicate listeners after reconnect/unmount; commands deduped by `actionId` |
| 3.5 | Real-time lobby: ready toggle, host start button (disabled logic), host controls, connection banners | AG | 3.2–3.4 | Browser E2E: 2 browsers see changes immediately; refresh restores lobby; kill backend → banner + auto-recover |
| 3.6 | Contract tests keeping `api-contracts.md` examples and types in sync | CC + AG | 3.2–3.4 | CI job validates sample payloads against shared JSON schemas |

Exit: lobby changes propagate in real time; refresh/reconnect restores state; disconnected-host scenario handled (host transfer).

**Status (verified 2026-10-13; evidence in `testing.md` "Phase 3 verification record"):** 3.1–3.5 done and verified by backend `verify`, frontend lint/typecheck/test/build and a live multi-browser run; two frontend integration defects (duplicate sockets, snapshot dropped after a server restart) were fixed. **3.6 is not done.** Carried forward: committed Playwright E2E (Phase 8), cross-browser/mobile/axe, heartbeat-timeout test, trusted-proxy rate limiting, WS host commands (Phase 4).

## Phase 4 — Shared game engine

| ID | Task | Owner | Depends | Acceptance |
|---|---|---|---|---|
| 4.1 | Engine SPI (`GameModule`, `GameState`, `Transition`, `GameContext`), registry | CC | P3 | Unit tests with a trivial test-only game module (in test scope only) |
| 4.2 | Phase/timer scheduler through room mailbox; deadline dedupe; `Clock` injection | CC | 4.1 | Timer vs action race tests with manual clock: late action → `STALE_ACTION` |
| 4.3 | Action dedupe (`actionId`), stale `phaseId` rejection, rate limiter hooks | CC | 4.1 | Unit tests |
| 4.4 | Turn order utility, score ledger, vote service (rematch), finish → `history` persistence (`game_session`, `game_round`, `game_submission`, `game_result` migrations) | CC | 4.1 | PostgreSQL IT: session persisted with results; persistence failure doesn't crash live game |
| 4.5 | Start flow: `STARTING` countdown, `GAME_STARTED`, rematch loop to `WAITING` | CC | 4.1–4.4 | WS IT with test game: start → finish → rematch |
| 4.6 | Frontend game plugin registry + generic session shell (countdown, scoreboard, reconnect snapshot, phase banner) | AG | 4.5 contract | Component tests; shell shows data from test game fixture |

Exit: a test-scope game runs end to end through the real engine, including reconnect snapshot and rematch.

## Phase 5 — Draw & Guess  (parallel with Phase 6)

| ID | Task | Owner | Depends | Acceptance |
|---|---|---|---|---|
| 5.1 | `drawing` module: stroke model, validators, bounded buffer | CC | 4.1 | Unit tests for limits and undo/clear |
| 5.2 | Draw & Guess module: rotation, word service (original word list), hint logic, guess normalization, scoring, phases, disconnect rules | CC | 5.1 | Unit tests per rule IDs `[DG-*]`; word never in any non-drawer payload (contract test) |
| 5.3 | WS IT: full 3-player game, unauthorized drawing/guessing rejected, rate limits, reconnect mid-round restores strokes | CC | 5.2 | ITs green |
| 5.4 | Canvas engine: pointer events, DPR scaling, normalized coords, batching, replay, undo/clear; pure-function unit tests | AG | 5.1 contract | Vitest canvas utility tests; manual browser check on mouse + touch emulation |
| 5.5 | Draw & Guess UI: toolbar, word choice, hint, guess feed, scoreboard, round summary, results + replay | AG | 5.2–5.4 | Playwright: draw & guess correct word across two contexts; finish and rematch |

## Phase 6 — Telephone Drawing  (parallel with Phase 5)

| ID | Task | Owner | Depends | Acceptance |
|---|---|---|---|---|
| 6.1 | Chain allocator + invariants | CC | 4.1 | Property tests n=3…12, all E: `[TP-1]` invariants |
| 6.2 | Telephone module: phases, private tasks, submissions persistence, timeout placeholders, disconnect handling | CC | 6.1, 4.4 | Unit + WS IT: 4-player full game; each player sees only previous entry |
| 6.3 | Album API + reveal flow (host-driven) | CC | 6.2 | IT: albums contain exactly E entries each |
| 6.4 | Telephone UI: text/draw submission, waiting screen, album viewer, reveal controls | AG | 6.2, 5.4 | Playwright: complete a telephone album with 4 contexts |

## Phase 7 — UI polish, accessibility, mobile

| ID | Task | Owner | Acceptance |
|---|---|---|---|
| 7.1 | Visual identity pass (original dino art, tokens, animation, light/dark) | AG | Design review on screenshots (desktop + mobile) |
| 7.2 | Accessibility: keyboard flow, focus, aria-live, reduced motion, contrast | AG | axe-core run in Playwright with zero serious violations; manual keyboard walk-through |
| 7.3 | Mobile: touch drawing, responsive layouts, viewport/orientation | AG | Playwright mobile emulation passes; manual device check noted |
| 7.4 | Error/empty/loading states, notifications, retry UX for every case in `MASTER_PROMPT` §14 | AG + CC | Checklist ticked with test per case |
| 7.5 | Backend hardening review (limits, logging, metrics) | CC | Security checklist in `testing.md` |

## Phase 8 — Tests, Docker and deployment

| ID | Task | Owner | Acceptance |
|---|---|---|---|
| 8.1 | Fill test gaps from §15; E2E suite for the 8 scenarios | AG (E2E) + CC (backend) | All listed scenarios automated or explicitly justified |
| 8.2 | Backend multi-stage Dockerfile (amd64/arm64), prod compose, Caddyfile, restart policies, limits, healthchecks | CC | `docker compose -f deploy/compose.prod.yml up` runs full stack locally behind Caddy |
| 8.3 | CI: images build/push, E2E job | CC | Green pipeline |
| 8.4 | `docs/deployment.md` for Oracle ARM64 VM + Cloudflare Pages; backups | CC (+AG for Pages) | Dry-run on a fresh VM or documented as unverified |
| 8.5 | Final acceptance walkthrough vs. `MASTER_PROMPT` §19 | Lead | Checklist signed off |

---

## 9. Phase risks and sequencing notes

- Contract first: before a phase starts, CC updates `api-contracts.md`; AG codes against it using MSW/fixtures until the backend slice lands.
- Resist building UI for rules that the server doesn't enforce yet.
- Highest technical risk items to spike early: STOMP private delivery with custom principal (3.1), room mailbox correctness (2.3), canvas DPR/touch (5.4).

## 10. Task division

**Claude Code** — `backend/`, `deploy/`, `.github/`, architecture and contract docs, all backend tests (unit, REST/WS/PostgreSQL ITs), ArchUnit, security, Docker/CI, deployment, code review of AG's PRs for contract conformity.

**Antigravity** — `frontend/`, UI/UX, design system, accessibility, canvas client, browser/E2E tests (Playwright), visual QA on desktop and mobile, frontend docs sections.

**Shared contract surface** — `docs/api-contracts.md`, `docs/game-rules.md`: changed only by CC through a dedicated `docs(contract): …` commit; AG requests changes via a note in the PR or `docs/contract-requests.md`. Neither agent edits the other's directory except for trivially mechanical changes agreed in the PR.
