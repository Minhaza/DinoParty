# Proposal — Fake Artist Online as a Third Game

Status: **superseded** by `docs/dino-impostor-plan.md` (Phase 9): the idea became two games, Dino Impostor and Sketchy Saurus; §4's questions are answered there and in `decisions-and-risks.md` §9 (D50–D64). Kept as the record of the original proposal.

## 1. What is known

From the brief we only know the working title "Fake Artist Online" and that it is to be a third game on the same platform. Everything else (turn count, voting, role reveal, scoring) is an **open decision** (§4). The notes below describe *capabilities* the platform needs, not rules.

> Caution: "A Fake Artist Goes to New York" is a commercially published tabletop game. Per the master prompt all names, art, words and text must be original, so the final title and rules need an originality/IP review (D10) before building.

## 2. Fit with the existing architecture

No change to the room system, envelope, persistence tables, or existing games is required.

| Concern | How it fits |
|---|---|
| Registration | New `games.fakeartist` package implementing `GameModule`; Flyway data migration inserting a `game_definition` row (`fake-artist`, enabled=false until ready). |
| Private roles/words | Existing private stream (`/user/queue/rooms/{code}`) delivers per-player role payloads; snapshot builder is already viewer-specific. |
| Shared canvas | Reuses the `drawing` module (stroke model, validation, buffer). Needs one new capability: a **per-turn stroke allowance** (a player may add at most N strokes when it is their turn). Implemented as a validator policy in the game module, not in `drawing`. |
| Turn order | Engine turn-order utility (also used by Draw & Guess). |
| Voting | Engine `VoteService` (introduced in Phase 4 for rematch) generalized to "each player votes for a target player". Built generically in Phase 4 so this game requires no engine change. |
| Timers/phases | Standard engine deadlines and phase IDs. |
| Persistence | `game_round` / `game_submission` / `game_result` with game-specific JSONB (`meta`, `detail`). No new tables. |
| Events | New command/event types prefixed `FAKEARTIST_*` per the extension rule in `api-contracts.md` §3.6; envelope unchanged. |
| Frontend | New `features/fakeartist/` + one entry in the game plugin registry (lazy-loaded); reuses canvas engine, scoreboard, countdown, player cards. Differences: per-stroke colour identity per player, turn indicator, voting UI. |

Prerequisites to keep this cheap (add to the existing phases, no scope creep):
1. Phase 4: make `VoteService` target-based, not just boolean.
2. Phase 5: keep the canvas engine's "who may draw now" as an input prop, not hard-coded to "the drawer".
3. Phase 5: keep stroke ownership (`authorGuestId`) on the stroke model.

Estimated effort once rules are fixed: ~1 phase (backend module + tests ≈ 40 %, frontend ≈ 60 %).

## 3. Proposed integration steps (after approval)

1. Rules finalization and originality review → update `game-rules.md` (new section) and `api-contracts.md`.
2. CC: module, word/category data, unit tests with rule IDs `[FA-*]`, WS IT.
3. AG: feature folder, voting/turn UI, Playwright scenario.
4. Enable the `game_definition` row.

## 4. Unresolved gameplay decisions (do not assume answers)

| ID | Question |
|---|---|
| FA-Q1 | Final name and IP/originality review outcome (D10). |
| FA-Q2 | Player count range. |
| FA-Q3 | Role assignment: how many fake artists (always 1? scaling?), and how is the fake chosen (uniform random vs. fair rotation)? |
| FA-Q4 | What do real artists see (word, category, both) and what does the fake see (nothing, category only)? |
| FA-Q5 | Number of drawing rounds, and strokes allowed per player turn. |
| FA-Q6 | Turn order: fixed vs. re-randomized per round; is the fake's seat position randomized? |
| FA-Q7 | Whether each player has a distinct colour and whether strokes are attributable to players during play. |
| FA-Q8 | Can players talk/chat during drawing? If yes, free chat or none (suspicion discussed off-platform)? |
| FA-Q9 | Voting: timing, simultaneous vs. open, ties, majority threshold, ability to abstain or change vote. |
| FA-Q10 | What happens if the fake artist is caught: do they get a chance to guess the word? How is a correct guess validated (exact, fuzzy)? |
| FA-Q11 | Scoring model for each outcome (fake caught/uncaught/guessed word, per-round vs. per-game points). |
| FA-Q12 | Number of rounds per session and role rotation between rounds. |
| FA-Q13 | Word/category source: curated lists, difficulty levels, and original content authoring. |
| FA-Q14 | Disconnection handling: what if the fake artist or an in-turn player leaves; minimum players to continue. |
| FA-Q15 | Spectators/late joiners and accessibility of colour-coded strokes (colour-blind support). |
| FA-Q16 | Reveal presentation: how and when roles are shown at the end. |
