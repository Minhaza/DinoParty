# DinoParty — Requirements Analysis: Ambiguities, Risks, Decisions

Source: `docs/MASTER_PROMPT.md` (immutable input; the product is named **DinoParty**, wherever the prompt says "PartyPlay").
Status legend: **PROPOSED** = architect recommendation awaiting approval; **FIXED** = dictated by the master prompt.

## 1. Fixed constraints (not up for debate)

- Java 21, Spring Boot (stable, verified compatible), Spring MVC, Spring WebSocket + STOMP, Spring Data JPA, PostgreSQL, Flyway, Bean Validation, Spring Security, JUnit 5 / Mockito / Testcontainers.
- React + TypeScript + Vite, Tailwind v4, shadcn/ui, Zustand, TanStack Query, React Router, Canvas + Pointer Events, STOMP client, Lucide.
- Modular monolith, single backend instance, in-memory active room state, durable history in PostgreSQL. Redis only as an abstraction; never required locally.
- Server-authoritative game logic. Guest identity via opaque token (hash stored server-side).
- Deploy: Docker/Compose, GitHub Actions, Cloudflare Pages (frontend), Oracle Always Free ARM64 VM + Caddy (backend). *Superseded by D49 (Render, §8).*

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

## 5. Phase 4 decisions needing approval (shared game engine)

Design: `docs/game-engine.md`. Contract: `docs/api-contracts.md` §3.7, §3.8, §4. Rules: `docs/game-rules.md` `[C-4]…[C-12]`. Status of every row: **approved as recommended** by the Phase 4 implementation brief and implemented, except D20 (frame size), which stays open until Phase 6 and is not implemented. D13 is the one that changes what users see when the server restarts and needs an explicit answer.

| ID | Decision | Recommendation | Alternatives / consequence |
|---|---|---|---|
| **D13** | **Engine behavior on server restart.** | **A — interrupt and return to lobby.** At startup every `RUNNING` session becomes `INTERRUPTED/SERVER_RESTART`; rooms in `STARTING/PLAYING/RESULTS` return to `WAITING` with members kept and `ready` cleared; graceful shutdown aborts sessions first and tells clients (`GAME_ABORTED`); clients learn via `room.lastSession` in their first snapshot. Interrupted games produce no standings. | B — checkpoint state per phase and resume (large: serializable per-game state, past deadlines, secrets in DB; post-MVP). C — close all rooms (simplest, unfriendly). Consequence of A: single-instance only, and deploys must stop the old process before starting the new one (recovery treats every `RUNNING` row as orphaned). §9 of the design doc. |
| D14 | Module layout: new `session` module orchestrates lifecycle between `room`, `engine`, `realtime`, `history` through ports; the engine runs **inside the existing keyed room mailbox** (no separate `RoomRuntime` actor); mailbox lock becomes fair. | Approve. Replaces the `RoomRuntime` sketch in architecture §3.2 (never built). | Introduce a real actor/queue per room: more machinery than one-room-≤10-players needs. |
| D15 | Start rules: host start allowed with connected members ≥ `minPlayers`; `ready` stays advisory; 3 s `STARTING` countdown is cancelled if connected members fall below the minimum; roster frozen at countdown end. | Approve `[C-4]`. | Require everyone ready (blocks groups with an AFK member). |
| D16 | Persistence split: `game_session` insert is synchronous in the start transaction (start fails if the DB is down); rounds/submissions/results are async via a bounded recorder with retry and may be dropped (counted, logged) except results, which are never dropped; results endpoint reads the DB only (`RESULTS_NOT_READY` until written). | Approve. | Fully async (cannot anchor FKs or restart recovery); fully synchronous (DB hiccup can stall a live room's mailbox). |
| D17 | No "public except X" audience. Strokes are broadcast to everyone and the drawer ignores its echo (changes the Phase 5 text of `DRAWING_UPDATED`). Restricted audiences use per-recipient private events. | Approve. | Per-recipient fan-out for every stroke (N private `seq` per event). |
| D18 | Deadline-first rule `[C-6]`: due deadlines are applied before any command; the engine grants no latency grace; games that need one schedule the closing deadline later than the advertised `deadlineAt`. Changes the Draw & Guess edge-case wording from `≤` to "before". | Approve. | A global grace (shifts every phase end and every fairness argument). |
| D19 | `RESULTS` auto-returns to `WAITING` after 15 minutes. | Approve; value configurable. | Stay in `RESULTS` until someone acts (idle rooms linger; ghost-room problem after restarts). |
| D20 | **Frame size vs Telephone drawings.** Game-rules allow 256 KiB per `TELEPHONE_SUBMIT_DRAWING`; the STOMP frame cap is 64 KiB. | Raise `app.realtime.max-message-bytes` to 320 KiB; keep per-command caps (e.g. 32 KiB strokes) in each `CommandSpec`; rate-limit large frames. Decide before Phase 6. | Chunked submission over several commands (more protocol); REST upload for drawings (new endpoint and auth path); lower the telephone cap to fit 64 KiB (stroke simplification). |
| D21 | History is independent of guest retention: `game_result.guest_id`/`game_submission.author_guest_id` have no FK to `guest_identity`; nickname is snapshotted per result. | Approve. | FK with `ON DELETE SET NULL` (loses authorship when guests are purged). |
| D22 | Rematch trigger `[C-3]`: the host's yes **or** every connected member's yes resets the room; a no never blocks; reset keeps members, host, game and settings and clears `ready`, scores and votes. | Approve. | Host-only reset; unanimous-only (an AFK-connected member blocks forever). |
| D23 | Leaving rules `[C-1]`/`[C-9]`: the game ends early only when players not `LEFT` fall below the game's `minActivePlayers` (draw-guess 2, telephone 3), never because of a momentary disconnect; with no completed round the session is aborted without results. In-game disconnect grace is 60 s via a new setting (Phase 3 has only 30 s). | Approve. Amends the wording of `[C-1]`. | Count connected players (a network blip can end a game). |

### Phase 4 risks

| # | Risk | Mitigation |
|---|---|---|
| R16 | Mailbox is a lock held during DB transactions; a slow module or slow query blocks every command of that room, including deadline wake-ups. | No I/O in module calls; no DB on the game-command path (in-memory roster); fair lock; hold-time metric + WARN at 50 ms; test with N strokes asserting zero statements. |
| R17 | Lost or late timer wake-up leaves a game stuck. | Deadlines are data in the session; wake-ups are stateless; 5 s watchdog; `deadline.missed` metric must stay 0. |
| R18 | A module leaks a secret in a public event or snapshot. | Event types declared per audience, public payload records separate from state, contract tests with host/member/outsider recorders, replay-equivalence property test. |
| R19 | History persistence stalls or drops data. | Bounded queue, idempotent upserts, never-drop results, retry/backoff, metric `dinoparty.history.dropped`, IT that kills the database mid-session. |
| R20 | Restart recovery misclassifies sessions when two instances overlap. | Single-instance rule documented; compose stops old before new; recovery logs the count of rows it changed. |
| R21 | `GameModule` SPI proves too narrow once real games land (Phases 5–6 run in parallel on one SPI). | Phase 4 exit requires the SPI to be exercised by a test module covering every hook; Phases 5/6 may only change the SPI through a PR that also updates `game-engine.md` and the test module. |

## 6. Phase 5 decisions needing approval (Draw & Guess)

Design: `docs/draw-guess-design.md`. Contract: `api-contracts.md` §3.9. Rules: `game-rules.md` `[DG-*]`. Status of every row: **PROPOSED**. The docs already apply the recommended option; if one is rejected, the rule and contract text change in a `docs(contract)` commit before task 5.4 starts. D28 also changes the shared `Stroke` format and so touches Telephone (Phase 6).

| ID | Decision | Recommendation | Alternative / consequence |
|---|---|---|---|
| D24 | Drawer leaves mid-round (`[DG-9c]` said "no points to anyone", but guesser points are published at each correct guess). | Round ends `DRAWER_LEFT`; drawer scores 0; points already awarded to guessers stay. | Roll back awarded points: needs a negative-delta event and tells players their points vanished. |
| D25 | Early end when all guessed: disconnected guessers (`[DG-9b]` "connected" vs edge table "after grace"). | A `DISCONNECTED` guesser blocks early end until they return or are `LEFT`; consistent with D23. | Ignore disconnected guessers immediately: a network blip forfeits that player's round. |
| D26 | `[DG-9b]` 2 s grace after the last correct guess. | None: the round ends in the command that completes it; `ROUND_END` (6 s) is the pause (consistent with D18). | A 2 s internal deadline before `ROUND_END` (extra state, no benefit: nobody can still guess). |
| D27 | Guess feedback rules. | Close guesses are private-only; `DUPLICATE` = same normalized wrong guess already made this round (not rebroadcast); solvers' later text is restricted chat to solvers + drawer; `ALREADY_GUESSED` removed. | Broadcast close guesses (others can copy a near-miss and fix the typo). |
| D28 | Drawing wire format and canvas budget. | Integer fixed-point points 0–10000 shared with Telephone and a round budget of 12 000 live points (amended from 15 000, see above) so a snapshot stays under the 256 KiB engine bound. | Floats rounded to 4 decimals with a ≈ 6 000-point budget; or REST-paged canvas fetch (new endpoint). |
| D29 | `totalRounds` when players leave. | Fixed turn list; `LEFT` players' turns are skipped; `totalRounds` is recomputed and can only shrink. | Fixed total with gaps in round numbers. |
| D30 | Early finish and the engine's completed-round count. | A `DRAWER_LEFT` round is recorded and counts as completed; a round in progress at an early finish is discarded (no drawer points, no record). | Do not record aborted rounds (needs a flag on `RoundRecord`). |
| D31 | Word pool. | Tiers EASY/HARD, ≥ 150 words each, 3–20 chars, original; `mixed` = union; "used" = chosen words; repeats allowed only on exhaustion. | Per-choice tier mix (one easy, one hard, one any). |

### WP-01 (word catalog) decision needing approval

| ID | Decision | Recommendation | Alternative / consequence |
|---|---|---|---|
| D32 | Three stored difficulties (`EASY`/`MEDIUM`/`HARD`) in the new database word catalog vs D31's two game tiers (`EASY`/`HARD`, ≥ 150 each). | **PROPOSED.** D31 is unchanged and still governs gameplay, which keeps reading the two resource lists in WP-01. The catalog maps them explicitly: every legacy EASY word is catalog `EASY`; every legacy HARD word is catalog `MEDIUM` or `HARD`. When the game switches to the catalog, setting `easy` = `EASY`, `hard` = `MEDIUM` ∪ `HARD`, `mixed` = all, so both game tiers keep ≥ 150 words *(the game-setting mapping is replaced by D33 below)*. The catalog guarantees ≥ 150 `EASY` and ≥ 150 `MEDIUM`; `HARD` is a smaller curated tier (D31 is **not** read as requiring 150 `HARD`). A new `medium` game setting, if wanted, is a separate `docs(contract)` change. | Require ≥ 150 `HARD` as well (needs ~35 more hard-to-draw entries of lower quality); or keep two catalog tiers (loses the finer filter the word-pack UI needs). |

### WP-02 (word selection) decision needing approval

| ID | Decision | Recommendation | Alternative / consequence |
|---|---|---|---|
| D33 | Word selection settings and how they meet D31/D32. | **PROPOSED; implemented in WP-02.** Games draw from the database catalog (the resource lists become test fixtures). `difficulty` gains `medium` and each value maps 1:1 to a stored difficulty (`easy`=`EASY`, `medium`=`MEDIUM`, `hard`=`HARD`, `mixed`=all), instead of D32's `hard` = `MEDIUM` ∪ `HARD`; `wordSource` `built-in`/`custom`/`combined`, `wordCategories` (several, Random Mix = all), `wordPackIds`. Category/difficulty filters apply to built-in words only; custom pack entries are selected by pack only. D31's "≥ 150 per tier, no exhaustion in a full game" is kept for the defaults (`mixed` Random Mix: 653 words) and every single difficulty over Random Mix (`EASY` 254, `MEDIUM` 283, `HARD` 116 ≥ 50 rounds); narrower selections are allowed down to one draw (3 words), with D31's repeat-on-exhaustion. Pool and settings are frozen at host start; a rematch re-resolves them with an empty used set. Word choices use the server's `SecureRandom`, not the session-seeded RNG, because the seed is stored with the session (D16) and offered-but-unchosen words must not be reconstructible ([DG-2]). | Keep D32's `hard` = `MEDIUM` ∪ `HARD` and add no `medium` (no strict "Hard" for the word-pack UI); or require a minimum of `players × cycles + 2` words at start (no repeats ever, but small custom packs could never be played). |

### WP-03 (custom word packs) decision needing approval

| ID | Decision | Recommendation | Alternative / consequence |
|---|---|---|---|
| D34 | Custom word packs: ownership, import confirmation, limits. | **PROPOSED; implemented in WP-03.** Packs are room-scoped (no accounts): host-only writes, member-visible metadata, words host-only. Import is two-step with **re-upload + SHA-256 digest + pack content version** instead of a server-side preview token: the server keeps no preview state, never trusts client rows and rejects stale confirms with `IMPORT_STALE`. Limits as in api-contracts §2.5 (1 MiB, 1 000 words, 80 code points, 20 packs, plus 10 000 rows per file as a parser bound). Duplicates are case-insensitive with accents preserved; the stricter `[DG-4]` equivalence still applies when the pool is built. | Short-lived preview token (needs expiry and memory bookkeeping, valid on one instance only); or user-owned permanent packs (needs accounts, out of scope). |

Phase 5 risks:

| # | Risk | Mitigation |
|---|---|---|
| R22 | Stroke traffic (≤ 30 batches/s × recipients) loads the room mailbox and the free VM. | Per-type buckets, point and stroke budgets, no DB on the hot path, mailbox hold-time metric, load test in 5.7. |
| R23 | A ~200 KB snapshot fails or is slow over STOMP (send buffer 512 KiB). | Maxed-canvas snapshot test (5.9); lower the budget rather than the engine bound. |
| R24 | Dropped batches (`RATE_LIMITED` is decided before the mailbox) leave holes in a stroke. | `batchNo` detection, client recovery with a new `strokeId`, client send rate ≈ 20/s < server 30/s. |
| R25 | Secret word leaks through a public event, error text, log or another viewer's snapshot. | Public payload records, secrecy harness with drawer/guesser/outsider (5.7), log review (H1). |
| R26 | The engine has no module tie-break and counts every `RoundRecord` as completed. | No engine change: ties display in play order; D30 accepts the completed-round counting. |


## 7. Phase 6 decisions needing approval (Dino Telephone)

Design: `docs/telephone-design.md` (§1 lists the contradictions found in the earlier text, §14 the options). Contract: `api-contracts.md` §3.10. Rules: `game-rules.md` `[TP-*]`. Status: **D41–D45 APPROVED** by the user on 2026-10-16 (before P6-03, recommended option as written); every other row is **PROPOSED**. The docs already apply the recommended option. If one is rejected, rules and contract change in a `docs(contract)` commit before the task that needs it (P6-02: D35–D40, D46–D48; P6-03: D41–D45). D36 makes **D20 (frame size) unnecessary for Telephone**: the frame cap stays 64 KiB, D20 stays open only as a record of the rejected alternative.

| ID | Decision | Recommendation | Alternative / consequence |
|---|---|---|---|
| D35 | Players advance together or independently. | Lockstep: one engine phase per step. | Independent pacing needs per-player phases (SPI redesign). |
| D36 | Drawing transport and limits. | Private server-held draft over the Draw & Guess batch protocol (`TELEPHONE_DRAW_*`, `[DG-14]` limits); `TELEPHONE_SUBMIT_DRAWING{}` commits; `ALREADY_SUBMITTED` replaces `DUPLICATE_ACTION` for repeats; drops 600/20 000/256 KB. | One-shot 256 KB submission: needs D20 (frame cap 320 KiB), loses work on refresh/timeout. |
| D37 | Timeout keeps the draft. | Auto-submit the draft (text or strokes); placeholder only when empty. | `[TP-5]` literal: placeholder for any missing submission. |
| D38 | `entriesPerChain`. | 0 (auto) or 4–8, clamped to `n` at roster freeze; odd values warn. | "Even or `n`" (not checkable at save time). |
| D39 | `allowSkipTimeout`. | Keep the key, accept only `true`. | Remove it, or define a strict mode. |
| D40 | Placeholder as next source. | Pass on, flagged. | Replace by a fresh prompt. |
| D41 | Early end (< 3 active) and the reveal. | **Approved:** No reveal after an early finish; albums via REST. | A module wind-down (engine hook). |
| D42 | Reveal pacing. | **Approved:** Host-driven, cursor-guarded, idle auto-advance 60 s (15 s if host disconnected). | No timeout (can strand the room); auto-play only. |
| D43 | Reveal data delivery. | **Approved:** Entries in public `ALBUM_REVEALED`; snapshot byte budget with `omitted` entries; REST after `FINISHED`. | Cursor events + REST fetch during the reveal. |
| D44 | Submission persistence. | **Approved:** Never-drop `SubmissionRecord` on `Transition` (R21 SPI change) + `V10 game_submission` (V9 went to the P6-02 settings). | Droppable (holes in albums) or all at finish. |
| D45 | Album access. | **Approved:** Any current member of the session's room; no retention job in the MVP. | Roster only; expiring links. |
| D46 | Auto-prompt source. | Built-in catalog words `EASY` ∪ `MEDIUM`. | A new phrase list. |
| D47 | Rotation. | Keep `holder = (i + k) mod n` (constant predecessor). | Latin-square offsets. |
| D48 | Disconnected holder and early end. | Blocks until the 60 s grace or the deadline (as D25). | Ignore disconnected holders. |

Phase 6 risks:

| # | Risk | Mitigation |
|---|---|---|
| R27 | Drawing content leaks before the reveal (task event, snapshot, REST, logs). | Private-only `TELEPHONE_TASK`, no echo of draft batches, REST albums only after `FINISHED`, secrecy harness with holder/other/outsider recorders, log review. |
| R28 | A reveal or step snapshot exceeds 256 KiB. | Byte budget with `omitted` entries; maximal-snapshot tests in P6-03; lower telephone `CanvasLimits` before touching the engine bound. |
| R29 | Albums have holes because a submission was dropped or the server restarted. | Never-drop `SubmissionRecord`; `INTERRUPTED` sessions expose no albums by design. |
| R30 | The SPI change for submissions disturbs Draw & Guess. | R21 process: the P6-03 PR updates `game-engine.md` and the test game; Draw & Guess passes an empty list. |
| R31 | Memory per room (8 live drafts + ≤ 32 stored drawings). | Same bounds as H6; ≈ 1 MB per draft, ≈ 6.5 MB worst-case entries; measured in P6-06. |

## 8. Deployment decisions

| # | Decision | Recommendation | Alternative |
|---|---|---|---|
| D49 | **Hosting target.** §1 names Cloudflare Pages + an Oracle ARM64 VM behind Caddy. | **Approved (user, 2026-10-21):** Render. One Docker web service (`deploy/Dockerfile`, blueprint `render.yaml`) serves the built SPA, `/api` and `/ws` from one origin, so the frontend keeps its same-origin calls and no CORS is needed; Render PostgreSQL holds the data. Free tier for now. Client addresses for rate limits come from `X-Forwarded-For` only through Tomcat's `RemoteIpValve` (`FORWARD_HEADERS_STRATEGY=native`), which trusts private-range proxies only and takes the right-most untrusted hop, so a client cannot choose its own address. Supersedes the deploy line of §1 and Phase 8 tasks 8.2–8.4 for now; the Oracle/Cloudflare plan stays possible later. | Oracle VM + Caddy + Cloudflare Pages (original plan: more ops work, no idle spin-down); Render static site + separate backend service (needs a configurable API/WebSocket base URL in the frontend and cross-origin CORS; Render static rewrites do not carry WebSockets). |

Deployment risks (Render free tier):

| # | Risk | Mitigation |
|---|---|---|
| R32 | Free web services spin down after ~15 min without inbound traffic; the restart ends live games (D13) and the next visitor waits for a cold start (tens of seconds). | Accepted for a demo; Starter plan (always on) before real use. |
| R33 | Render deploys start the new instance before stopping the old one (no persistent disk on free tier), so startup recovery (D13) can mark sessions `INTERRUPTED` that the old instance is still running. | Deploy when no games run; the old instance's graceful shutdown aborts its sessions anyway. A paid instance with a disk gets stop-then-start deploys. |
| R34 | Free PostgreSQL expires after 30 days and has no backups. | Demo data only; paid database before real use. |
