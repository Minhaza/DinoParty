# DinoParty — Game Rules

All values marked *(default)* are configurable within the stated bounds. The server is authoritative for roles, timers, scores and transitions. Bracketed IDs (e.g. `[DG-12]`) are stable rule references used by tests.

## Common rules

- Players are authenticated guests in a room. Games start only from `WAITING` by the host when `minPlayers ≤ connectedPlayers ≤ maxPlayers` (members whose connection is in its grace period do not count as connected; the `ready` flag is advisory and never blocks a start `[C-4]`).
- Every phase has a server `deadlineAt`. Client clocks are display-only.
- Actions carry `actionId` and `phaseId`; duplicates are ignored (idempotent ack), stale ones rejected.
- A disconnected player has a reconnection grace period (30 s lobby / 60 s in-game, default). After it, the player is `LEFT` for the current session (they may rejoin only after the game ends, in the lobby). The in-game value needs its own setting (`app.realtime.in-game-disconnect-grace`, default 60 s); Phase 3 has only the lobby value.
- If the number of players not `LEFT` falls below the game's `minActivePlayers` during play (draw-guess 2, telephone 3), the game ends early `[C-1]`: with results computed from the completed rounds if there is at least one, otherwise it is aborted with no results and the room returns to `WAITING`. A player who is merely `DISCONNECTED` (inside the grace period) still counts as active, so a network blip never ends a game.
- Host leaving permanently transfers hosting to the longest-connected remaining player `[C-2]`.
- Text input: trimmed, 1–N chars, control characters stripped, HTML never rendered. Nicknames 2–20 chars.
- Rematch: in `RESULTS`, players vote; the host voting yes, or every connected member voting yes, returns the room to `WAITING` with the same members, host, game and settings `[C-3]`. Details and the other engine-level rules are below.

### Engine-level rules (all games)

- **`[C-4]` Start.** Host start moves the room `WAITING → STARTING` and begins a 3 s server countdown. If connected members drop below `minPlayers` before it ends, the room returns to `WAITING` (`START_CANCELLED`). When it ends, the roster is frozen: every current member, in a shuffled order (`order`) drawn once from the session's random source. Nobody joins mid-session (`ROOM_IN_PROGRESS`).
- **`[C-5]` One phase at a time.** A session has exactly one current phase with a fresh `phaseId`. Changing phase invalidates every deadline and every command of the previous phase.
- **`[C-6]` Deadline wins.** A deadline that is due (`deadlineAt ≤ now`) is applied before any command is looked at. A command processed at or after the deadline of its phase is `STALE_ACTION`, even if it was sent earlier; clients never get a grace period from the engine (a game that wants one schedules its closing deadline later than the `deadlineAt` it advertises).
- **`[C-7]` Commands are serialized per room.** Two commands for one room never run concurrently; commands from one connection run in the order sent, commands from different players in the order the server dequeued them. "Simultaneous" correct guesses are therefore ranked by that order.
- **`[C-8]` Scores are integers owned by the engine's ledger.** Games return score deltas with a reason; clients and games never write totals. Ranking: highest total first, equal totals share a rank and the next rank is skipped (1, 1, 3); a game supplies the display tie-break order. Unscored games (telephone) have no standings and `score: null`.
- **`[C-9]` Leaving mid-game.** A player who leaves, is kicked or times out is `LEFT` for the rest of the session: their score stays in the results, they get no further turns, and the game's own rules decide what happens to the turn they held (`[DG-10]`, `[TP-5]`). Rejoining happens only in the next `WAITING`.
- **`[C-10]` Results.** The engine publishes `GAME_FINISHED` and moves the room to `RESULTS` once the game reports it is done. `RESULTS` lasts until the room resets (rematch vote, host close, everyone leaving) or 15 minutes pass (`RESULTS_TIMEOUT`, then `WAITING`). Results stay available through `GET /sessions/{id}/results` after the reset.
- **`[C-11]` Rematch reset.** Resets the room, not the people: members, host, game key and settings are kept; `ready` flags, scores, votes and the in-memory session are cleared; the next start creates a new session (`session_number + 1`) with a new shuffle. Settings are editable again.
- **`[C-12]` Server restart.** A restart ends any running session (`INTERRUPTED`); the room returns to `WAITING` with its members. See `docs/game-engine.md` §9 (decision D13).

---

## Game 1 — Draw & Guess (`draw-guess`)

Players: 2–10 (default max 8); `minActivePlayers` 2. Design, state machine and wire details: `docs/draw-guess-design.md`; contract: `api-contracts.md` §3.9. Decisions D24–D31 (`decisions-and-risks.md` §6) are **approved**, D25 and D28 with the amendments written below; rules that came from them are tagged with the decision id.

### Settings (host, in WAITING only)

| Setting | Default | Bounds |
|---|---|---|
| `cycles` | 2 | 1–5 |
| `drawSeconds` | 80 | 30–180 |
| `wordChoiceSeconds` | 15 | 5–30 |
| `hintReveal` | on | on/off: reveal letters progressively |
| `difficulty` | mixed | easy/mixed/hard word pool |

The number of word choices is the constant 3 (not a setting). Constants (not settings): round-summary pause 6 s, word length 3–20.

### Round flow

Phases of one round, in order: `WORD_CHOICE` → `DRAWING` → `ROUND_END` (names are the engine's `phase` values). Every round has all three unless it is aborted (`[DG-10]`).

1. **`[DG-1]` Turns and drawer rotation.** The engine's `order` (a shuffle made once at start, `[C-4]`) is repeated `cycles` times to make the *turn list* (`players × cycles` entries, fixed at start). Each round takes the next turn; a turn whose player is `LEFT` when the turn is reached is skipped without a round. `round` is 1-based and counts rounds actually played; `totalRounds` is `rounds played so far + remaining turns whose player is not LEFT` and is recomputed at every phase start, so it may shrink when players leave and never grows. A player who left never re-enters (`[C-9]`). A `DISCONNECTED` player keeps their turn (`[DG-10]`). *(proposed, D29)*
2. **`[DG-2]` Word choice phase** (`WORD_CHOICE`, `wordChoiceSeconds`). The server draws 3 distinct words from the pool (`[DG-15]`) and sends them **privately** to the drawer only. `CHOOSE_WORD{wordIndex}` from the drawer ends the phase at once and starts `DRAWING`. If the drawer has not chosen by the deadline, the server picks one of the three at random and starts `DRAWING` (`autoPicked: true` in the round record). Nobody but the drawer ever sees the three choices, and the two unchosen words are never published, persisted or revealed.
3. **`[DG-3]` Drawing phase** (`DRAWING`, `drawSeconds`, deadline = phase start + `drawSeconds`). The drawer receives the word privately. Everyone else sees only the **hint**: a string with one character per character of the word, `_` for each hidden letter, spaces and hyphens shown as they are (`"____ ___"`, `"__-____"`); clients render the spaced form. With `hintReveal` on, one random still-hidden letter (never a space or hyphen) is revealed when 50 % of `drawSeconds` has elapsed and another at 75 %, and `HINT_UPDATED` is published each time; never more than ⌈letters/2⌉ revealed. The drawer's word and canvas are the only thing the drawer produces; the canvas starts empty.
4. **`[DG-4]` Guess normalization and matching.** A guess is normalized as: Unicode NFKC → NFD with combining marks removed (accents folded) → lowercase → hyphens and underscores become spaces → every character that is not a letter, digit or space is removed → whitespace collapsed and trimmed. The word is normalized the same way. A guess is **correct** if the normalized texts are equal, or equal after removing all spaces (`"ice cream"` = `"icecream"`). A guess that normalizes to the empty string is invalid (`VALIDATION_FAILED`). Raw guess length is 1–80 characters (Unicode code points) after trim; control characters and invisible format characters (Unicode `Cc`/`Cf`, e.g. bidi overrides, zero-width marks) are stripped before the length check and normalization, and the stripped, trimmed text is what chat shows.
5. **`[DG-5]` Close guesses.** For normalized words of ≥ 6 letters, a guess at Levenshtein distance exactly 1 (on the space-free forms) is **close**: the guesser gets private feedback `CLOSE`; it is not accepted and not broadcast. *(proposed, D27: not broadcast.)*
6. **`[DG-6]` Wrong guesses** (neither correct nor close) are broadcast as public chat lines with the author (`GUESS_CHAT`). A wrong guess whose normalized text equals one already guessed (by anyone) in this round is not broadcast; the author gets private feedback `DUPLICATE` *(proposed, D27)*.
7. **`[DG-7]` Correct guesses and muting.** A correct guess is broadcast only as `GUESS_ACCEPTED` (guesser, points, new total; never the word). That player is then a *solver* for the rest of the round: any further text they submit is delivered only to the other solvers and the drawer (`GUESSER_CHAT`), is never evaluated as a guess, and is not echoed to anyone else. Late solvers do not receive earlier restricted lines live, and the snapshot only contains restricted lines the viewer was an audience member of when they were sent. The drawer cannot send text (`[DG-8]`) but receives solvers' lines.
8. **`[DG-8]` Drawer restrictions.** In `DRAWING` the drawer may only draw, undo and clear; they cannot guess or chat (`IS_DRAWER`). Non-drawers cannot send drawing or word commands (`NOT_DRAWER`). Guesses and chat exist only in `DRAWING`; in `WORD_CHOICE`/`ROUND_END` they are `INVALID_PHASE`.
9. **`[DG-9]` Round end.** `DRAWING` ends when (a) its deadline passes (`reason: TIME`), or (b) every *eligible guesser* has guessed correctly (`reason: ALL_GUESSED`), at the moment the last correct guess is processed — there is no extra grace (`[C-6]`, D26) — or (c) the drawer is `LEFT` (`[DG-10]`). Eligible guessers are defined in `[DG-13]`; a `DISCONNECTED` eligible guesser stays eligible, and so blocks (b), only during the engine's in-game reconnection grace (60 s); when the grace expires the engine marks them `LEFT`, they are removed from the eligible set and (b) is re-evaluated at once with the remaining players (D25). (b) holds when the eligible set has no unsolved guesser left and at least one guesser solved; if it has no unsolved guesser and nobody solved, fewer than 2 players remain and the engine's `[C-1]` path decides (the module does not end the round itself, so that round is discarded as in `[DG-16]`). Room membership, presence and reconnection are the engine's and are unchanged. At round end the engine moves to `ROUND_END` (6 s): `ROUND_ENDED` publishes the word, the round's score deltas and totals, the final canvas stays visible. Round-end conditions are evaluated inside the same mailbox command that caused them, in this order of events: `GUESS_ACCEPTED` (if any), `ROUND_ENDED`, `PHASE_STARTED{ROUND_END}`.
10. **`[DG-10]` Drawer leaves.** A drawer who is `DISCONNECTED` keeps the turn: the round continues with its current deadline and the drawer's snapshot restores word and canvas on return. When the drawer becomes `LEFT` (grace expired, left, kicked) in `WORD_CHOICE` or `DRAWING`, the round ends at once with `reason: DRAWER_LEFT`: the drawer scores 0, guesser points already awarded stay (they were published as `GUESS_ACCEPTED`), nobody receives further points, and the word is revealed if it had been chosen *(proposed, D24)*. The round still goes through `ROUND_END` and counts as a recorded round (engine `[C-1]` counts it, D30). If the drawer is `LEFT` during `ROUND_END`, nothing changes.
11. **`[DG-11]` Next round or finish.** When `ROUND_END` expires the next round starts (`WORD_CHOICE`, new word draw, empty canvas) or, if no turn is left, the game finishes (`GAME_FINISHED`, room → `RESULTS`). The last round also gets its full `ROUND_END` pause.

### Scoring (defaults)

- Let `T = drawSeconds`, `remMs` = milliseconds from the processing instant (room mailbox time) to the `DRAWING` deadline, `order` = 0-based index among correct guessers in mailbox order. `roundHalfUp(x) = floor(x + 0.5)`.
- **Guesser points** `[DG-12]`: `max(50, roundHalfUp(100 + 400 × remMs / (T × 1000)) − 25 × order)`. Example: `T = 80`, 60 s left, first solver → 400; third (`order = 2`) → 350.
- **Drawer points** `[DG-13]`, awarded once at round end, 0 when the round ended `DRAWER_LEFT`: let `eligible` = non-drawer roster members not `LEFT` at the start of `DRAWING`, minus those who became `LEFT` without having guessed; `correct` = number of solvers (a solver who later left still counts in `correct` and in `eligible`). Points = `roundHalfUp(100 × correct / eligible)`, plus 50 if `correct = eligible`; 0 if `correct = 0`. Example: 3 of 4 → 75; 4 of 4 → 150.
- Scores are integers computed server-side; clients never submit scores. Points are applied to the ledger when computed (guesser: at the guess; drawer: at round end).
- Ranking `[C-8]`; tied players share a rank. Display order among them is the engine's: play order (the engine has no module tie-break and Draw & Guess does not add one). Standing `detail`: `{correctGuesses, guesserPoints, drawerPoints, roundsDrawn}`.

### Word pool `[DG-15]`

- Original English word list (A14), versioned resource. Each word has tier `EASY` or `HARD`, 3–20 characters, letters plus single spaces/hyphens, no leading/trailing separator, no offensive entries (blocklist test). Size: ≥ 150 per tier so that `players × cycles` (≤ 50) rounds never exhaust a tier.
- `difficulty`: `easy` → EASY tier, `hard` → HARD tier, `mixed` → both tiers. The 3 choices are drawn uniformly without replacement from the pool minus **words already chosen earlier in this session** (offered-but-unchosen words may come back). Pool exhaustion falls back to allowing repeats. A new session (rematch) starts with an empty used set.

### Timers `[DG-17]`

| Phase | Duration | Advertised `deadlineAt` | Ends early when |
|---|---|---|---|
| `WORD_CHOICE` | `wordChoiceSeconds` | start + duration | valid `CHOOSE_WORD`; drawer `LEFT` |
| `DRAWING` | `drawSeconds` | start + duration | all eligible guessed; drawer `LEFT` |
| `ROUND_END` | 6 s | start + 6 s | never |

Hint reveals are internal deadlines at 50 % and 75 % of `DRAWING`. All durations come from the server clock; due deadlines are applied before any command `[C-6]`.

### Disconnection and leaving `[DG-16]`

| Case | Behavior |
|---|---|
| Drawer disconnects | Round continues on the current deadline (`[DG-10]`); if the grace period (60 s) expires the drawer is `LEFT` and the round ends `DRAWER_LEFT`. |
| Guesser disconnects | Stays eligible and keeps their points and solver status only during the in-game reconnection grace (60 s), blocking an `ALL_GUESSED` early end meanwhile. If they return, nothing changed. When the grace expires the engine marks them `LEFT` (D25): they leave the eligible set (`[DG-13]`) and `[DG-9]` (b) is re-evaluated at once. |
| Fewer than 2 players not `LEFT` | Engine `[C-1]`: the session finishes with results if at least one round was recorded (including a `DRAWER_LEFT` round), otherwise it is aborted. A round in progress at that moment is discarded: no drawer points, no round record, guesser points already awarded stay in the standings. |
| Reconnect | The snapshot restores everything the viewer may see (`[DG-18]`); nothing is replayed. |

### Reconnect `[DG-18]`

A reconnecting viewer receives a snapshot containing the current phase, `phaseId`, deadlines, hint, scores, the solvers, the visible chat lines and the **complete canvas** (all live strokes, in order). The drawer additionally receives `word` (in `DRAWING`) or `wordChoices` (in `WORD_CHOICE`). No other viewer ever receives either, and a solver does not receive the word either. In `ROUND_END` the word is public.

### Canvas and drawing constraints `[DG-14]`

- Logical canvas aspect 16:10. Wire coordinates are **integers 0–10000** (`x` of width, `y` of height; 1 unit = 0.01 % of the side), D28 (approved). Tools: pencil, eraser, 12-colour palette (client UI; the server accepts any `#RRGGBB`), 4 brush sizes (index 1–4), clear, undo (last stroke).
- Limits (all enforced by the server on the drawer's own canvas, per round): ≤ 200 points per batch; ≤ 8 KiB per `DRAW_STROKE` message; ≤ 2000 points per stroke; ≤ 300 live strokes; **≤ 12 000 live points in total**; ≤ 30 batches/s (burst 40). Undo and clear free their budget. The approved target was 15 000 points (D28), but with `[[x,y]]` pairs the worst-case snapshot (12 000 × 14 B + 300 stroke headers ≈ 123 B + ≤ 30 chat lines + header) is ≈ 223 KB, while 15 000 points would be ≈ 265 KB and break the 256 KiB (262 144 B) snapshot bound, so the limit is reduced to 12 000; 15 000 would need the flat-array representation `points:[x0,y0,x1,y1,…]` (≈ 235 KB), which is not approved. A test serializes a maximal snapshot and asserts it is below 256 KiB.
- **Budget exhaustion is deterministic and never truncates.** A batch that would exceed any limit (points per stroke, live points, live strokes) is rejected **whole** with `PAYLOAD_TOO_LARGE` (`retryable: false`) before any state changes; nothing of it is stored or published, so every client canvas equals the server canvas. Earlier accepted batches of that stroke stay. Batches that add no points (`final` with an empty `points`) always fit, so a stroke can always be closed. The drawer frees budget with undo or clear; after undoing an open stroke, further batches for its `strokeId` are `INVALID_ACTION`. The snapshot reports `canvasBudget` (used and maximum points/strokes) so the UI can warn before the limit.
- A stroke is sent as ordered batches with the same `strokeId`, numbered from 0 (`batchNo`), the last one with `final: true`. The first batch for a `strokeId` fixes tool, colour and size; later batches must repeat them unchanged. A new `strokeId` implicitly closes any still-open stroke. Out-of-order or gapped `batchNo` is rejected (`INVALID_ACTION`).
- Undo removes the drawer's last stroke (open or closed), clear empties the board; undo/clear on an empty board are acknowledged no-ops that publish nothing. Undo does not restore cleared strokes. Both are part of the snapshot state (the snapshot contains the strokes that exist now). The canvas is discarded when the next round's `WORD_CHOICE` starts.
- Strokes are never persisted (D5); only round summaries are.

### Draw & Guess edge cases

| Case | Behavior |
|---|---|
| Guess arrives at/after deadline | The due deadline is applied first (`[C-6]`): the phase has already changed and the command is `STALE_ACTION`. "Before" means room-mailbox processing time, not network arrival. |
| `CHOOSE_WORD` races the word-choice deadline | Same: at/after the deadline the server's pick wins and the command is `STALE_ACTION`. |
| Simultaneous correct guesses | Serialized by the room mailbox; earlier processed = lower `order` = more points. |
| Same word twice in a session | Prevented by the used-words set; pool exhaustion falls back to repeats. |
| Duplicate command (`actionId` seen) | `DUPLICATE_ACTION`, not executed (engine). Any resubmission with a new `actionId` is judged on its own merits (a second `CHOOSE_WORD` is `STALE_ACTION` because the phase changed; a repeated `DRAW_STROKE` batch is `INVALID_ACTION` by `batchNo`; a repeated correct guess is restricted chat). |
| Everybody but the drawer leaves | Fewer than 2 players: engine `[C-1]` path above. |
| 2-player game | One drawer, one guesser; `eligible = 1`; the round ends when they guess or on time. |
| Room host leaves | Hosting transfers `[C-2]`; the game is unaffected. |
| Rematch | Engine `[C-3]`/`[C-11]`: same players, host and settings, new shuffle, empty used-words set, scores reset. |

---

## Game 2 — Telephone Drawing (`telephone`)

Players: **4–8** (default). Rationale: ≥ 4 gives non-trivial chains; ≤ 8 keeps the total play time reasonable. Even and odd counts are both supported (see parity note).

### Settings

| Setting | Default | Bounds |
|---|---|---|
| `promptSeconds` | 60 | 20–120 |
| `drawSeconds` | 90 | 30–180 |
| `describeSeconds` | 45 | 20–90 |
| `entriesPerChain` | `n` if n even, else `n − 1` | even or `n`; 4 ≤ value ≤ n |
| `allowSkipTimeout` | on | On timeout, an empty/placeholder entry is recorded |

### Chain allocation (rotation) `[TP-1]`

Let `n` players be ordered `p0 … p(n−1)` (shuffled once at game start). There are `n` chains `c0 … c(n−1)`; chain `c_i` is *owned* by `p_i`.

- Entry index `k` ∈ `0 … E−1` where `E = entriesPerChain ≤ n`.
- **Holder of chain `c_i` at step `k` is `p_((i + k) mod n)`.**
- `k = 0`: the owner writes the initial prompt (everyone submits a prompt simultaneously).
- `k` odd → DRAW the previous entry; `k` even (> 0) → DESCRIBE the previous drawing.

**Properties (tested as invariants):**
1. At every step, each player holds exactly one chain, and each chain has exactly one holder (the map `i → (i+k) mod n` is a permutation).
2. A player holds a given chain at most once while `E ≤ n` (the holders for `k = 0…n−1` are all distinct).
3. Each player sees only the immediately preceding entry of the chain they hold.
4. Every chain ends with exactly `E` entries.

**Parity note.** Entry 0 is text, so with `E` entries the final entry is a drawing when `E` is even and a text when `E` is odd. If the host leaves `entriesPerChain = n` and `n` is odd, the lobby shows: "Albums will end with a description." Choosing even `E` is recommended; the UI defaults to `n` for even `n` and `n − 1` for odd `n` `[TP-2]` (so albums end on a drawing; decision D6 in `decisions-and-risks.md`).

Example, `n = 4`, `E = 4`:

| step k | c0 | c1 | c2 | c3 | type |
|---|---|---|---|---|---|
| 0 | p0 | p1 | p2 | p3 | PROMPT |
| 1 | p1 | p2 | p3 | p0 | DRAW |
| 2 | p2 | p3 | p0 | p1 | DESCRIBE |
| 3 | p3 | p0 | p1 | p2 | DRAW |

### Phase flow

1. **PROMPT phase.** All players submit a text prompt (≤ 140 chars). Timer `promptSeconds`. On timeout the server inserts a random prompt from the prompt pool `[TP-3]`.
2. **Step phases (k = 1 … E−1).** Each player receives (privately) only the preceding entry of their held chain and submits the next entry (DRAW or DESCRIBE). Phase ends when all active players submit **or** the deadline passes (early advance). Players who finish early see a **waiting screen** with progress count (names only, no content) `[TP-4]`.
3. **Timeout** `[TP-5]`: a missing text becomes the placeholder `(no description)`; a missing drawing is an empty canvas flagged `timedOut`. Placeholders are shown to the next holder (so they still have something to continue) but marked in the album.
4. **REVEAL phase.** The host (or a 15 s auto-advance if host absent) steps through albums; each album shows entries in sequence with author names. Everyone sees the same state; navigation is by `REVEAL_NEXT`/`REVEAL_PREV` from the host only `[TP-6]`. Players can freely browse already-revealed items locally.
5. **RESULTS.** Telephone has no competitive score; the results page lists albums and optional emoji "reactions" tally *(post-MVP)*. Rematch voting follows common rules.

### Telephone edge cases

| Case | Behavior |
|---|---|
| Player disconnects and does not return | Their future holdings are auto-filled with placeholders (`LEFT_PLAYER`); chains continue; the album shows the gap. If active players < 3, game ends early and reveals what exists. |
| Player reconnects mid-step | Receives private snapshot: held entry, own draft state (if any), deadline. |
| Resubmission | The first valid submission is final in MVP `[TP-7]`; later ones are rejected as `DUPLICATE_ACTION`. |
| Oversize drawing | Rejected with `PAYLOAD_TOO_LARGE`; limits: ≤ 600 strokes, ≤ 20 000 points, ≤ 256 KB serialized. |
| Reveal with timed-out entries | Displayed with a "timed out" badge. |

### Persistence

Each submission is stored once (`game_submission`) when accepted: text, or compact stroke JSON. Albums are reconstructed from `game_submission` by `(session_id, chain_index, step_no)` for results viewing after the session ends.

---

## Rule IDs and tests

Every `[XX-n]` rule has at least one unit test whose name includes the ID (e.g. `DG12_guesserPoints_decayWithTime`). Property tests cover `[TP-1]` for n ∈ 3…12 and all E.
