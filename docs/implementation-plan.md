# DinoParty — Implementation Plan

Phases follow `MASTER_PROMPT.md` §18. Each phase ends with: run tests/builds → fix defects → verify flows → update docs → summarize → list limitations. A task is done only when its acceptance criteria are verified.

Owners: **CC** = Claude Code (backend, architecture, tests, infra/CI, contracts). **AG** = Antigravity (frontend, UI, browser testing). See §10 for the split and handoff rules.

Verification commands (once scaffolded; `<…>` = exact versions chosen in Phase 1):

```
Backend:   ./mvnw -f backend verify                      # unit + IT (Docker needed for ITs)
           ./mvnw -f backend test                        # unit only
Frontend:  npm --prefix frontend ci
           npm --prefix frontend run format              # Prettier write; then:
           npm --prefix frontend run format:check | lint | typecheck | test | build   # all run in CI
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

**Status (backend, 2026-10-14):** 4.1-4.7 implemented with the deviations listed in `game-engine.md` section 13. **Final verification 2026-10-14 (all branches merged; backend `verify` and frontend lint/typecheck/test/build green; see `testing.md` "Phase 4 final integration verification").** Frontend shell 4.8 landed in PR #10 with fixture/store tests only. **Not done:** live-backend browser check of the shell, the optional orphan-room sweep of 4.7, and part of 4.1 (`GET /games*`, settings-schema move). Evidence: `testing.md` "Phase 4 verification record". Restart recovery and the database outage are tested against PostgreSQL by seeding state; a live process kill was not exercised.

Design: `docs/game-engine.md`; contract: `api-contracts.md` §3.7–§3.8, §4; rules `game-rules.md` `[C-4]…[C-12]`; decisions D13–D23 (`decisions-and-risks.md` §5) must be approved or amended **before task 4.1 starts**. No Draw & Guess or Telephone gameplay is built in this phase; the engine is proven with a test-scope game module.

| ID | Task | Owner | Depends | Acceptance |
|---|---|---|---|---|
| 4.0 | Approve D13–D23; merge the `docs(contract)` commits of this design | Lead/user | — | Written approval or amendments; if D13/D20 change, `game-engine.md`/`api-contracts.md` updated first |
| 4.1 | `engine` package: SPI (`GameModule`, `GameDefinition`, `CommandSpec`, `Transition`, `GameContext`, `ActionRejected`), `GameRegistry` with startup consistency checks against `game_definition`; move settings schema behind the registry (`room.SettingsSchema` becomes an adapter); implement `GET /games`, `GET /games/{key}`; ArchUnit rules for `engine`/`games`/`session` | CC | 4.0 | Unit tests with a test-only game module (test scope + test-only Flyway location for its row); all Phase 2 REST ITs unchanged and green; startup fails on duplicate key / row without module |
| 4.2 | `ActiveSession` core: phase identity, deadline book, `drainDue`, `DeadlineScheduler` port (`ScheduledThreadPoolExecutor` + `ManualScheduler`), watchdog, fair mailbox lock, mailbox hold-time metric | CC | 4.1 | Race tests of `game-engine.md` §5.2 with a manual clock: late action → `STALE_ACTION`; exactly-at-deadline; replaced/cancelled/duplicate wake-ups no-ops; 200-thread storm; no leaked `ActiveSessions` |
| 4.3 | Action pipeline: `ActionGate` (roster membership, dedupe, phase guard), typed payload binding, per-type rate/size from `CommandSpec`, router change to roster authorization, `NOT_IN_ROOM`/`STALE_ACTION`/`INVALID_PHASE` mapping | CC | 4.1 | Unit tests per pipeline step and order; WS IT: wrong role/room/stale phase/duplicate/oversize/rate-limited; Hibernate statistics prove zero DB statements per game command |
| 4.4 | Engine services: `TurnOrder`, `ScoreLedger` + `Ranking`, `RematchVotes`, audience-aware `SessionEventSink`, public/private declaration checks, secrecy harness | CC | 4.1 | Unit + property tests (competition ranking, ledger invariants, permutation order); secrecy test with host/member/outsider recorders on the test game |
| 4.5 | Lifecycle in `session` + room: `RoomService` lifecycle methods, `RoomEvent.StatusChanged`, REST `start`/`kick`/`close` + WS `START_GAME`, `STARTING` countdown/cancel, `GAME_STARTED`, finish → `RESULTS`, abort paths, `REMATCH_VOTE`, `RESULTS_TIMEOUT`, in-game disconnect grace, `PATCH /guests/me` lock | CC | 4.2–4.4 | WS IT with the test game: start → phases → finish → rematch → second session; every transition-table row (positive and forbidden) tested; concurrent start requests → exactly one `STARTING`; kick/close in each status; authorization tests (non-host, non-member) |
| 4.6 | Snapshot: `GameSnapshot` header + `view`, results/rematch in snapshot, `lastSession` | CC | 4.5 | IT: reconnect in `STARTING`/`PLAYING`/`RESULTS`/after reset; property test: snapshot + later events ≡ uninterrupted event stream for every viewer |
| 4.7 | `history`: migration `V4`, `SessionRecorder` (bounded queue, retry, never-drop results), `SessionHistoryQuery`, `GET /sessions/{id}/results`; `RestartRecovery` + graceful-shutdown abort; optional orphan-room sweep | CC | 4.5 | PostgreSQL IT: constraints (one `RUNNING` per room), session/rounds/results persisted; database stopped mid-session → game continues, results appear after it returns; startup on a database seeded with `RUNNING`/`PLAYING` rows → `INTERRUPTED`/`WAITING`; results endpoint states |
| 4.8 | Frontend game plugin registry + generic session shell: countdown, scoreboard, phase banner, reconnect via snapshot, rematch vote UI, "game interrupted" banner, kick/close host controls; types mirror §2.1/§3.4/§3.7 | AG | 4.5 contract (fixtures until backend slice lands) | Component tests; shell renders a test-game fixture; wholesale store replacement on snapshot; `aria-live` phase announcements |
| 4.9 | Docs and test-record update: `testing.md` Phase 4 coverage, README, `architecture.md` status, known limitations | CC (+AG frontend part) | 4.1–4.8 | Docs match behavior; limitations listed honestly |

Exit: a test-scope game runs end to end through the real engine over real STOMP, including start countdown and cancel, stale/duplicate/unauthorized actions, deadline races, reconnect snapshots in every room status, results persistence, rematch loop, and restart recovery; ArchUnit rules green; `backend verify` and frontend lint/typecheck/test/build green. The SPI is declared stable for Phases 5–6 only after this exit (R21).

## Phase 5 — Draw & Guess  (parallel with Phase 6)

Design: `docs/draw-guess-design.md`; contract: `api-contracts.md` §3.9; rules `game-rules.md` `[DG-1]…[DG-18]`; decisions D24–D31 (`decisions-and-risks.md` §6) must be approved or amended **before task 5.4 starts**. Task 5.0 and the documents were done in P5-01. **P5-02 (backend) implemented 5.1–5.6 and 5.9, and 5.7 except its load/zero-Hibernate-statement acceptance items** (details and remaining gaps: `draw-guess-design.md` §17, verification in `testing.md`); 5.5 needed one SPI addition (`GameContext.nextPhaseId`, R21). P5-03/P5-04 delivered 5.10–5.12 (frontend). **P5-05 (integration verification, 2026-10-14)** added a minimal committed Playwright smoke (`frontend/e2e`, `npm --prefix frontend run e2e`) covering most of 5.13 with two players (desktop mouse + mobile touch), and new `DrawGuessIT` cases for client-supplied scores, duplicate correct guesses, drawer reconnect and results after a rematch; it also fixed a flaky `DrawGuessIT` (evidence: `testing.md` "Phase 5 integration verification record (P5-05)"). Still open: 5.8, the rest of 5.13 (three-player run, raw wrong-role commands from the browser, drawer leaving), 5.14, and E2E in CI.

| ID | Task | Owner | Depends | Acceptance |
|---|---|---|---|---|
| 5.0 | Approve D24–D31 (and D28's effect on Telephone's `Stroke`); merge the contract commits | Lead/user | — | Written approval or amendments; contract/rules updated first if any change |
| 5.1 | `drawing` library: `Stroke`, `StrokeBatch`, `CanvasBuffer` (per-batch/stroke/round budgets, undo, clear, open-stroke rules, batch ordering and style checks) | CC | 5.0 | Unit tests at every limit ±1; budget refund on undo/clear; rejection leaves buffer equal |
| 5.2 | Word pool: original resource (tiers, ≥ 150 each), loader at startup, blocklist/charset test, draw-3 and used-set logic | CC | 5.0 | Tests: tier sizes, charset, no repeats in a session, exhaustion fallback, seeded determinism |
| 5.3 | Pure rules: `GuessMatcher` (normalize, close, duplicate), `Scoring` (`[DG-12]`, `[DG-13]`), `HintMasker` | CC | 5.0 | Unit tests named `DGn_…` with the examples of game-rules; property test for hint bounds |
| 5.4 | `DrawGuessModule`: turn list, T1–T10, `CommandSpec`s, event audiences, `view`, `outcome`, `RoundRecord` meta | CC | 5.1–5.3, 4.x | Unit tests per transition row and per rejected cell; invariants property test; word never in a non-drawer `view` or public event |
| 5.5 | Registration: module bean, settings schema equality with `room.SettingsSchema`, `GameCatalog` start path, ArchUnit rules (no SPI change) | CC | 5.4 | Room with `draw-guess` starts; startup check fails on drift; no SPI change |
| 5.6 | History: round meta and standings detail visible in `GET /sessions/{id}/results` | CC | 5.4 | IT: results contain per-round drawer/word/reason and standings detail |
| 5.7 | WS ITs (real STOMP): full 3-player game, stale/late/duplicate, wrong role per command, oversize/rate-limit, secrecy across drawer/guesser/outsider, reconnect mid-round (strokes, hint, chat, solvers), drawer/guesser leaves in each phase, rematch twice, concurrent correct guesses | CC | 5.4–5.5 | ITs green; zero Hibernate statements per game command; mailbox hold time within threshold under 10-player load |
| 5.8 | Contract fixtures: JSON examples of every §3.9 payload, validated by backend and frontend tests | CC + AG | 5.0 | Same files consumed by both suites in CI |
| 5.9 | Size verification: maxed-out canvas snapshot < 256 KiB delivered over STOMP; module-raised `PAYLOAD_TOO_LARGE`/`NOT_DRAWER` arrive as `GAME_ERROR` | CC | 5.5 | Tests green or budget lowered and docs updated |
| 5.10 | Types and fixtures mirroring §3.9; event reducers into the game store; snapshot replaces game state wholesale | AG | 5.8 | Vitest for every event/snapshot shape; no secret fields typed as public |
| 5.11 | Canvas engine: Pointer Events (mouse/touch/pen), DPR scaling, integer 0–10000 quantization, ≈ 50 ms batching ≤ 20/s, simplification, stroke recovery after rejection, replay, undo/clear, eraser | AG | 5.8 | Vitest canvas utility tests (transform, DPR, batching, replay); manual browser check mouse + touch emulation |
| 5.12 | Draw & Guess plugin UI: word choice, hint, toolbar, guess/chat feed (public + restricted), scoreboard, round summary, results, own-echo suppression, `aria-live` announcements | AG | 5.10–5.11 | Component tests; keyboard-operable toolbar; `prefers-reduced-motion` respected |
| 5.13 | Playwright: draw & guess a word across 3 contexts; wrong-role actions rejected; refresh/reconnect mid-round; drawer disconnect; finish and rematch | AG | 5.7, 5.12 | E2E green on the committed backend |
| 5.14 | Hardening review (design §13), a11y smoke (axe), limits/abuse tests, docs and verification record in `testing.md` | CC + AG | 5.7, 5.13 | Checklist ticked; known limitations listed honestly |

Exit: three browsers play a complete game (word choice, drawing, guessing, scoring, round summaries, results, rematch); every `[DG-n]` has a test; secrecy, authorization and limit tests pass; reconnect restores canvas and state; `backend verify`, frontend lint/typecheck/test/build and E2E green.

### Word Pack System

| ID | Task | Owner | Depends | Acceptance |
|---|---|---|---|---|
| WP-01 | Word catalog and database: categorized, difficulty-tagged built-in words (Flyway `V5`/`V6`), `words` module, read-only metadata endpoints (api-contracts §2.3); no gameplay change | CC | 5.2 | ≥ 150 `EASY` and ≥ 150 `MEDIUM`, curated `HARD`; legacy words all present with consistent tiers (D32); duplicate/format constraints, filtering, locale fallback and API contract tested; `backend verify` green. **Done** (design §18) |
| WP-02 | Word filtering and game integration: `wordSource`/`wordCategories`/`difficulty` (adds `medium`)/`wordPackIds` settings (api-contracts §2.4, D33), `WordPoolService` + frozen `WordPool`, `CustomWordProvider` boundary for WP-03, start check `WORD_POOL_TOO_SMALL`, engine `GameModule.prepare`/`GameSetup`; no frontend change | CC | WP-01 | Categories (single, several, Random Mix, empty), each difficulty and mixed, combined de-duplication, insufficient pool (no partial start), no-repeat, exhaustion, secrecy, freeze at start, rematch reset tested; `backend verify` green. **Done** (design §19) |
| WP-03 | Custom word packs and import: room-scoped packs (host-only writes, member-visible metadata), TXT/CSV import with preview/confirm (api-contracts §2.5, D34), `PackWordProvider` for the word pool; `V8` | CC | WP-01, WP-02 | CRUD + authorization + cross-room isolation, parsers, limits, concurrency and rollback tests green; `./mvnw verify` |

## Phase 6 — Telephone Drawing  (parallel with Phase 5)

| ID | Task | Owner | Depends | Acceptance |
|---|---|---|---|---|
| 6.1 | Chain allocator + invariants | CC | 4.1 | Property tests n=3…12, all E: `[TP-1]` invariants |
| 6.2 | Telephone module: phases, private tasks, submissions persistence, timeout placeholders, disconnect handling | CC | 6.1, 4.7 | Unit + WS IT: 4-player full game; each player sees only previous entry |
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
