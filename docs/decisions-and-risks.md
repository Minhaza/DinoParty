# DinoParty — Requirements Analysis: Ambiguities, Risks, Decisions

Source: `docs/MASTER_PROMPT.md` (immutable input; the product is named **DinoParty**, wherever the prompt says "PartyPlay").
Status legend: **PROPOSED** = architect recommendation awaiting approval; **FIXED** = dictated by the master prompt.

## 1. Fixed constraints (not up for debate)

- Java 21, Spring Boot (stable, verified compatible), Spring MVC, Spring WebSocket + STOMP, Spring Data JPA, PostgreSQL, Flyway, Bean Validation, Spring Security, JUnit 5 / Mockito / Testcontainers.
- React + TypeScript + Vite, Tailwind v4, shadcn/ui, Zustand, TanStack Query, React Router, Canvas + Pointer Events, STOMP client, Lucide.
- Modular monolith, single backend instance, in-memory active room state, durable history in PostgreSQL. Redis only as an abstraction; never required locally.
- Server-authoritative game logic. Guest identity via opaque token (hash stored server-side).
- Deploy: Docker/Compose, GitHub Actions, Cloudflare Pages (frontend), Oracle Always Free ARM64 VM + Caddy (backend).

## 2. Ambiguities and how we resolve them

| # | Ambiguity in master prompt | Recommendation | Status |
|---|---|---|---|
| A1 | "Spring Boot, a verified compatible stable release" — no version. | Pick the latest GA 3.5.x/4.x line that supports Java 21 and Testcontainers at Phase 1 time; record the exact version in `backend/pom.xml` and `docs/architecture.md`. Verify with a clean `verify` build. | PROPOSED |
| A2 | Build tool unspecified (Maven vs Gradle). | **Maven** with wrapper (`mvnw`): simpler for Spring + Docker multi-stage + ARM64 builds. | PROPOSED |
| A3 | Repository layout unspecified. | Monorepo: `backend/`, `frontend/`, `deploy/`, `docs/`, `.github/`. | PROPOSED |
| A4 | Package manager for frontend. | **npm** (lockfile committed) to minimise tooling on Cloudflare Pages and CI. | PROPOSED |
| A5 | REST health endpoint path. Prompt asks for "health endpoints" only. | `GET /api/v1/health` (public, app-level: status, version, DB reachability) plus Actuator `/actuator/health/{liveness,readiness}` for Docker/Caddy checks. Actuator not exposed publicly through Caddy. | PROPOSED |
| A6 | Where is the auth token sent? Prompt: "store token in client". | REST: `Authorization: Bearer <token>`. STOMP: token in the `CONNECT` frame native header (never in the URL query, to keep it out of access logs). Token persisted in `localStorage`. | PROPOSED |
| A7 | "Invite URL" vs "invalid invitation": is there an invite secret separate from the room code? | Room code (6 chars, unambiguous alphabet, ~30^6 space) **is** the invitation; invite URL = `{frontend}/join/{CODE}`. Host may close the room; no separate token in MVP. Rate-limit join attempts per guest and IP to resist code guessing. | PROPOSED |
| A8 | Private vs public rooms; room discovery. | All rooms are code-only (unlisted). No public room browser in MVP. | PROPOSED |
| A9 | Min/max players per game. | Draw & Guess 2–10 (default max 8). Telephone 4–8 (see A12). Values live in `GameDefinition`, are overridable by host within bounds. | PROPOSED |
| A10 | "Reconnection grace period" length and host-transfer policy. | Grace 30 s lobby / 60 s in-game (configurable). On expiry: player marked LEFT; if host, host transfers to the longest-connected remaining player. A disconnected drawer forfeits the turn after grace (round ends, no drawer points). | PROPOSED |
| A11 | Draw & Guess scoring formula ("configurable rules"). | Guesser: `base + speedBonus` (linear decay over round time). Drawer: fixed points per correct guesser. Default values in `game-rules.md`; configurable subset: rounds, round seconds, word choices, hint reveal. | PROPOSED |
| A12 | Telephone: allowed player counts and number of entries. Prompt suggests even counts "if it simplifies". | Rotation `holder(chain c, step k) = (c + k) mod n` works for every n ≥ 3 and guarantees nobody holds a chain twice while `entries ≤ n`. MVP supports **4–8 players; entries per chain default to n (even n) or n−1 (odd n) so albums end on a drawing; the host may choose any value 4 ≤ E ≤ n.** Odd n is allowed; the UI warns if E is odd (album ends with text). Full proof and edge cases in `game-rules.md`. | PROPOSED |
| A13 | Telephone: who writes the initial prompt and who owns chain *c*? | Chain *c* is owned by player *c*; the owner's initial prompt is entry 0. The owner never receives it again (entries ≤ n). | PROPOSED |
| A14 | Word lists, i18n, content moderation. | English-only MVP, original curated word list (~300 words) in a versioned JSON resource. Nickname/free-text filter = length + character allow-list + small blocklist; no ML moderation. | PROPOSED |
| A15 | Avatar system: "avatar" and "optional avatar color". | Avatars are a fixed set of original dino illustrations referenced by key (`dino-rex`, …) + hex color from a palette. No user-uploaded images (avoids storage/moderation). | PROPOSED |
| A16 | Redis "abstraction". | Define ports (`RoomStateStore`, `RateLimiter`, `EventBus`) with in-memory implementations. No Redis dependency or compose service in MVP. Docs state that Redis does not make multi-instance gameplay work. | FIXED + PROPOSED |
| A17 | Persistence of drawings. Prompt: no per-event DB writes. | Telephone drawing submissions are stored as one compressed stroke-list JSON (JSONB or `bytea`) per submission at submit time. Draw & Guess strokes are memory-only; only a final thumbnail/stroke blob per round may be persisted (decision D5). | PROPOSED |
| A18 | Rematch semantics. | Rematch vote in the RESULTS state; when all connected players (or host) confirm, the same room returns to WAITING with the same players and settings and a new `GameSession`. | PROPOSED |
| A19 | Chat vs guesses. Prompt mentions "chat and guesses". | In Draw & Guess a single text input is a guess; wrong guesses show as chat to everyone; correct guesses are hidden (shown as "X guessed it!"). No free chat in Telephone or lobby (MVP). | PROPOSED |
| A20 | Light/dark theme "if feasible". | CSS-variable theming from Phase 1 (cheap now, costly later); polish in Phase 7. | PROPOSED |

## 3. Architectural risks

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | Concurrency bugs in room/game state (join races, timer vs action). | Capacity bypass, double scoring. | One **single-threaded mailbox (actor-style) per room**; every mutation (REST-originated, STOMP, timer) is a command enqueued to it. No shared mutable state outside the mailbox. Deterministic `Clock` injected for tests. |
| R2 | Timer expiry racing in-flight actions. | Stale accepted actions. | Deadlines are server timestamps; timer fires a command into the mailbox; actions carry `phaseId`/`actionId`; stale ones are rejected with `STALE_ACTION`. |
| R3 | REST joins and WS membership drifting apart. | Ghost players. | REST join is the only membership mutation; WS connect only *attaches* a connection to an existing membership. Presence is derived from sessions, tracked by the room mailbox. |
| R4 | Drawing traffic volume. | CPU/bandwidth on a small free VM. | Normalized coordinates, client batching (~50 ms), point-count and payload caps, simplification on server, per-user rate limit. Replay for reconnect from bounded in-memory stroke list. |
| R5 | Secret leakage (word, telephone chain) through broadcast topics or snapshots. | Fairness failure. | Separate public topic (`/topic/rooms/{code}`) from private user queue; snapshot builder takes a *viewer* parameter; contract tests assert absence of secrets for other viewers. |
| R6 | Spring `/user` destinations and sticky identity with STOMP. | Private events not delivered. | Custom `Principal` = guest id set in a `ChannelInterceptor` on `CONNECT`. Integration-test private delivery early (Phase 3). |
| R7 | In-memory state lost on restart/deploy. | Active games die. | Documented limitation; graceful drain on deploy is out of scope; completed results already persisted; clients show "room lost" with a clear path back. |
| R8 | Oracle Always Free ARM64 constraints (CPU, RAM, idle reclamation, availability). | Slow builds, capacity issues. | Multi-arch images (`linux/arm64`) built in CI via buildx; JVM heap flags tuned; keep services to backend + Postgres + Caddy. |
| R9 | Cloudflare Pages (frontend) → separate-origin backend: CORS, WSS origin checks, cookies unavailable. | Connection failures. | Bearer token (no cookies), explicit CORS allowlist and STOMP `allowedOrigins` from env, WSS via Caddy. |
| R10 | Token/identity security (guest tokens are bearer secrets; XSS exposes them). | Account hijack. | 256-bit random tokens, SHA-256 hash stored, expiry + sliding renewal, strict CSP in Cloudflare `_headers`, no `dangerouslySetInnerHTML`, sanitize all user text. |
| R11 | Two AI agents editing the same files. | Merge conflicts, contract drift. | Ownership map and worktree rules in `AGENTS.md`; `docs/api-contracts.md` is the single source of truth, changed only via an explicit contract-change commit. |
| R12 | Scope: eight phases and two full games. | Unfinished core. | Vertical slices, phase exit gates, no UI-only features. |
| R13 | Flyway vs JPA `ddl-auto`. | Schema drift. | `spring.jpa.hibernate.ddl-auto=validate`; Flyway is the only schema authority. |
| R14 | Testcontainers in CI/ARM/Windows dev. | Flaky or slow tests. | Separate `*IT` suite (failsafe), Docker required only for ITs; unit tests run without Docker. |
| R15 | Original IP: "inspired by" existing games. | Legal/brand risk. | Original name, art, words, and UI copy; Fake Artist title and rules are subject to originality review (see `fake-artist-proposal.md`). |

## 4. Decisions needing approval

Please approve, amend or reject. Anything unanswered is implemented as PROPOSED.

| ID | Decision | Recommendation |
|---|---|---|
| D1 | Build tool | Maven + wrapper |
| D2 | Monorepo layout `backend/ frontend/ deploy/ docs/` | Approve |
| D3 | Spring Boot version | Select at Phase 1 after verifying a clean build; report exact version |
| D4 | Token transport (Bearer header; STOMP CONNECT header; `localStorage`) | Approve |
| D5 | Persist Draw & Guess round artwork? | No for MVP (history keeps scores + words only); revisit post-MVP |
| D6 | Telephone supports odd player counts | Yes, 4–8 players, entries ≤ n |
| D7 | Room code is the only invite secret; unlisted rooms; no public browser | Approve |
| D8 | Event sequencing: two streams (public per-room seq, private per-recipient seq) | Approve |
| D9 | Frontend test runner/E2E: Vitest + Testing Library, Playwright | Approve |
| D10 | Fake Artist naming/IP review before building it | Required before any implementation |
| D11 | Dev ports: backend 8080, frontend 5173, Postgres 5432 (compose maps 5433 on host to avoid clashes) | Approve |
| D12 | Branching: one branch per task; PRs into `main`; squash merge | Approve |
