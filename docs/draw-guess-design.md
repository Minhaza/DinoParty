# DinoParty — Draw & Guess Design (Phase 5)

Status: **backend implemented in P5-02 (tasks 5.1–5.6, 5.9 and most of 5.7; see §17); decisions D24–D31 approved (D25, D28 as amended below).** Written against the Phase 4 engine as merged on `main` (`docs/game-engine.md`, `backend/.../engine`, `session`). Normative sources: rules in `docs/game-rules.md` (`[DG-*]`), wire formats in `docs/api-contracts.md` §3.9. This document adds the state machine, the module mapping, the concurrency analysis and the task breakdown, and lists the decisions that need approval (D24–D31, mirrored in `docs/decisions-and-risks.md` §6). Where this document and the two sources differ, the sources win; fix this document.

## 1. Scope and decisions needing approval

Draw & Guess runs as a `GameModule` (`draw-guess`) inside the shared engine: the engine owns room status, countdown, roster/shuffle, `phaseId`, deadlines, dedupe, scores ledger, persistence, rematch; the module owns rounds, words, drawing, guesses and the scoring formulas. No SPI change is expected (R21); where one is, it is listed in §14.

The earlier rules contained contradictions; all eight below are approved (D25 and D28 amended) and applied in the contract:

| ID | Contradiction found | Applied (proposed) | Alternative |
|---|---|---|---|
| D24 | `[DG-9c]` "round aborted, no points to anyone" vs `[DG-12]`: guesser points are published immediately in `GUESS_ACCEPTED` | Drawer left ⇒ drawer 0, points already awarded stay | Roll back awarded points (clients already saw totals; needs a negative-delta event) |
| D25 | `[DG-9b]` "all *connected* non-drawers guessed" vs edge table "eligibility removed after grace expires" | **Approved, amended:** a `DISCONNECTED` guesser is eligible only during the engine's 60 s grace; at expiry the engine marks them `LEFT`, they leave the eligible set and early completion is re-evaluated. No unsolved guesser left and ≥ 1 solver ⇒ `ALL_GUESSED`; no unsolved guesser and no solver ⇒ the engine's `[C-1]` path decides (the module does not end the round) | Ignore disconnected guessers immediately |
| D26 | `[DG-9b]` "2 s grace" vs `[C-6]`/D18 "no grace" | No grace: round ends in the command that completes it; the 6 s `ROUND_END` is the pause | Add a 2 s `INTERNAL` deadline (extra phase state) |
| D27 | `CLOSE`/`DUPLICATE`/`ALREADY_GUESSED` feedback existed with no rule; `[DG-6]` says wrong guesses are broadcast | Close guesses are private only; `DUPLICATE` = same normalized wrong guess already made this round by anyone (not rebroadcast); `ALREADY_GUESSED` dropped, solver text is restricted chat | Broadcast close guesses (lets others copy a near-miss) |
| D28 | `[DG-14]` limits (300 × 2000 points) allow ~8 MB of canvas, but the snapshot bound is 256 KiB (`game-engine.md` §7.1) | **Approved, amended:** integer points 0–10000; **12 000** live points (the 15 000 target does not fit: see §8), 8 KiB per message, 200 points per batch, 2000 per stroke, 300 strokes; exhaustion rejects the whole batch (`PAYLOAD_TOO_LARGE`), never truncates; maximal-snapshot test | 15 000 points needs the flat `points:[x0,y0,…]` representation (not approved) |
| D29 | "total rounds = players × cycles" vs `[DG-1]` skip left players | Fixed turn list, skip `LEFT`, `totalRounds` recomputed (may only shrink) | Fixed `totalRounds` with gaps |
| D30 | Engine counts any `RoundRecord` as a completed round for `[C-1]` | A `DRAWER_LEFT` round is recorded and counts; a round in progress at an early finish is discarded | Don't record aborted rounds (needs a flag on `RoundRecord`) |
| D31 | Word pool unspecified (tiers, repeats) | Two tiers EASY/HARD, ≥ 150 each; `mixed` = union; "used" = chosen words only | Per-choice tier mix (one easy, one hard, one any) |

Non-blocking choices made in the docs (listed so nobody finds them by accident): hint wire format is one char per word char; a correct guesser does not get the word back in snapshots; guess length 1–80; `strokeId` is a UUID; a new `strokeId` implicitly closes an open stroke; undo/clear on an empty canvas are acknowledged no-ops; spaces are ignored when matching (`ice cream` = `icecream`).

## 2. Roles

| Role | Who | Can | Cannot |
|---|---|---|---|
| **Drawer** | the one roster member whose turn it is (server decides, never the client) | `CHOOSE_WORD` (WORD_CHOICE), `DRAW_STROKE`/`DRAW_UNDO`/`DRAW_CLEAR` (DRAWING); sees choices, word, restricted solver chat | guess or chat |
| **Guesser (not yet solved)** | every other roster member not `LEFT` | `SUBMIT_GUESS` (evaluated; wrong ones are public chat) | draw; see word/choices |
| **Solver** | a guesser who guessed correctly this round | `SUBMIT_GUESS` as restricted chat to other solvers + drawer; sees that chat | be scored again; reveal the word publicly |
| **Spectator** | not applicable | — | A member of the room who is not on the frozen roster cannot exist during a session (`[C-4]`: nobody joins mid-session) |
| **Host** | independent of the above | engine-level: close, kick, rematch (`[C-2]`, `[C-3]`) | has no extra in-round power |

Roles are recomputed per round; the drawer role is a property of the round state, never read from a client field.

## 3. State machine

### 3.1 Relationship to the shared lifecycle

```
Room:    WAITING → STARTING → PLAYING ───────────────────────────────► RESULTS → WAITING
                                 │                                       ▲
Session:                      RUNNING ── (module phases below) ── finish ┘ FINISHED
                                 └── abort → ABORTED (no completed round / error / close)
```

The game phases live entirely inside `PLAYING`. The engine adds none (`phase` is the module's name). `GAME_STARTED` precedes the first `PHASE_STARTED`; `GAME_FINISHED` and `ROOM_STATUS_CHANGED{RESULTS}` follow the final `ROUND_END` (api-contracts §3.7).

### 3.2 Module state machine

```
init ─► nextTurn? ──yes──► WORD_CHOICE ──CHOOSE_WORD / deadline(auto-pick)──► DRAWING ──┬─ deadline ───────────┐
            ▲                  │                                                       ├─ last eligible solves ┼─► ROUND_END
            │                  └── drawer LEFT ─────────────────────────────────────────┴─ drawer LEFT ─────────┘      │
            │                                                                                                           │ deadline (6 s)
            └──────────────── another turn left ◄──────────────────────────────────────────────────────────────────────┤
                                         no turn left ──► finished=true ─► engine: GAME_FINISHED ─► RESULTS ◄───────────┘
```

| # | From → To | Trigger (inside the mailbox) | Guards | Events (in order) | Deadlines |
|---|---|---|---|---|---|
| T1 | `init` → `WORD_CHOICE` | session start | turn list built from `order × cycles` (`[DG-1]`); first non-`LEFT` turn | `PHASE_STARTED{WORD_CHOICE}`, private `WORD_CHOICES` | `PHASE_END` = +`wordChoiceSeconds` |
| T2 | `WORD_CHOICE` → `DRAWING` | valid `CHOOSE_WORD` by drawer | phase current, sender = drawer, index 0–2 | `PHASE_STARTED{DRAWING, hint}`, private `DRAWER_WORD` | `PHASE_END` +`drawSeconds`; `INTERNAL hint-1`, `hint-2` at 50 %/75 % if `hintReveal` |
| T3 | `WORD_CHOICE` → `DRAWING` | word-choice deadline fires | — | as T2 (`autoPicked=true`) | as T2 |
| T4 | `DRAWING` → `ROUND_END` | `PHASE_END` deadline fires | — | closing: `ROUND_ENDED{TIME}` · `PHASE_STARTED{ROUND_END}` | `PHASE_END` +6 s |
| T5 | `DRAWING` → `ROUND_END` | `SUBMIT_GUESS` completes the eligible set | `correct = eligible` after this guess | closing: `GUESS_ACCEPTED`, `ROUND_ENDED{ALL_GUESSED}` · `PHASE_STARTED{ROUND_END}` | as T4 |
| T6 | `WORD_CHOICE`/`DRAWING` → `ROUND_END` | `onPlayerStatus(drawer, LEFT)` | drawer is the round's drawer | closing: `ROUND_ENDED{DRAWER_LEFT}` (word `null` if unchosen) · `PHASE_STARTED{ROUND_END}` | as T4 |
| T7 | `DRAWING` → `DRAWING` | guess/drawing/hint events | see §4 | per command | hint deadlines |
| T8 | `ROUND_END` → `WORD_CHOICE` | `PHASE_END` fires | a non-`LEFT` turn remains | as T1; canvas and per-round state reset | as T1 |
| T9 | `ROUND_END` → finish | `PHASE_END` fires | no turn remains | `Transition.finish()` | engine |
| T10 | any → engine finish/abort | active (non-`LEFT`) players `< 2` | engine `[C-1]` | engine's; the in-progress round is discarded (D30) | — |

A turn that is skipped (player `LEFT` when reached) is skipped *inside* T1/T8: the module advances to the next non-`LEFT` turn within the same transition; if none, T9. `phaseId` changes in every row that changes phase; all deadlines of the old phase are cancelled by the engine (§4.3 of the engine doc), so a late hint wake-up can never fire in the next round.

**Invariants** (property-tested): exactly one current phase; `DRAWING` has a word, a drawer who is on the roster, and `solvers ⊆ roster \ {drawer}`; no word in any state exposed by `view` for a non-drawer; `round ≤ totalRounds`; `strokes` within the budgets; each guesser appears at most once among solvers; ledger total = sum of emitted deltas.

## 4. Actions: sender, phase, payload, errors

Authoritative table: `api-contracts.md` §3.9 (commands, per-type rate/byte caps, error codes). Summary of the pipeline per command (`game-engine.md` §4.1) with the Draw & Guess-specific part:

| Step | Check | Failure |
|---|---|---|
| Transport | type known for `draw-guess`, bytes ≤ cap, rate bucket | `INVALID_ACTION`, `PAYLOAD_TOO_LARGE`, `RATE_LIMITED` |
| Binding | payload fields/types/ranges (module validates the `Map`; there is no typed binding, game-engine §13) | `VALIDATION_FAILED` |
| Gate | roster member not `LEFT`; `actionId` unseen; due deadlines applied; `phaseId` current; type legal in phase (`CommandSpec.phases`) | `NOT_IN_ROOM`, `DUPLICATE_ACTION`, `STALE_ACTION`, `INVALID_PHASE` |
| Module role | drawer-only commands: `NOT_DRAWER`; `SUBMIT_GUESS` by drawer: `IS_DRAWER` | as named |
| Module semantics | batch order/style, budgets, normalization | `INVALID_ACTION`, `PAYLOAD_TOO_LARGE`, `VALIDATION_FAILED` |

`CommandSpec`s to register: `CHOOSE_WORD {WORD_CHOICE; 256 B; 2/s b3}`, `DRAW_STROKE {DRAWING; 8 KiB; 30/s b40}`, `DRAW_UNDO {DRAWING; 128 B; 5/s b5}`, `DRAW_CLEAR {DRAWING; 128 B; 2/s b3}`, `SUBMIT_GUESS {DRAWING; 1 KiB; 2/s b5}`. Event types: public `DRAWING_UPDATED`, `HINT_UPDATED`, `GUESS_ACCEPTED`, `GUESS_CHAT`, `ROUND_ENDED`; private `WORD_CHOICES`, `DRAWER_WORD`, `GUESS_FEEDBACK`, `GUESSER_CHAT`.

Per the rule "validate, then mutate" every rejection leaves state equal; a module test per command asserts this.

### 4.1 Guess evaluation (inside `handle`, in order)

1. Sender is the drawer → `IS_DRAWER`. Sanitize (strip control chars, trim), length 1–80, normalize (`[DG-4]`); empty ⇒ `VALIDATION_FAILED`.
2. Sender is a solver → restricted chat: append to `chat` with `audience = solvers ∪ {drawer}` (snapshot of the set at send time), emit `GUESSER_CHAT` to that set (including the sender). Stop.
3. Normalized equals the normalized word (also with spaces removed) → **correct**: `order = solvers.size()`, points per `[DG-12]` using `remMs = deadline − ctx.clock().instant()`, `ScoreDelta(guesser, points, "GUESS")`, add to solvers, emit `GUESS_ACCEPTED`. If now `solvers ⊇ eligible` → end round (T5) with `GUESS_ACCEPTED` in `closing`.
4. Word has ≥ 6 letters and Levenshtein = 1 on space-free forms → private `GUESS_FEEDBACK{CLOSE}`; not stored, not broadcast.
5. Normalized already in `seenWrongGuesses` (bounded set, this round) → private `GUESS_FEEDBACK{DUPLICATE}`.
6. Otherwise add to `seenWrongGuesses`, append public chat, emit `GUESS_CHAT`.

Whole-answer-inside-a-sentence guesses are not detected in the MVP (known limitation, §13 H4).

## 5. Word selection and visibility

| Datum | Drawer | Other players | Outsider / public stream | Persisted |
|---|---|---|---|---|
| 3 choices | private `WORD_CHOICES` + own snapshot in `WORD_CHOICE` | never | never | no |
| Chosen word (before round end) | private `DRAWER_WORD` + own snapshot in `DRAWING` | never (hint only) | never | no (until round end) |
| Chosen word (round end) | public `ROUND_ENDED.word`, snapshot in `ROUND_END` | same | same | `RoundRecord.meta.word` at round end |
| Unchosen words | never | never | never | no |
| Used-words set | server only | — | — | no |
| Hint | public (`PHASE_STARTED`, `HINT_UPDATED`, snapshot) | same | same | no |

Drawing choice flow: the module draws 3 words with `ctx.random()` at T1/T8; `wordChoices` are kept in state only in `WORD_CHOICE`; on T2/T3 the chosen word replaces them and `wordChoices` is nulled. The word list is a startup-loaded resource (no I/O in calls). `view()` is the only reader of secrets and takes the viewer id; the public event builders take a state-free `Public` record (engine §6.5).

Secrecy tests (backend, mandatory): three recorders (drawer, guesser, outsider) over a full game; assert the word/choices appear only in the drawer's private events and snapshots until `ROUND_ENDED`; assert no `ERROR`/`GAME_ERROR` message or log line contains them; assert a solver's snapshot has no `word`.

## 6. Timers and turn completion

`[DG-17]` table in the rules. Implementation notes: deadlines are `DeadlineRequest` ids `phase-end`, `hint-1`, `hint-2`; the engine cancels all on phase change. The advertised `deadlineAt` of `DRAWING` equals the `phase-end` deadline and is also `T` in scoring (no latency compensation, `[C-6]`). A turn completes only through T4/T5/T6; there is no path that leaves a phase without a deadline (`ROUND_END` and both choice/drawing phases always have `phase-end`), so a stuck game is impossible by construction; the engine watchdog covers swallowed wake-ups.

Scoring is integer arithmetic (`remMs`, `T×1000` as `long`), with `roundHalfUp` implemented as `(2·num + den) / (2·den)`. Unit tests carry the examples in the rules and the boundary cases (`remMs = 0`, `order` large → floor 50, `eligible = 1`).

## 7. Disconnection and leaving

Handled in `onPlayerStatus(change)`:

| Change | Module reaction |
|---|---|
| `DISCONNECTED` (any player) | none (state kept); the engine broadcasts `PLAYER_DISCONNECTED`. |
| `RECONNECTED` | none; client requests a snapshot. |
| `LEFT`, player is the drawer, phase `WORD_CHOICE`/`DRAWING` | T6. |
| `LEFT`, player is an eligible guesser who has not solved | remove from `eligible`; re-evaluate T5 (all remaining solved? then end round). |
| `LEFT`, solver | stays in `solvers` and `correct`; remains in `eligible` for the ratio. |
| `LEFT`, in `ROUND_END` or the player's own future turn | none now; turn skipped later (T1/T8). |

`PlayerStatusChange` carries no reason; the engine decides `LEFT` after the 60 s in-game grace, a leave or a kick. When the engine's `active < 2` rule triggers, it finishes/aborts before the module sees further input (T10); the module's `outcome()` is therefore called with the state at that moment and must not assume a round boundary: it reports standings detail from the ledger/solver counters only.

## 8. Drawing protocol (design rationale)

Normative format: api-contracts §3.9.

- **Why one command with `final` rather than begin/points/end:** every batch carries enough to be validated and replayed on its own (style repeated, `batchNo`), so there is no begin/end state that a dropped message can leave dangling; begin/end would triple message count on short strokes. "Begin" is `batchNo = 0`, "end" is `final = true`, "points" is any batch.
- **Ordering:** frames from one connection are processed in sent order; the mailbox serializes; publication order = acceptance order; `seq` is gapless per stream. `batchNo` additionally detects a *client-side* hole (a batch dropped by `RATE_LIMITED`, which is decided before the mailbox and therefore never reaches the module). On a hole the server rejects later batches of that stroke, and the client's recovery (new `strokeId` from the last point) keeps drawing alive.
- **Bounds:** per message 200 points and 8 KiB; per stroke 2000 points; per round 300 live strokes and 12 000 live points; ≤ 30 batches/s per drawer (burst 40). Abuse is self-limiting: at 30 batches × 200 points the point budget is gone in 2 s, after which stroke batches are rejected until undo/clear.
- **Deterministic exhaustion:** `CanvasBuffer.append` checks every limit against the *whole* batch before mutating anything and throws `ActionRejected(PAYLOAD_TOO_LARGE)`; nothing is stored, published or truncated, so server and all clients stay identical. Closing-only batches (`final`, no points) cannot fail on budget. Undo and clear refund exactly the points/strokes they remove. `canvasBudget` in the snapshot exposes usage.
- **Fan-out:** one public `DRAWING_UPDATED` per batch; the drawer receives and ignores its echo (D17); no per-recipient copies.
- **State:** `List<Stroke>` (bounded) plus an `openStrokeId`; undo pops the tail, clear empties; counters `livePoints` and `strokes` are decremented accordingly. The canvas is cleared when the next round starts, kept during `ROUND_END`.
- **Server-side processing:** the server validates and stores points exactly as received (integers); it does not simplify (clients do), so replay on all clients is identical to the drawer's canvas. Consecutive duplicate points are allowed.
- **Reconnection recovery:** snapshot `strokes` contains every live stroke in order, each with `closed` flag. A viewer applies the snapshot wholesale, then events with `seq >` the snapshot's. The drawer's own client, after reconnect, replaces its local canvas with the snapshot (its in-flight open stroke, if any, is shown with `closed:false` and continues with the next `batchNo` only if the client still holds the counter; otherwise it starts a new `strokeId`).
- **Snapshot size bound (256 KiB = 262 144 B, `game-engine.md` §7.1):** worst-case JSON with `[[x,y]]` pairs: point `[10000,10000],` = 14 B → 12 000 × 14 = 168 000 B; 300 stroke objects ≈ 123 B = 36 900 B; ≤ 30 chat lines × ≈ 450 B (80 chars × up to 4 B UTF-8 + keys) = 13 500 B; header, players, scores, hint, ids for 10 players ≈ 5 000 B → **≈ 223 KB**, margin ≈ 39 KB. At 15 000 points the same arithmetic gives ≈ 265 KB > limit, hence 12 000. A flat `points:[x0,y0,…]` array (12 B/point, ≈ 235 KB at 15 000) would allow 15 000 but changes the shared `Stroke` format again and is not approved. A maximal-canvas test serializes the real snapshot and asserts `< 262 144` bytes; the STOMP send buffer is 512 KiB (`WebSocketConfig`) so a 223 KB frame is deliverable (to be verified in an IT).

## 9. Concurrency and idempotency

- All module calls run in the room mailbox, one at a time; the module has no locks, threads or I/O.
- **Command vs deadline:** due deadlines run first (`[C-6]`): `CHOOSE_WORD` at/after the word-choice deadline → server pick wins, command `STALE_ACTION`; a guess at/after the drawing deadline → `STALE_ACTION`; 1 ms before → accepted with `remMs = 1`.
- **Concurrent correct guesses:** serialized in mailbox order; first gets `order = 0`. Two guessers can never both be `order = 0`. The last-guess-ends-round decision happens in the same call, so a second guess arriving afterwards finds `ROUND_END` and gets `STALE_ACTION` (its `phaseId` is the old one).
- **Drawer commands vs round end:** a stroke batch in flight when the round ends has the old `phaseId` → `STALE_ACTION`; strokes are never accepted after `DRAWING` ends.
- **Idempotency:** engine dedupe by `actionId` per (guest, room) (`DUPLICATE_ACTION`) comes first; module-level natural idempotency covers retries with a fresh `actionId`: `CHOOSE_WORD` (phase changes ⇒ `STALE_ACTION`), `DRAW_STROKE` (`batchNo` mismatch ⇒ `INVALID_ACTION`; the accepted state is unchanged), `DRAW_UNDO`/`DRAW_CLEAR` (empty canvas ⇒ acknowledged no-op; they are *not* idempotent otherwise, a second undo removes another stroke — clients must not retry them with a fresh id), `SUBMIT_GUESS` (a repeated correct guess is restricted chat, a repeated wrong guess is `DUPLICATE`). Clients that miss an `ACTION_ACK` rely on events/snapshot, never on blind retries (contract §3.3).
- **Snapshots** are built in the mailbox after `drainDue`, so a snapshot is exactly the state after its `seq`; replay-equivalence for every viewer is a property test (events + snapshot ≡ uninterrupted stream).
- **Cross-room:** no state shared between rooms; the word pool is an immutable list; randomness from the per-session `RandomSource`.
- **Persistence** only at round end via `RoundRecord` (no per-stroke or per-guess writes).

## 10. Integration boundaries

| Neighbour | Boundary |
|---|---|
| `engine` | Module implements `GameModule`; uses only `GameContext` (clock, random, roster, order, settings). Uses `Transition.closing/events/deadlines/scores/rounds/finish`. `GameDefinition`: key `draw-guess`, bounds 2–10, `minActivePlayers = 2`, `scored = true`, `settingsSchema`/defaults equal `room.SettingsSchema` (startup check fails on drift; the schema stays in `room` until the Phase 4 registry move lands, a duplicated-values test guards it). |
| `session` | Starts/ends sessions, applies transitions, publishes events by audience, drives presence → `onPlayerStatus`, handles rematch. The module never calls these. |
| `room` | Untouched. Settings validation, `maxPlayers`, host rules unchanged. `GameCatalog` row `draw-guess` already enabled; today starting yields `GAME_NOT_FOUND` until the module registers — registration flips it. |
| `realtime` | Router resolves command types from the module's `CommandSpec`s; per-type buckets; roster-based authorization; `GAME_ERROR` mapping for module rejections (must be verified for `PAYLOAD_TOO_LARGE`/`NOT_DRAWER`, §14). |
| `history` | `RoundRecord{roundNo, startedAt, endedAt, endReason, scoreDeltas, meta{drawerGuestId, word, autoPicked, correctGuessers}}`; standings `detail`. No new tables. |
| `drawing` (new, shared with Telephone) | Pure `games`-level library: `Stroke`, `StrokeBatch`, `CanvasBuffer` (budgets, undo, clear), validators. Depends on nothing but `common`. ArchUnit: `games.drawguess` → `engine`, `drawing`, `common` only. |
| `games.drawguess` | `DrawGuessModule`, `DrawGuessState`, `WordPool` (resource loader at startup), `GuessMatcher` (normalize/levenshtein), `Scoring`, `HintMasker`, `DrawGuessViews`. |
| Frontend | Game plugin registry from 4.8: a `draw-guess` plugin consuming §3.9 types; canvas engine independent of React. |

## 11. Testing strategy

| Layer | Tests |
|---|---|
| Unit (no Spring) | `GuessMatcher` (normalization table incl. accents, hyphens, spaces, NFKC, empty, close/dup); `Scoring` (examples, floors, rounding, drawer ratio incl. leavers); `HintMasker` (never reveals spaces/hyphens, ≤ ⌈L/2⌉, deterministic with seeded RNG); `CanvasBuffer` (every budget, undo/clear, open-stroke closing, batch-order and style checks, budget refund); `WordPool` (tier sizes, charset, blocklist, no repeats in a session, exhaustion fallback); turn list (leavers skipped, `totalRounds` shrink-only); every transition row T1–T10 and every rejected cell; one test per `[DG-n]` named `DGn_…`. |
| Property | Random action/deadline interleavings with a manual clock: one phase; secrecy of `view` for every viewer; ledger = deltas; snapshot + later events ≡ uninterrupted stream per viewer; canvas budgets never exceeded. |
| WS IT | Full 3-player game (2 cycles) over STOMP; stale/late/duplicate; wrong role per command; oversize/rate-limit; secrecy across drawer/guesser/outsider; reconnect mid-`DRAWING` restores strokes, hint, chat, solvers; drawer leaves in each phase; guesser leaves ending the round; 2 players, one leaves → engine finish/abort; rematch twice; two games in parallel rooms; concurrent correct guesses (N threads) → distinct orders. |
| Load/limit | Maxed-out canvas snapshot < 256 KiB and delivered; 30 batches/s for 5 s accepted; 10 players × 20 batches/s fan-out with the mailbox hold-time metric below the 50 ms WARN threshold; zero Hibernate statements per game command. |
| Frontend | Canvas utilities (coordinate transform, DPR, integer quantization, batching, replay, undo/clear), store reducers for every event, snapshot replacement, handler tests with a mock transport, component tests (word choice, hint, guess feed, scoreboard, round summary), a11y (`aria-live` announcements for round end/hint, keyboard-operable toolbar). |
| E2E (Playwright) | Draw & guess a word across 3 contexts; wrong role actions rejected; refresh/reconnect mid-round; drawer disconnect; finish + rematch. |
| Architecture | `games.drawguess` imports; no `Instant.now`/`new Random`/threads in `games`. |

## 12. Implementation task breakdown

Supersedes the Phase 5 table of `implementation-plan.md` (that file carries the same list with owners and acceptance).

| Group | Tasks |
|---|---|
| Backend (CC) | 5.1 `drawing` library (`CanvasBuffer`, validators); 5.2 word pool + resource + blocklist test; 5.3 `GuessMatcher`, `Scoring`, `HintMasker` (pure); 5.4 `DrawGuessModule` state machine (T1–T10, `view`, `outcome`, `CommandSpec`s, events); 5.5 registration and settings consistency, GameCatalog flip, ArchUnit rules; 5.6 history `RoundRecord` meta + standings detail |
| Integration (CC + AG) | 5.7 WS ITs with real STOMP (§11); 5.8 contract fixtures: JSON examples for every §3.9 payload checked by backend and frontend tests; 5.9 frame/outbound size verification (maxed-out snapshot) |
| Frontend (AG) | 5.10 types and fixtures mirroring §3.9; 5.11 canvas engine (pointer events, DPR, normalized integer coordinates, batching ≈ 50 ms, simplification, replay, undo/clear, eraser); 5.12 plugin: word choice, hint, guess/chat feed (public + restricted), toolbar, scoreboard, round summary, results; 5.13 snapshot/reconnect handling and echo suppression |
| Testing (CC + AG) | 5.14 Playwright scenarios; 5.15 property/replay tests; 5.16 a11y smoke |
| Hardening (CC + AG) | 5.17 limit/abuse review (§13); 5.18 docs and verification record in `testing.md` |

Dependencies: 5.1–5.3 are independent and parallel; 5.4 needs them; 5.7 needs 5.4–5.5; 5.10/5.11 start on the contract alone (fixtures); 5.12 needs 5.10–5.11; 5.14 needs 5.7 and 5.12.

## 13. Hardening checklist

- H1 No secret in logs, errors, `toString` of state, metrics tags; MDC carries `roomCode`, `guestId`, `sessionId`, `phaseId` but never `word`.
- H2 Every drawing/guess limit enforced server-side and tested at its boundary (±1).
- H3 Text is rendered as text (no HTML); control characters stripped; maximum lengths enforced before normalization work (cap input at 80 chars first so Levenshtein runs on bounded input).
- H4 Known limitation: a wrong guess containing the answer inside a longer sentence is broadcast as is; post-MVP: detect whole-token containment and answer privately.
- H5 Drawer collusion (telling the word out of band) and screenshot cheating are out of scope.
- H6 The 12 000-point/300-stroke/2000-point budgets protect memory per room; verify the `ActiveSession` heap per room with a maxed canvas (≈ 1 MB) times a plausible room count.
- H7 `RATE_LIMITED` is not recorded for dedupe (engine behavior) — the stroke client must tolerate dropped batches (§8).
- H8 Word list review: no offensive entries; original list, no copying from proprietary lists.
- H9 Clock: all scoring uses the injected `Clock`; tests never sleep.

## 14. Possible engine/SPI touch points (to confirm in 5.4–5.5, per R21)

1. `ActionRejected` raised by a module with `PAYLOAD_TOO_LARGE`/`NOT_DRAWER`/`IS_DRAWER` must reach the sender as a private `GAME_ERROR`; confirm in `CommandRouter` (contract §3.4 now says so). If it is rerouted to `/user/queue/errors`, fix the router, not the contract.
2. **Tie-break display order (decision, no SPI change).** `Ranking.of(totals, order)` orders equal scores by play order and the engine has no module tie-break (although `game-engine.md` §6.3 describes one). Draw & Guess does **not** add a hook: it must not change shared engine behavior for one game, so tied players display in play order and the earlier "correct guesses, then earliest join" display order is dropped; `correctGuesses` stays in the standing `detail`.
3. `CommandSpec.phases` is matched by name: the three phase names are the contract (`WORD_CHOICE`, `DRAWING`, `ROUND_END`).
4. The engine counts every `RoundRecord` as completed (D30); no change needed if D30 is approved as proposed.
5. `PlayerStatusChange` has no reason; sufficient for this module.
6. `HINT_UPDATED` is emitted from `onDeadline` of `INTERNAL` deadlines; confirm the engine passes the deadline id (`DeadlineFired`).

## 15. What was and was not verified

Verified by reading the code at the commit above: engine SPI signatures (`GameModule`, `Transition`, `CommandSpec`, `Audience`), the completed-round counting in `SessionService`, the outbound send-buffer limit, and that `Ranking` has no module tie-break. **Not verified:** any behavior of Draw & Guess (nothing is implemented); the STOMP delivery of a ~200 KB snapshot; whether the router maps module-raised `PAYLOAD_TOO_LARGE` as §14.1 assumes. No build or test run was needed or performed — documentation only.

## 16. Consistency check against the Phase 4 engine (done before P5-02; one correction in §17)

Checked against `backend/src/main/java/com/dinoparty/{engine,session}` and the engine tests; **no shared-engine change is needed.**

| Engine fact | Consequence for the module |
|---|---|
| `GameContext` has no player statuses | The module tracks `ACTIVE/DISCONNECTED/LEFT` itself from `onPlayerStatus` (a reconnect arrives as `ACTIVE`). |
| `SessionService.onMemberLeft` calls the module first, then finishes/aborts when active players `< minActivePlayers` | The module reacts to `LEFT` as in §7; it never ends the session itself. An `ALL_GUESSED`/`DRAWER_LEFT` round it records before the engine finishes counts as completed (D30). |
| `PhaseChange.data` is merged into `PHASE_STARTED` with `putIfAbsent`; `phase`, `phaseId`, `deadlineAt` are the engine's | Module public data uses other keys (`round`, `totalRounds`, `drawerGuestId`, `hint`). |
| `view()` keys are merged into the snapshot with `putIfAbsent` after the header (`sessionId, gameKey, status, order, phase, phaseId, phaseStartedAt, deadlineAt, scores, players, results, rematch`) | `DrawGuessSnapshot` keys never collide with those (tested). |
| Deadline ids `phase-end`, countdown and results ids are reserved; module requests are `INTERNAL` and dropped at phase change | Hint reveals are `hint-1`, `hint-2`; the phase deadline comes from `PhaseChange.deadlineAt`. |
| Module-thrown `DomainException`s (`ActionRejected`) are rethrown to the router; other exceptions become `SERVER_ERROR` | All rejections are `ActionRejected`; unexpected bugs become `SERVER_ERROR` without changing state. |
| `GameDefinition` has no settings schema; `room.SettingsSchema` keeps the draw-guess schema and DB defaults | The module reads `ctx.settings()` with the same keys and falls back to the defaults if a key is absent; a test compares module defaults with `V2` row values. |
| Ranking: ties in play order, no module hook | No hook added (§14.2). |
| `RoundRecord` count = completed rounds | D30 as approved. |
| D15: `ready` is advisory | The module never reads `ready`; start rules are untouched. |

## 17. Implementation notes (P5-02, backend)

Built: `com.dinoparty.drawing` (`Tool`, `CanvasLimits`, `StrokeBatch`, `CanvasBuffer`) and `com.dinoparty.games.drawguess` (`DrawGuessModule`, `DrawGuessState`, `DrawGuessSettings`, `WordPool`, `GuessMatcher`, `Scoring`, `HintMasker`), word lists `resources/games/drawguess/words-{easy,hard}.txt` (203 / 184 words, version 1). Registration is the Spring bean: the existing `GameCatalog` row (`V2`, enabled) now finds a module, so a `draw-guess` room starts; **no Flyway migration** was needed (no new tables; rounds use `game_round.meta`, results use `game_result.detail`).

Differences from this design, all deliberate:

- **One SPI change (R21), correcting §16 "no shared-engine change".** `GameContext.nextPhaseId` (game-engine.md §13): the private `WORD_CHOICES`/`DRAWER_WORD` must carry the `phaseId` of the phase they belong to, and the engine created that id only after the module returned. The test game and an engine test exercise it.
- **`NOT_DRAWER`/`IS_DRAWER`** were in the contract's error list but missing from `ErrorCode`; added (HTTP 403 if ever surfaced over REST). The router already sends module rejections as private `GAME_ERROR` (§14.1 confirmed, including `PAYLOAD_TOO_LARGE`, by `DrawGuessIT`).
- **Settings drift guard is a test, not a startup check.** `games` must not depend on `room` and `room` must not know modules, so `DrawGuessSettingsSchemaTest` (room schema accepts every module bound, refuses ±1) and `DrawGuessIT.theModuleDefaultsEqualTheCatalogRow` replace the startup comparison of §10. The module also tolerates missing/invalid values by using the defaults.
- **Seen-wrong-guess set** is bounded at 1000 normalized texts per round; beyond that duplicates are rebroadcast rather than answered `DUPLICATE` (memory bound, H6).
- **Restricted chat audience** = drawer + solvers who have not left, fixed at send time.
- **`roundsDrawn`** counts rounds the player drew to a recorded end other than `DRAWER_LEFT`.
- **Startup word-list validation**: the pool refuses to load (startup failure) on a malformed or duplicate entry.

Tests: `CanvasBufferTest`, `StrokeBatchTest` (limits at ±1, rejection leaves the buffer equal, refunds); `DrawGuessRulesTest` (`[DG-3]`, `[DG-4]`, `[DG-5]`, `[DG-12]`, `[DG-13]`, `[DG-15]`, settings); `DrawGuessSessionTest` (the module inside the real `SessionService` with a manual clock: T1–T10, rejection cells, secrecy, hints, timers, concurrency, reconnect, leaving, results, rematch, maximal snapshot < 256 KiB, a random-interleaving invariant walk); `DrawGuessIT` (real STOMP: full 3-player game, roles, stale/duplicate, secrecy across sockets, refresh mid-round, concurrent correct guesses, drawer leaving, results REST, rematch, maximal canvas snapshot delivered, module- and transport-level `PAYLOAD_TOO_LARGE`, catalog defaults); ArchUnit rules for `drawing` and `games.drawguess`.

Not done here (later tasks): 5.8 shared JSON contract fixtures, the replay-equivalence property test (snapshot + later events ≡ uninterrupted stream), the 10-player fan-out load test and mailbox hold-time measurement, the "zero Hibernate statements per Draw & Guess command" assertion (the engine's generic test covers the code path), whole-token answer detection in chat (H4), and everything frontend.


## 18. Word catalog (WP-01, data foundation for word packs)

Status: implemented (backend). Contract: `api-contracts.md` §2.3. Decision: D32 (proposed).

- **Storage.** Flyway `V5__word_catalog.sql` (schema) and `V6__word_catalog_seed.sql` (data, version 1). Three tables: `word_category` (stable keys, display order, `active`), `word_category_label` (label per category and locale) and `word_catalog_entry` (stable UUID, `locale`, `display_text`, `category_key`, `difficulty`, `active`). There was no word table before; the legacy resource lists were migrated into the seed rather than replaced.
- **Constraints.** One entry per locale and normalized text (spaces and hyphens ignored, like `[DG-4]` matching), whatever its category or difficulty; `difficulty IN (EASY, MEDIUM, HARD)` (`MIXED` cannot be stored); `[DG-15]` text shape for every locale (3–20 chars, lowercase, no leading/trailing/double separators, no digits/control chars), plus ASCII letters only for `en`; locale tags `ll`/`ll-RR`; category FK. Random Mix is not a row.
- **Stable ids.** `md5('dinoparty-word:' || locale || ':' || text)::uuid`, computed in the migration, so every database has the same ids; an id never changes after insert (a corrected text keeps its row).
- **Seed (English).** 653 words: 254 `EASY`, 283 `MEDIUM`, 116 `HARD`, in nine categories. All 387 legacy words are kept: the 203 legacy EASY words are `EASY`; the 184 legacy HARD words are split into 127 `MEDIUM` and 57 `HARD` (D32). 266 new words fill the categories (Sports, Technology, Actions, Places had few or none). A word has one category, its best fit; people go where they work (`chef` → Food & Drinks, `librarian` → Places), body parts are Nature.
- **Gameplay unchanged.** `WordPool` still loads `words-easy.txt`/`words-hard.txt`; `games.drawguess` may not depend on `words` (ArchUnit) and no game, scoring, STOMP or engine code changed. `WordCatalogSeedIT` fails if a legacy word is missing from the catalog or has an inconsistent tier, so the two cannot drift until the game reads the catalog (later work package).
- **Module.** `com.dinoparty.words` (`WordCatalogService`, `DifficultyFilter`, `CatalogLocales`, read records; `words.persistence` private), depends on `common` only. `WordCatalogService.words(...)` returns filtered words for future server-side drawing; no REST endpoint lists words.
- **Locale handling.** Normalized tag → fallback chain tag, language, `en`; the first locale with active words is the catalog locale; labels fall back per category along the same chain.

| Category (key) | EASY | MEDIUM | HARD | Total |
|---|---:|---:|---:|---:|
| Animals (`animals`) | 55 | 30 | 11 | 96 |
| Food & Drinks (`food-drinks`) | 31 | 30 | 10 | 71 |
| Nature (`nature`) | 35 | 28 | 19 | 82 |
| Objects (`objects`) | 63 | 47 | 14 | 124 |
| Places (`places`) | 17 | 30 | 10 | 57 |
| Movies & Games (`movies-games`) | 18 | 37 | 17 | 72 |
| Sports (`sports`) | 12 | 29 | 10 | 51 |
| Technology (`technology`) | 8 | 30 | 12 | 50 |
| Actions (`actions`) | 15 | 22 | 13 | 50 |
| **Total** | **254** | **283** | **116** | **653** |
