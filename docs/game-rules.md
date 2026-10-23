# DinoParty — Game Rules

All values marked *(default)* are configurable within the stated bounds. The server is authoritative for roles, timers, scores and transitions. Bracketed IDs (e.g. `[DG-12]`) are stable rule references used by tests.

## Common rules

- Players are authenticated guests in a room. Games start only from `WAITING` by the host when `minPlayers ≤ connectedPlayers ≤ maxPlayers` (members whose connection is in its grace period do not count as connected; the `ready` flag is advisory and never blocks a start `[C-4]`).
- Every phase has a server `deadlineAt`, except Telephone's `REVEAL` (`deadlineAt: null`; it is driven by the host with a server auto-advance, `[TP-6]`). Client clocks are display-only.
- Actions carry `actionId` and `phaseId`; duplicates are ignored (idempotent ack), stale ones rejected.
- A disconnected player has a reconnection grace period (30 s lobby / 60 s in-game, default). After it, the player is `LEFT` for the current session (they may rejoin only after the game ends, in the lobby). The in-game value needs its own setting (`app.realtime.in-game-disconnect-grace`, default 60 s); Phase 3 has only the lobby value.
- If the number of players not `LEFT` falls below the game's `minActivePlayers` during play (draw-guess 2, telephone 3, dino-impostor 3, sketchy-saurus 3), the game ends early `[C-1]`: with results computed from the completed rounds if there is at least one, otherwise it is aborted with no results and the room returns to `WAITING`. A player who is merely `DISCONNECTED` (inside the grace period) still counts as active, so a network blip never ends a game.
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
| `difficulty` | mixed | easy/medium/hard/mixed: built-in words of that stored difficulty (mixed = all) |
| `wordSource` | built-in | built-in / custom / combined (`[DG-15]`) |
| `wordCategories` | random-mix | built-in categories, several allowed; Random Mix = all active |
| `wordPackIds` | none | custom word packs (≤ 10) for custom/combined |

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

- **Sources** (WP-02, D33; contract api-contracts §2.4). Built-in words come from the original English word catalog (A14, WP-01, api-contracts §2.3): every word has one category and one stored difficulty `EASY`/`MEDIUM`/`HARD`, 3–20 characters, letters plus single spaces/hyphens, no offensive entries (blocklist test). Custom words come from the host's selected word packs (WP-03). `wordSource` picks built-in, custom or both.
- **Filters.** `wordCategories` (one or more; Random Mix or an empty list = every active category) and `difficulty` (`easy`/`medium`/`hard` = that stored difficulty, `mixed` = all) filter **built-in words only**. Custom pack entries are never filtered by category or difficulty, with or without such metadata; an entry that is not a usable word (shape above) is not used.
- **Pool.** Built-in words first, then packs in the selected order; duplicates removed by `[DG-4]` correct-guess equivalence (first occurrence kept). The pool is resolved and **frozen when the host starts**: settings, catalog or pack changes during a game never affect it.
- **Start check.** The start is rejected, and nothing is initialized, when the pool has fewer distinct words than one draw (3): `WORD_POOL_TOO_SMALL` with the required and available counts; unknown/inactive categories and unavailable or missing packs are `VALIDATION_FAILED`.
- **Draw.** The 3 choices are drawn uniformly without replacement from the pool minus **words already chosen earlier in this session** (offered-but-unchosen words may come back), with the server's cryptographically strong random source. Pool exhaustion falls back to allowing repeats. A new session (rematch) re-resolves the pool from the room's current settings and starts with an empty used set.
- Difficulty never changes scoring (`[DG-12]`/`[DG-13]` are the same for every word).

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

## Game 2 — Dino Telephone (`telephone`)

Players: **4–8**; `minActivePlayers` 3 (`[C-1]`); unscored (`scored = false`, no standings). Even and odd counts are supported. Design, state machine, limits and the list of open decisions: `docs/telephone-design.md`; contract: `api-contracts.md` §3.10. Rules that came from decisions D35–D48 (`decisions-and-risks.md` §7; D41–D45 approved, the rest **proposed**) are tagged with the decision id; where this section replaces earlier text, the replaced wording is named in `telephone-design.md` §1.

### Settings (host, in WAITING only)

| Setting | Default | Bounds |
|---|---|---|
| `promptSeconds` | 60 | 20–120 |
| `drawSeconds` | 90 | 30–180 |
| `describeSeconds` | 45 | 20–90 |
| `entriesPerChain` | 0 (auto) | 0 or 4–8 *(proposed, D38)*; auto = `n` if `n` is even, else `n − 1`; an explicit value is clamped to `n` when the roster freezes |
| `allowSkipTimeout` | true | only `true` is accepted; timeouts always produce an entry `[TP-5]` *(proposed, D39)* |

Constants (not settings): text 1–140 characters, reveal idle auto-advance 60 s (15 s while the host is disconnected), drawing limits of `[DG-14]`.

### Roster, chains and entries

1. **`[TP-1]` Chain allocation (rotation).** Let `n` players be ordered `p0 … p(n−1)` (the engine's shuffle, made once at start, `[C-4]`; `n` is the frozen roster, including members in their grace period). There are `n` chains `c0 … c(n−1)`; chain `c_i` is *owned* by `p_i`. Entry index `k ∈ 0 … E−1`, `E` as in `[TP-2]`. **The holder of chain `c_i` at step `k` is `p_((i + k) mod n)`**, i.e. player `p_j` holds chain `c_((j − k) mod n)`. Entry `0` is a text prompt written by the owner, odd `k` is a DRAW of the previous entry (a text), even `k > 0` is a DESCRIBE of the previous entry (a drawing). The allocation is fixed at start and never recomputed; a player who leaves keeps their index.
   Properties (tested as invariants for `n ∈ 3…12` and every `E ≤ n`):
   1. At every step each player holds exactly one chain and each chain has exactly one holder (the map `i → (i + k) mod n` is a permutation).
   2. A player holds a given chain at most once while `E ≤ n`; the chain they own only at step 0. **Nobody receives a chain they already contributed to, and nobody receives their own previous contribution**: the previous entry of a chain is always written by another player.
   3. A holder is shown only the immediately preceding entry of the chain they hold, and never its author (`[TP-8]`).
   4. Every chain ends with exactly `E` entries.
2. **`[TP-2]` Entries per chain.** `E` is computed once at session start from the frozen `n` and `entriesPerChain` (settings table) and is public (`totalSteps`). Entry 0 is text, so albums end with a drawing when `E` is even and with a description when `E` is odd; the lobby shows "Albums will end with a description." when an explicit odd value (or an odd auto-cap) applies. Auto gives `n = 4, 5, 6, 7, 8 → E = 4, 4, 6, 6, 8` *(proposed, D38)*. The game has `E` step phases followed by the reveal.

Example, `n = 4`, `E = 4`:

| step k | c0 | c1 | c2 | c3 | type |
|---|---|---|---|---|---|
| 0 | p0 | p1 | p2 | p3 | PROMPT |
| 1 | p1 | p2 | p3 | p0 | DRAW |
| 2 | p2 | p3 | p0 | p1 | DESCRIBE |
| 3 | p3 | p0 | p1 | p2 | DRAW |

### Phase flow

Phases (names are the engine's `phase` values), in order: `PROMPT` → `DRAW` / `DESCRIBE` alternating → … → `REVEAL`. Every step phase has a fresh `phaseId`; `REVEAL` is a single phase.

3. **`[TP-3]` Prompt phase** (`PROMPT`, `promptSeconds`, step 0). Every player submits a text prompt (1–140 characters). A player without a submission at the deadline gets: their draft text if non-empty, else a prompt from the **prompt pool** — built-in catalog words of difficulty `EASY` ∪ `MEDIUM`, frozen at host start, drawn without replacement *(proposed, D46)*; such an entry is marked `fill: AUTO_PROMPT`. There is no word choice and no hint.
4. **`[TP-4]` Step phases** (`DRAW` for odd `k`, `DESCRIBE` for even `k > 0`; steps `1 … E−1`). All players advance **together** (lockstep) *(proposed, D35)*: step `k + 1` starts only when step `k` has ended. At the start each non-`LEFT` holder receives privately only the preceding entry of the chain they hold (`[TP-8]`) and submits the next one. A step ends when every *eligible holder* has submitted (`reason: ALL_SUBMITTED`, in the command that completes it, no grace) or its deadline passes (`reason: TIME`, `[C-6]`). Eligible holders are the roster members not `LEFT`; a `DISCONNECTED` holder stays eligible, and so blocks the early end, only during the in-game reconnection grace (60 s); when it expires the engine marks them `LEFT`, their cell is filled and the step is re-evaluated at once *(proposed, D48)*. The next step starts in the same mailbox command with a new deadline measured from that instant. Holders who submitted see a **waiting screen** with the names of those who have (`TELEPHONE_PROGRESS`; names only, no content).
5. **`[TP-5]` Timeouts and auto-fill.** Every cell (chain, step) ends with exactly one entry. At the deadline a holder without a submission is filled from their **server-held draft** if it is non-empty (a partial drawing or half-typed text is kept, `fill: TIMEOUT`, `timedOut: true`) *(proposed, D37)*; with an empty draft: DESCRIBE → the text `(no description)`, DRAW → an empty canvas, both `placeholder: true`, `fill: TIMEOUT`. A holder who is already `LEFT` when a step starts is filled immediately at step start (`fill: LEFT_PLAYER`; in `PROMPT`, `AUTO_PROMPT`); a holder who becomes `LEFT` during a step is filled at once from their draft or a placeholder, and the step is re-evaluated. A placeholder is passed on as the next holder's source, flagged `placeholder: true`, and shown in the album with a badge *(proposed, D40)*. `allowSkipTimeout` has no other effect `[TP-9]`.
6. **`[TP-6]` Reveal** (`REVEAL`, no phase deadline). After the last step the server reveals the albums in chain order `c0 … c(n−1)`; each album shows its entries in order `0 … E−1` with the author's name and the badges of `[TP-5]`. The phase starts with entry `(0,0)` revealed. The **host** (the current host at command time; `NOT_HOST` for anyone else) steps with `REVEAL_NEXT` / `REVEAL_PREV`; both carry the cursor the host saw and a mismatch is `STALE_ACTION`, so double clicks and races with the auto-advance never skip an entry. `REVEAL_PREV` moves the shared cursor back through already revealed entries (a no-op at the first); `REVEAL_NEXT` past the furthest revealed entry reveals the next (the first of the next album after an album's last). If the host does not act, an auto-advance performs `NEXT` after 60 s, or 15 s while the host is `DISCONNECTED` *(D42)*. `REVEAL_NEXT` on the last entry of the last album finishes the game (`[C-10]`). Everyone sees the same cursor; players can browse already received entries locally without moving it. Drawing playback is a client-side animation of the stroke order, skippable and replaced by the final image under reduced motion. After the game the albums are browsable freely from the results screen (`[TP-11]`).
7. **`[TP-7]` Submissions are final.** The first valid `TELEPHONE_SUBMIT_TEXT`/`TELEPHONE_SUBMIT_DRAWING` of a holder in a step is final; any further input of theirs in that step (second submit, draft or drawing commands) is rejected `ALREADY_SUBMITTED` *(replaces `DUPLICATE_ACTION`, which means the same `actionId`, D36)*. Re-opening a submission is post-MVP.
8. **`[TP-8]` Private assignments and secrecy.** The source entry reaches only its next holder: in the private `TELEPHONE_TASK` event and in that holder's own snapshot; it is never in a public event, another player's snapshot, an error or a log. The source's author is not sent. Earlier entries of the chain, other players' tasks, drafts and submissions are never sent. All entries become visible only through `ALBUM_REVEALED` in `REVEAL`, and through the album endpoints after the game is `FINISHED`. Drafts and drawing batches are not echoed to anyone.
9. **`[TP-9]` Drafts and drawing.** A holder's work in progress lives on the server: drawing through the Draw & Guess stroke protocol (`TELEPHONE_DRAW_STROKE`/`UNDO`/`CLEAR`, the limits and rejection rules of `[DG-14]`: ≤ 200 points/batch, 8 KiB, 2000 points/stroke, 300 strokes, **12 000 live points**, whole-batch `PAYLOAD_TOO_LARGE`), text through `TELEPHONE_TEXT_DRAFT` (last write wins, ≤ 140 characters); `TELEPHONE_SUBMIT_DRAWING{}` commits the draft. A refresh or reconnect restores the draft from the snapshot. The earlier one-shot `strokes` payload and the 600 strokes / 20 000 points / 256 KB limit are dropped *(proposed, D36)*; strokes are integers 0–10000 (D28). An explicit empty drawing is a valid submission.
10. **`[TP-10]` Disconnection and leaving.** `DISCONNECTED` within the grace changes nothing for the game (task and draft stay on the server). A player who becomes `LEFT` (grace expired, left, kicked) is filled per `[TP-5]` for the current and every later step; their entries stay in the albums with `fill: LEFT_PLAYER`; their earlier submissions stand. Host leaving transfers hosting (`[C-2]`); the new host drives the reveal.
11. **`[TP-11]` Early end and results.** If non-`LEFT` players fall below 3 the engine ends the game `[C-1]`: with results if at least one step completed (a step in progress is discarded as a round, its already accepted submissions are kept), otherwise aborted. **No reveal follows an early end** — the albums that exist are available from `RESULTS` on and are flagged incomplete *(D41; replaces "reveals what exists")*; during a running `REVEAL` the same rule ends the reveal. Standings are empty; each completed step is one recorded round (`roundNo = step + 1`).
12. **`[TP-12]` Timers.** `[C-6]` applies: a due deadline is applied before any command; a command processed at/after its phase's deadline is `STALE_ACTION`. No latency grace — the server-held draft makes one unnecessary.

| Phase | Duration | Advertised `deadlineAt` | Ends early when |
|---|---|---|---|
| `PROMPT` | `promptSeconds` | start + duration | all eligible holders submitted |
| `DRAW` | `drawSeconds` | start + duration | same |
| `DESCRIBE` | `describeSeconds` | start + duration | same |
| `REVEAL` | — | `null` | last entry of the last album passed (auto-advance 60 s / 15 s) |

13. **`[TP-13]` Rematch and persistence.** Rematch is the engine's (`[C-3]`, `[C-11]`): same members, host, game and settings, new session with a **new shuffle** (so new chains and neighbours), `E` re-resolved against the new roster, nothing carried over. Each accepted submission is stored once (`game_submission`, never dropped) with its text or compact stroke JSON; albums are rebuilt from `(session_id, chain_index, step_no)` for as long as the session's room exists, for any current member of that room (as for results), and stay readable after the rematch reset *(D44, D45)*. `ABORTED`/`INTERRUPTED` sessions expose no albums.
14. **`[TP-14]` Limits.** Text: control and invisible format characters (`Cc`/`Cf`) stripped, trimmed, 1–140 Unicode code points; rendered as text, never HTML. Drawings: `[TP-9]`. Rates: strokes 30/s (burst 40), text/draft 2/s, reveal 5/s.

### Telephone edge cases

| Case | Behavior |
|---|---|
| Player disconnects and does not return | After the 60 s grace they are `LEFT`: `[TP-10]`; chains continue with `LEFT_PLAYER` entries shown in the album. Fewer than 3 active: `[TP-11]`. |
| Player reconnects mid-step | Snapshot: held task, own draft, `submitted`, deadline (`[TP-9]`). |
| Everybody submitted except a `DISCONNECTED` player | The step waits for their return, their `LEFT` or the deadline (`[TP-4]`). |
| Submit arrives at/after the deadline | Deadline first: the next phase exists and the command is `STALE_ACTION`; the draft already became the entry (`[TP-5]`). |
| Second submit / draw after submitting | `ALREADY_SUBMITTED` (`[TP-7]`). |
| Wrong kind for the phase (text during DRAW, strokes during DESCRIBE) | `INVALID_PHASE` (current `phaseId`), stale ones `STALE_ACTION`. |
| Oversize drawing | A batch exceeding a budget is rejected whole, `PAYLOAD_TOO_LARGE` (`[TP-9]`); the draft is unchanged. |
| Text empty after trimming / over 140 | `VALIDATION_FAILED`. |
| `REVEAL_*` from a non-host | `NOT_HOST`. |
| Double `REVEAL_NEXT` | The second carries the old cursor: `STALE_ACTION`. |
| `REVEAL_PREV` at the first entry | Acknowledged no-op. |
| Reveal with placeholders / timed-out entries | Badges `placeholder` / `timedOut` / `LEFT_PLAYER` / `AUTO_PROMPT`. |
| Host leaves in `REVEAL` | New host (`[C-2]`) drives it. |
| Rematch | `[TP-13]`. |

### Persistence

Albums are reconstructed from `game_submission` by `(session_id, chain_index, step_no)`: one row per accepted or auto-filled cell, written at accept time through the never-drop recorder path (`telephone-design.md` §12). Chat does not exist in Telephone.

---

## Game 3 — Dino Impostor (`dino-impostor`)

Players: **4–10**; `minActivePlayers` 3 (`[C-1]`); scored. Every round one player, the **impostor**, does not know the secret word; the others (**artists**) do. Players add one stroke at a time to one shared canvas, then vote for whoever they think is the impostor. Plan and decisions: `docs/dino-impostor-plan.md`, D50–D62 (`decisions-and-risks.md` §9; approved rows marked there, the rest **proposed**); contract: `api-contracts.md` §3.11. Rules marked *(shared)* also apply to Game 4 (Sketchy Saurus), which shares their code.

### Settings (host, in WAITING only)

| Setting | Default | Bounds |
|---|---|---|
| `rounds` | 0 (auto) | 0 or 3–10; auto = the number of players when the roster freezes, clamped to 3–10 *(D59)* |
| `laps` | 2 | 1–3 *(D54)* |
| `turnSeconds` | 25 | 10–60 *(D54)* |
| `voteSeconds` | 60 | 30–120 *(D57)* |
| `guessSeconds` | 30 | 15–60 *(D58)* |
| `difficulty` | `mixed` | `easy`, `medium`, `mixed` (= `EASY` ∪ `MEDIUM`) *(D60)* |

Constants (not settings): role reveal 5 s, round end 8 s, vote early-close minimum 15 s, chat 1–120 characters, drawing limits of `[DG-14]`.

### Rules

1. **`[DI-1]` Players** *(shared)*. 4–10 connected players to start; the game continues while at least 3 roster members are not `LEFT` (`[C-1]`).
2. **`[DI-2]` Rounds and words** *(shared)*. The effective round count `R` is resolved when the roster freezes. At host start `prepare` draws `R` distinct words from the built-in catalog at the set difficulty, each with its category (rooms carry no locale yet: the catalog's default locale, as for Draw & Guess). The start comes before the roster freezes, so with `rounds = 0` it draws the auto maximum of 10 words and the game uses the first `R`; a pool that cannot supply them (Sketchy Saurus: plus decoys, `[SS-3]`) rejects the start with `WORD_POOL_TOO_SMALL` *(D60)*. Words are drawn with the injected `RandomSource`, never the session seed.
3. **`[DI-3]` Impostor rotation** *(shared)*. At each round start exactly one impostor is chosen among the roster members not `LEFT` who have been impostor the fewest times so far, uniformly at random among them (`RandomSource`) *(D52)*. Over `R = k·n` rounds with nobody leaving, everyone is impostor exactly `k` times; in general everyone's count differs by at most 1.
4. **`[DI-4]` Roles and secrecy** *(shared)*. At `ROLE_REVEAL` every player not `LEFT` privately receives `IMPOSTOR_ROLE` in the same transition: artists `{role:ARTIST, category, word}`, the impostor `{role:IMPOSTOR, category, word:null}` (Sketchy Saurus exception: `[SS-3]`). The word and the decoy are in no public event before `IMPOSTOR_ROUND_ENDED`; the impostor's identity in none before the vote result (`IMPOSTOR_VOTE_CLOSED`, which names them only when caught) or `IMPOSTOR_ROUND_ENDED`; and none of them is ever in another viewer's snapshot, an error or a log line. Spectators and outsiders get no role and see the category only at the reveal.
5. **`[DI-5]` Seat order.** Each round the seat order is a fresh shuffle of the players not `LEFT` (session random source); if the impostor lands in seat 0 they are moved to seat 1 and the others keep their order *(D55)*. The order is public.
6. **`[DI-6]` Turns and strokes.** A round has `laps × n` turns in seat order (`n` = seated players, fixed at round start; turn `t` is seat `t mod n` in lap `⌊t / n⌋ + 1`); each turn is its own `DRAWING` phase with a fresh `phaseId`. Only the player whose turn it is may draw (`NOT_DRAWER` otherwise); they draw **one** stroke: `PENCIL` only, in their own session colour (`VALIDATION_FAILED` otherwise), within the `[DG-14]` limits; a second stroke in the same turn is `ALREADY_SUBMITTED`. A drawer who reconnects mid-turn learns the state of their stroke from the snapshot's `turnStroke` and may continue an open stroke. There is no undo or clear. Every accepted batch is published live with its author *(D54, D56)*. A batch carrying an earlier turn's `phaseId` is `STALE_ACTION`.
7. **`[DI-7]` Turn end.** A turn ends when the drawer's stroke receives its `final` batch, or at the turn deadline (`turnSeconds`; an open stroke is closed as it is, no stroke = an empty turn). A drawer who is `DISCONNECTED` keeps the turn until its deadline; one who is `LEFT` is skipped at once (their stroke so far stays, closed), and so are their later turns of the round. `[C-6]` applies to every turn deadline.
8. **`[DI-8]` Chat** *(shared)*. `IMPOSTOR_CHAT` only in the voting phase (`VOTE`, Sketchy Saurus `GALLERY`); public; text 1–120 code points after stripping `Cc`/`Cf` and trimming; 5 per 10 s per player; rendered as text, never HTML. No chat in any other phase *(D57)*.
9. **`[DI-9]` Voting** *(shared)*. In the voting phase (`voteSeconds`) every player not `LEFT` may vote for one other player not `LEFT`; self-votes are `VALIDATION_FAILED`. A vote can be changed or withdrawn (`target:null`) until the phase ends. Others see only who has voted, never for whom. The phase ends at its deadline, or early when every player not `LEFT` has a vote in **and** at least 15 s of the phase have passed (checked on each vote and by a deadline at the 15 s mark). A player who becomes `LEFT` loses their vote and every vote for them is dropped *(D57)*. When the phase ends, every vote is published, with the accused player (strict top) and, if caught, the impostor.
10. **`[DI-10]` Caught or escaped** *(shared)*. The impostor is **caught** when they have strictly more votes than any other player; a tie at the top, or no votes at all, means they **escaped**.
11. **`[DI-11]` Guess** *(shared)*. A caught impostor who knew their role gets `GUESS` (`guessSeconds`) and one `IMPOSTOR_GUESS`. It is correct when it equals the word after the shared normalization (the Draw & Guess matcher's rules, moved to `words`); "close" counts as wrong; no guess by the deadline is wrong *(D58)*. Sketchy Saurus: `[SS-3]`.
12. **`[DI-12]` Scoring** *(shared)*. Per round: escaped → impostor +2; caught and guessed right → impostor +2; caught and wrong (or no guess allowed) → every artist +1, plus +1 for each artist whose final vote was the impostor. "Every artist" means the artists seated this round who are not `LEFT` at the end of the round. Nobody else scores. Totals and ranking are the engine's (`[C-8]`; tie-break: the session order) *(D59)*.
13. **`[DI-13]` Leaving and disconnects** *(shared)*. `DISCONNECTED` inside the grace changes nothing. If the **impostor** becomes `LEFT` during a round, the round ends at once with outcome `IMPOSTOR_LEFT`, no points, and the reveal. Others who leave are dropped from the seating, votes and chat; fewer than 3 not `LEFT` ends the game per `[C-1]` (results if at least one round completed) *(D61)*. An impostor who leaves during `ROUND_END` changes nothing: the round has already ended.
14. **`[DI-14]` Reveal** *(shared)*. `ROUND_END` (8 s) publishes `IMPOSTOR_ROUND_ENDED` with the impostor, word, category, decoy (Sketchy Saurus, else `null`), every final vote, the guess and the points; snapshots in `ROUND_END` carry the same. Then the next round's `ROLE_REVEAL`, or after round `R` the game finishes (`[C-10]`). Rematch per `[C-3]`; rotation counts start again.

### Phase flow

| Phase | Duration | Ends early when |
|---|---|---|
| `ROLE_REVEAL` | 5 s | — |
| `DRAWING` (one phase per turn) | `turnSeconds` | the drawer's stroke receives its `final` batch, or the drawer is `LEFT` |
| `VOTE` | `voteSeconds` | everyone voted and ≥ 15 s passed |
| `GUESS` | `guessSeconds` | the guess arrives |
| `ROUND_END` | 8 s | — |

### Dino Impostor edge cases

| Case | Behavior |
|---|---|
| Stroke from a player whose turn it is not | `NOT_DRAWER`; canvas unchanged. |
| Second stroke in a turn | `ALREADY_SUBMITTED`. |
| `ERASER`, or a colour other than the sender's | `VALIDATION_FAILED`. |
| Batch after the turn deadline | `STALE_ACTION` (`[C-6]`); the stroke was closed as it stood. |
| Drawer leaves mid-turn | Their stroke so far stays; the next turn starts at once. |
| Impostor leaves | `IMPOSTOR_LEFT`, no points, reveal, next round. |
| Vote for self / for a player who left | `VALIDATION_FAILED`. |
| Tie at the top of the vote | Escaped (`[DI-10]`). |
| Reconnect in any phase | Snapshot: own role, strokes with authors, turn, own vote, voted list, chat, reveal. |

---

## Game 4 — Sketchy Saurus (`sketchy-saurus`)

Players: **4–10**; `minActivePlayers` 3; scored. The same family as Game 3 — `[DI-1]`–`[DI-4]` and `[DI-8]`–`[DI-14]` apply unchanged — but everyone draws at the same time on their own hidden canvas, and the voting happens over a gallery of all drawings. Decisions D50–D64; contract `api-contracts.md` §3.12.

### Settings (host, in WAITING only)

| Setting | Default | Bounds |
|---|---|---|
| `rounds`, `voteSeconds`, `guessSeconds`, `difficulty` | as Game 3 | as Game 3 |
| `sketchSeconds` | 60 | 30–120 |
| `impostorKnows` | `true` | bool *(D63)* |
| `unawareGuess` | `false` | bool; ignored when `impostorKnows` is `true` *(D63)* |

### Rules

1. **`[SS-1]` Phases.** `ROLE_REVEAL` (5 s) → `SKETCH` (`sketchSeconds`) → `GALLERY` (the voting phase of `[DI-9]`, `voteSeconds`) → `GUESS` when `[DI-11]`/`[SS-3]` allow it → `ROUND_END` (8 s).
2. **`[SS-2]` Sketching.** In `SKETCH` every player not `LEFT` draws on their own private canvas with `SKETCHY_DRAW_STROKE`/`UNDO`/`CLEAR` (full toolset, any colour) within ≤ 1 500 live points and 100 strokes per drawing (other `[DG-14]` limits unchanged) *(D64)*. Nothing of a drawing reaches anyone else before `GALLERY`; batches are not echoed. `SKETCHY_SUBMIT` makes the drawing final (`ALREADY_SUBMITTED` for further input). The phase ends at its deadline or when every player not `LEFT` has submitted; at the deadline each unsubmitted draft becomes that player's drawing as it stands; an empty drawing is shown as "no drawing". `GALLERY` publishes every drawing with its artist.
3. **`[SS-3]` Impostor awareness** *(D63)*. With `impostorKnows = true` the impostor is told their role (`[DI-4]`) and a caught impostor guesses (`[DI-11]`). With `impostorKnows = false` the impostor receives `{role:ARTIST, category, word:<decoy>}` — the decoy is a different catalog word of the same category and difficulty (different after normalization, used nowhere else in the session) — and their events, snapshot and screens have exactly the shape of an artist's. If caught: with `unawareGuess = true` they privately receive `IMPOSTOR_GUESS_OPEN` (which first tells them their role) and guess per `[DI-11]`; otherwise there is no `GUESS` phase and the round scores as caught and wrong (`[DI-12]`).

### Sketchy Saurus edge cases

| Case | Behavior |
|---|---|
| Drawing command after submitting | `ALREADY_SUBMITTED`. |
| Budget exhausted | Whole batch rejected `PAYLOAD_TOO_LARGE`; draft unchanged. |
| Player disconnects in `SKETCH` | Draft kept on the server; restored from the snapshot on return; becomes their drawing at the deadline. |
| Player `LEFT` in `SKETCH` | No drawing for them in the gallery; the early end is re-evaluated. |
| Reconnect in `GALLERY` | Snapshot carries the whole gallery. |
| Unaware impostor caught, `unawareGuess = false` | Straight to `ROUND_END`; artists score. |

---

## Rule IDs and tests

Every `[XX-n]` rule has at least one unit test whose name includes the ID (e.g. `DG12_guesserPoints_decayWithTime`). Property tests cover `[TP-1]` for n ∈ 3…12 and every E ≤ n; Telephone rules are `[TP-1]…[TP-14]`. Dino Impostor rules are `[DI-1]…[DI-14]` (the ones marked *shared* are tested in both impostor games) and Sketchy Saurus rules `[SS-1]…[SS-3]`; a property test covers `[DI-3]` for n ∈ 4…10 and R ∈ 3…10.
