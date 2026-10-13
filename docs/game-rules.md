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

Players: 2–10 (default max 8). Rounds: one turn per player per "cycle"; `cycles` *(default 2)* → total rounds = players × cycles.

### Settings (host, in WAITING only)

| Setting | Default | Bounds |
|---|---|---|
| `cycles` | 2 | 1–5 |
| `drawSeconds` | 80 | 30–180 |
| `wordChoiceSeconds` | 15 | 5–30 |
| `wordChoices` | 3 | fixed 3 in MVP |
| `hintReveal` | on | on/off: reveal letters progressively |
| `difficulty` | mixed | easy/mixed/hard word pool |

### Round flow

1. **`[DG-1]` Drawer selection.** Fair rotation: the order is a shuffled permutation of players made at game start; each cycle uses the same order. A player who left is skipped. A late-rejoining player does not re-enter mid-game.
2. **`[DG-2]` Word choice phase** (`wordChoiceSeconds`). The server draws 3 distinct words (not used earlier in this session) and sends them **privately** to the drawer. If the drawer does not pick before the deadline, the server picks one at random.
3. **`[DG-3]` Drawing phase** (`drawSeconds`). The drawer's word is sent privately to the drawer. Others receive a hint of underscores and lengths (e.g. `_ _ _ _  _ _ _`), with spaces/hyphens shown. If `hintReveal` is on, one random letter is revealed at 50 % and again at 75 % of elapsed time (never more than ⌈len/2⌉ letters).
4. **`[DG-4]` Guessing.** Every non-drawer who has not yet guessed correctly may submit guesses. Guess normalization: Unicode NFKC, lowercase, trim, collapse whitespace, strip punctuation; accents folded. Match is exact on normalized text, or Levenshtein distance ≤ 1 for words ≥ 6 letters is reported as `CLOSE` (private feedback "so close!", **not** accepted) `[DG-5]`.
5. **`[DG-6]` Wrong guesses** are broadcast as chat lines (with author). **Correct guesses** are broadcast only as "<name> guessed the word!" (word withheld); the guesser is then muted for guesses and their further chat is visible only to other correct guessers and the drawer `[DG-7]`.
6. **`[DG-8]` Drawer restrictions.** The drawer can only draw/undo/clear; cannot guess; their chat is disabled. Other players cannot send drawing commands.
7. **`[DG-9]` Round end** when (a) time expires, or (b) all connected non-drawer players have guessed correctly (end immediately after 2 s grace to show result), or (c) the drawer permanently leaves (round aborted, no points to anyone) `[DG-10]`. The word is revealed to all at round end; a short `ROUND_ENDED` summary (word, points) is shown for 6 s.
8. **`[DG-11]` Next round** or game end after the final round.

### Scoring (defaults)

- Let `T = drawSeconds`, `t_rem` = remaining seconds when the guess is accepted (server clock), `order` = 0-based index among correct guessers.
- **Guesser points** `[DG-12]`: `round(100 + 400 × t_rem / T) − 25 × order`, minimum 50.
- **Drawer points** `[DG-13]`: `round(100 × correctGuessers / (eligibleGuessers))` + `50` if everyone guessed; 0 if nobody guessed. (`eligibleGuessers` = connected non-drawers at round start.)
- Scores are integers computed server-side; clients never submit scores.
- Ties in the final ranking share rank; tiebreaker display order: total correct guesses, then earliest join.

### Canvas and drawing constraints

- Logical canvas aspect fixed (16:10); coordinates normalized 0–1. Tools: pencil, eraser, 12-color palette, 4 brush sizes, clear, undo (last stroke).
- Limits `[DG-14]`: ≤ 200 points per batch, ≤ 2000 points per stroke, ≤ 300 strokes per round, ≤ 32 KB per message, rate ≤ 30 batches/s per drawer.
- Undo removes the drawer's last stroke; clear empties the board. Both are broadcast and part of the snapshot state.

### Draw & Guess edge cases

| Case | Behavior |
|---|---|
| Drawer disconnects mid-round | Grace period runs; if they return, round continues with current deadline. If grace expires: round aborted `[DG-10]`. |
| Guesser disconnects | Their eligibility removed after grace expires; may end the round early if all remaining have guessed. |
| Guess arrives at/after deadline | Accepted only if the server processes it before `deadlineAt` (the engine's `[C-6]`: due deadlines are applied first, so the phase has already changed and the command is `STALE_ACTION`). "Processes" means the room-mailbox time, not network arrival. |
| Simultaneous correct guesses | Serialized by the room mailbox; earlier processed = better `order`. |
| Same word twice in a session | Prevented by the used-words set; pool exhaustion falls back to repeats. |

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
