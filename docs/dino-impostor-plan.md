# DinoParty — Impostor Games Plan: Dino Impostor and Sketchy Saurus (Phase 9)

Status: **plan; P9-01 (rules, contract, decisions) written** — see `decisions-and-risks.md` §9, `game-rules.md` Games 3–4, `api-contracts.md` §3.11–§3.12. Replaces the open list in `fake-artist-proposal.md` §4. Answers marked **USER** were chosen by the user on 2026-10-22; every other answer is **PROPOSED** (to become D50–D64 in `decisions-and-risks.md` in P9-01) and is applied below as the recommendation. Nothing is built until P9-01 is merged.

Inputs read: `fake-artist-proposal.md`, `game-engine.md` (SPI §1.3, turns §6.2), `telephone-design.md` (the pattern this plan follows), `api-contracts.md` §2/§3.6/§3.9, the `engine`, `drawing` and `games.*` packages, and `frontend/src/features/{drawing,games,drawguess}`.

## 1. Two games, one family

The fake-artist idea becomes **two separate games** in the lobby (USER), built on shared code. A host who wants the other one switches the waiting room's game (`PATCH /rooms/{code}/settings {gameKey}` already allows it in `WAITING`).

In both games, each round every player but one gets a secret **word** and its **category**, and the **impostor** doesn't get the word. Everyone draws, then talks in a short chat and votes for whoever they think is the impostor. Points add up over several rounds, and a different player is the impostor each time.

| | **Dino Impostor** (`dino-impostor`) | **Sketchy Saurus** (`sketchy-saurus`) |
|---|---|---|
| Drawing | Players take turns adding **one stroke each** to **one shared canvas**, for two laps, each in their own colour. Everyone watches live. | Everyone draws **at the same time on their own canvas**, hidden from the others, until time runs out or everyone has submitted. |
| Then | Chat and vote while looking at the shared canvas. | All drawings are shown side by side with their artist's name; chat and vote. |
| Impostor's clue | The category only; they know they are the impostor. | Setting `impostorKnows` (D63). **On:** the category only, and they know they are the impostor. **Off:** a **decoy word** from the same category, and they are told they are an ordinary artist, so they don't know they are the impostor. |
| Caught impostor | One guess at the word. | One guess if they knew. If they didn't, setting `unawareGuess` (USER: optional, default off) decides: **off** → no guess; **on** → they are told they were the impostor and get one guess at the real word. |
| Feel | Slow bluffing; every stroke is a clue. | Quick; the tension is in the reveal. |

Why two games rather than one game with a mode setting (D62): different settings for each (laps/turn timer vs. sketch timer/`impostorKnows`) that the generic settings form can't show conditionally; each can be enabled and shipped on its own; rules and tests stay separate; players understand "pick a game" better than a mode switch. The shared parts (rotation, voting, chat, scoring, reveal) are written once.

## 2. Answers to the open questions (FA-Q1…FA-Q16)

"Both" = both games; **DI** = Dino Impostor only; **SS** = Sketchy Saurus only.

| ID | Answer | Source |
|---|---|---|
| FA-Q1 | Names **Dino Impostor** (`dino-impostor`) and **Sketchy Saurus** (`sketchy-saurus`). Original copy, art and badges; no reference to the tabletop title anywhere in the product. The D10 review moves to P9-01: a checklist (names, rules text, UI copy, art) signed off by the user. | USER; D50 |
| FA-Q2 | Both: **4–10 players** at start (with 3 the vote is close to a coin flip); the game keeps going while ≥ 3 are active. | D51 |
| FA-Q3 | Both: **exactly 1 impostor** per round, chosen by **fair rotation**: from the players who have been impostor the fewest times, at random among ties, using the injected `RandomSource` (not the session seed, so it can't be worked out from stored data; same reasoning as D&G words). | USER (rotation); D52 |
| FA-Q4 | Both: real artists get category + word. DI and SS with `impostorKnows` on: the impostor gets the category + "you are the impostor". SS with `impostorKnows` off: the impostor gets a decoy word and the same artist screen as everyone else. | D53, D63 |
| FA-Q5 | DI: **2 laps**, **1 stroke per turn** (setting `laps` 1–3). A turn ends on the final batch of your one stroke, or when the turn timer runs out (`turnSeconds`, default 25, 10–60) — then it is an empty turn. Strokes stream live. SS: one drawing each, `sketchSeconds` default 60 (30–120), full toolset (eraser, undo, clear, any colour) because the canvas is your own; ends early once everyone active has submitted. | D54 |
| FA-Q6 | DI: seat order is shuffled again each round; the impostor is **never first** in lap 1 (going first with no information is an instant tell). SS: no turns. | D55 |
| FA-Q7 | DI: every player gets a **fixed colour** for the session from a 10-colour palette that colour-blind users can tell apart, plus an **initial badge** in the roster and a stroke legend. Only `PENCIL`; no eraser, undo or clear (that would edit others' work). The server rejects a batch whose colour isn't the sender's. SS: each drawing is labelled with its artist. | D56 |
| FA-Q8 | Both: **no chat while drawing**; a public discussion chat is open only during voting. | USER |
| FA-Q9 | Both: voting phase, `voteSeconds` default 60 (30–120). **Secret** votes: others only see *who has voted*. You can change your vote until the phase closes; you can't vote for yourself; not voting is abstaining. Ends early when every active player has a vote in **and** at least 15 s have passed. **Caught** = the impostor has strictly the most votes; a tie at the top means the impostor escapes. | D57 |
| FA-Q10 | **USER: one guess.** SS with `impostorKnows` off: a guess only when `unawareGuess` is on (USER: optional; default off, because an impostor who never knew had no reason to hide). Guess phase `guessSeconds` default 30. Matched by the existing D&G normaliser (case, accents, spacing, punctuation); exact after normalising = correct, "close" doesn't count; timeout = wrong. | USER; D58 |
| FA-Q11 | Both, per round: impostor escapes → impostor **+2**. Caught and guesses right → impostor **+2**. Caught and wrong (or no guess) → each real artist **+1**, and **+1 more** for each real artist who voted for the impostor. Totals use the existing `ScoreLedger`/`Ranking`. | D59 |
| FA-Q12 | **USER: N rounds, rotating impostor.** Setting `rounds`: `0` (auto, the default) = the number of players at start, clamped 3–10; or 3–10. | USER |
| FA-Q13 | Both: words from the built-in **word catalog** (`word_category`, `EASY` ∪ `MEDIUM`, room locale); the category label is the impostor's clue. No word used twice in a session. SS decoys: a different word from the same category and difficulty, never used elsewhere in the session; `prepare` refuses to start if a category has too few words. Custom word packs aren't supported in v1 (they have no categories). | D60, D63 |
| FA-Q14 | Both: 60 s reconnect grace (as D25/D48). A player who leaves is removed; votes for them are dropped. **The impostor leaves** → the round ends with no points (`IMPOSTOR_LEFT`) and the word is revealed. Fewer than 3 active → the game ends early with results. DI: a disconnected drawer's turn runs out on the timer. SS: a disconnected player's drawing counts as whatever they had when time ran out. | D61 |
| FA-Q15 | Both: late joiners and spectators follow the shared room rules — they watch, and see neither word nor roles until the reveal. Accessibility: never colour alone (initial + legend); turn changes and phase changes announced through `aria-live`; voting by keyboard. | D56 |
| FA-Q16 | Both: `ROUND_END` (8 s, auto-advance) shows the impostor, the word (and SS's decoy word, if any), every vote, the guess and the points. Final results reuse the shared results screen and rematch. | D59 |
| — | SS drawings are server-held **private drafts**, as in Telephone (D36): batches go only to their author until the gallery opens. ≤ 1 500 live points and 100 strokes per drawing keep a 10-drawing gallery snapshot under the 256 KiB limit (half of D&G's budget would not: 10 × 6 000 points is far over it); the maximal-snapshot test in P9-04 fixes the exact figure. Kept in memory for the round only (not saved). | D64 |

## 3. Rules to add to `game-rules.md`

Written in P9-01: `game-rules.md` "Game 3 — Dino Impostor" (`[DI-1]…[DI-14]`; the rules marked *shared* also apply to Game 4) and "Game 4 — Sketchy Saurus" (`[SS-1]…[SS-3]`). Those sections are authoritative; tests carry the rule ID in their name (`DI3_…`, `SS2_…`).

## 4. State machines

```
Dino Impostor:   ROLE_REVEAL (5 s) ──► DRAWING (turn t = 0 … laps·n − 1) ──► VOTE ─┐
Sketchy Saurus:  ROLE_REVEAL (5 s) ──► SKETCH (everyone at once) ──► GALLERY ──────┤
                                                                                   ├─ caught, impostor knew (or SS unawareGuess) ──► GUESS ──► ROUND_END
                                                                                   ├─ caught, didn't know (SS, unawareGuess off) ───────────► ROUND_END
                                                                                   └─ escaped ──────────────────────────► ROUND_END
ROUND_END ──► ROLE_REVEAL (next round)  |  FINISHED (last round, or < 3 active)
Impostor leaves in any phase ──► ROUND_END (IMPOSTOR_LEFT, no points)
```

`GALLERY` is the voting phase with every drawing included in its event and snapshot; voting, chat and early close are the same code as `VOTE`. Phase IDs `round:{r}:{phase}` and DI's turn deadlines `round:{r}:turn:{t}` use the standard deadline book, so a stale deadline is ignored by its ID.

## 5. Contract sketch (`api-contracts.md` §3.11 and §3.12, written in P9-01)

Written in P9-01: `api-contracts.md` §3.11 (shared `IMPOSTOR_*` commands/events + Dino Impostor) and §3.12 (Sketchy Saurus, `SKETCHY_*`), the §3.6 family-prefix sentence, §2.2 settings and "Phase 9 contract changes". Choices made while writing it:

- Each Dino Impostor turn is its own `DRAWING` phase with a fresh `phaseId`, so a late or out-of-turn batch is caught by the engine's phase guard (`STALE_ACTION`) and needs no turn-specific deadline code.
- Drawing commands are game-prefixed like Telephone's (`IMPOSTOR_DRAW_STROKE`, `SKETCHY_DRAW_*`), not D&G's `DRAW_STROKE`.
- The vote result (all votes, the accused, the impostor when caught) is public in `IMPOSTOR_VOTE_CLOSED` when voting ends, so everyone can watch a caught impostor guess.
- No new error codes: `NOT_DRAWER`, `ALREADY_SUBMITTED`, `FORBIDDEN`, `WORD_POOL_TOO_SMALL` cover the new cases.
- Settings outside their bounds are a 400 (as for every game), so `rounds` uses `0` = auto (players at start, clamped 3–10), like Telephone's `entriesPerChain`.

## 6. What changes outside the new package

The proposal's 3 groundwork items were **not** built; the code shows:

1. **No shared `VoteService`.** Rematch voting is a boolean map inside `session.ActiveSession`. → The vote count lives in the shared impostor code (`ImpostorVotes`, plain state under the room lock). No engine change.
2. **Strokes don't record who drew them.** `drawing.CanvasBuffer` stores no author. → DI keeps `strokeId → authorGuestId` next to its one buffer and adds the author when it builds events/snapshots; undo/clear are never routed. SS keeps one `CanvasBuffer` per player (as Telephone's drafts), so the owner is the author and undo/clear work as they do today. No change to `drawing`.
3. **"Who may draw" is already a prop** (`DrawCanvas.isDrawer`). DI passes `isMyTurn`; SS passes `true` during `SKETCH`. DI also needs per-author colours and a one-stroke lock — new optional props, D&G unchanged.
4. **`GuessMatcher` sits in `games.drawguess`.** Using it from another game would cross a module boundary (ArchUnit). → Move the normaliser into the `words` module as a public `GuessNormalizer`; D&G switches to it with no change in behaviour (its tests prove that).
5. **Migrations:** `V11__impostor_games.sql` adds both `game_definition` rows (4–10, default settings, `enabled = false`). Enabling is its own later migration per game (`V12` DI, `V13` SS — or one migration if they launch together).
6. **Frontend registry:** two entries each in `VISUALS` (two new original badges) and `MODULES` in `features/games/gameModules.ts`. Both games use the generic settings form (no custom lobby panel needed).

## 7. Backend design (`com.dinoparty.games.impostor`)

One package (one ArchUnit module) holding both games:

- Shared: `ImpostorRotation` (fair pick, DI seat shuffle; pure, takes `RandomSource`), `ImpostorRound` (role assignment, word/decoy, reveal data), `ImpostorVotes` (tally, caught/escaped), `ImpostorVoting` (`VOTE`/`GALLERY` chat + votes + early close, `GUESS`, `ROUND_END`, next round/finish, leave paths — the code both modules call after drawing), `ImpostorScoring` (pure), `ImpostorWords` (`prepare` helper: `rounds` words + decoys from the catalog).
- `DinoImpostorModule`, `DinoImpostorSettings`, `DinoImpostorState`, `DinoImpostorViews` — turns, stroke policy, author map.
- `SketchySaurusModule`, `SketchySaurusSettings`, `SketchySaurusState`, `SketchySaurusViews` — per-player drafts, submit, gallery.

Each `*Views` is the one place that decides who may see the word, decoy and roles. `CommandSpec`s with rate limits: draw commands (existing limits), `SKETCHY_SUBMIT` (once per round), `IMPOSTOR_CHAT` (5 / 10 s), `IMPOSTOR_VOTE` (10 / 10 s), `IMPOSTOR_GUESS` (1 per round). `CommandRouter`'s reserved-type list gets the new types.

## 8. Frontend design

- `features/impostor/common/`: `RoleCard` (word or impostor notice, category; reduced-motion safe), `VotePanel` (roster as a radio group, change/withdraw, voted markers), `DiscussionChat`, `ImpostorGuessInput`, `RoundReveal` (shows the decoy word when there was one), shared types for §3.11, `useImpostorCommands` (sends with `actionId`).
- `features/impostor/dino/`: `DinoImpostorStage` (routes by phase), `TurnStrip` (seat order, current turn, countdown, `aria-live` "Your turn"), `SharedCanvas` (wraps `DrawCanvas`; `isDrawer = isMyTurn`, fixed colour, no toolbar), `StrokeLegend`, store, fixtures.
- `features/impostor/sketchy/`: `SketchySaurusStage`, `SketchCanvas` (`DrawCanvas` + normal toolbar, submit, "N of M submitted"), `GalleryGrid` (every drawing with its artist; scrolls on a phone; keyboard focus per drawing so you can vote from it), store, fixtures.

Stores are Zustand, replaced wholesale by snapshots. Results and rematch reuse the shared screens.

## 9. Tasks

| ID | Task | Owner | Depends on | Done when |
|---|---|---|---|---|
| P9-01 | **Rules and contract.** D50–D64 in `decisions-and-risks.md` (the user's answers marked approved); D10 originality checklist for both names signed off; `game-rules.md` Game 3 + Game 4; `api-contracts.md` §3.11 (shared + DI), §3.12 (SS), the §3.6 family-prefix sentence, "Phase 9 contract changes"; `fake-artist-proposal.md` marked superseded. Separate `docs(contract)` commit. | CC | — | Docs merged; every `[DI-n]`/`[SS-n]` testable; user approved D50–D64 |
| P9-02 (**done**, see below) | **Backend shared code + Dino Impostor drawing.** `GuessMatcher` → `words.GuessNormalizer` (own commit, D&G tests unchanged and green); `V11` with both rows disabled; `ImpostorRotation` (property test: over `rounds = n` everyone is impostor ⌊r/n⌋ or ⌈r/n⌉ times; DI impostor never in seat 0); `ImpostorWords`; `DinoImpostorModule` up to the end of `DRAWING` (turns, stroke policy, author map, `view`); ArchUnit rule | CC | P9-01 | Unit + session tests with a manual clock for every turn/timeout/rejection case; secrecy check; `./mvnw -f backend verify` |
| P9-03 | **Backend voting, guess, scoring, wind-down** (shared `ImpostorVoting`/`ImpostorVotes`/`ImpostorScoring`), wired into DI; leave/disconnect paths; `RoundRecord` meta; rematch | CC | P9-02 | Unit tests per `[DI-9…14]`; WS ITs: 5-player DI game, leave in each phase (impostor and others), reconnect in each phase, unauthorised commands (wrong turn, wrong room, spectator, self-vote), secrecy, 2 rooms in parallel; backend `verify` |
| P9-04 | **Backend Sketchy Saurus.** Settings, decoy draw, `SKETCH` (private per-player drafts, undo/clear, submit, early close, timeout), `GALLERY` on the shared voting code, the `unawareGuess` off (no guess) and on (late role notice + guess) paths | CC | P9-03 | Unit tests per `[SS-1…3]`; secrecy harness covers SS (no stroke reaches another player before `GALLERY`; with `impostorKnows = false` nothing the impostor receives differs in shape from an artist's); maximal 10-drawing gallery snapshot < 256 KiB; WS ITs for 5-player games with `impostorKnows` on, off + `unawareGuess` off, and off + `unawareGuess` on, reconnect in `SKETCH` and `GALLERY`; backend `verify` |
| P9-05 | **Frontend: shared pieces + Dino Impostor screens** against fixtures: registry entries, both badges, `common/` components, `dino/` | AG | P9-01 (alongside P9-02) | **Complete** (all checks clean, 467 tests, fixture preview verified; see `docs/testing.md` and `docs/contract-requests.md`) |
| P9-06 | **Frontend: Sketchy Saurus screens** against fixtures: `sketchy/`, including the decoy case on the ordinary artist screens | AG | P9-05 | Component tests; checked in a browser with fixtures at phone and desktop width |
| P9-07 | **Live wiring** for both games against the real backend; reconnect recovery | AG | P9-03, P9-04, P9-06 | A live multi-browser game of each, played by hand from start to rematch; contract gaps sent back as `docs/contract-requests.md` entries |
| P9-08 | **E2E, accessibility, launch.** Playwright `dino-impostor.e2e.ts` (4 contexts: caught + guesses, refresh mid-turn, out-of-turn stroke refused) and `sketchy-saurus.e2e.ts` (`impostorKnows = false`: caught with no guess offered, and caught with `unawareGuess` on and a guess made; refresh mid-sketch); axe smoke on every phase; enabling migrations; registry E2E expects 4 games; `testing.md` record; `README` game list | AG (E2E/a11y), CC (migrations, CI) | P9-07 | CI green, including E2E; both games in `GET /games` and the lobby |

Rough size: about 1.5 phases. Backend (P9-02–P9-04) ≈ 40 %, frontend (P9-05–P9-08) ≈ 60 %. P9-05/P9-06 run alongside the backend work against fixtures. **Dino Impostor can launch on its own** after P9-03 + P9-05 + P9-07 (DI part) + P9-08 (DI part), with Sketchy Saurus following.


### P9-02 status (built)

Built in `com.dinoparty.games.impostor` (one ArchUnit module; nothing outside uses it): `ImpostorRotation` (`[DI-3]` pick, `[DI-5]` seats), `ImpostorWords`/`ImpostorSetup` (words frozen in `prepare`), `ImpostorRound` (roles; the only builder of `IMPOSTOR_ROLE`), `DinoImpostorSettings`/`ImpostorDifficulty`, `DinoImpostorState`, `DinoImpostorViews`, `DinoImpostorModule`. `GuessMatcher`'s normalizer moved to `words.GuessNormalizer` (D&G tests unchanged and green). `V11` adds both catalog rows, disabled. `CommandRouter` reserves the new command types. Both games are still disabled.

Where the build differs from the design above:

- **Temporary `VOTE`.** After the last turn the round enters `VOTE` with its deadline, but no command is declared for it; its deadline moves to the next round or finishes the game, without scores or round records. An impostor who leaves ends the round with a `// P9-03:` note where `IMPOSTOR_ROUND_ENDED` goes. P9-03 replaces both.
- **Words are drawn at `prepare`, before the roster freezes.** With `rounds = 0` it draws the auto maximum of 10 words and the game uses the first `R`, so an auto start needs 10 distinct words at the set difficulty.
- **No `ImpostorWord` type.** `words.WordCatalogTypes.CategorizedWord` (text, category key, label) is used directly; `WordPoolService.categorizedBuiltIn` returns the usable, de-duplicated built-in words of the given difficulties. `ImpostorWords` keeps the whole pool, so P9-04 can draw decoys from the unused words of a category.
- **Closing an open stroke** (turn deadline, drawer `LEFT`) appends an empty final batch to the canvas; no event is published and `drawing` is unchanged (api-contracts §3.11, "Turns").
- **Locale.** Rooms carry no locale; words and category labels come from the catalog's default locale, as for Draw & Guess.
- **Colour palette:** Okabe-Ito without its yellow plus three colours of Paul Tol's muted scheme (comment in `DinoImpostorModule.PALETTE`).

**Known risk for P9-04.** `GameRegistry` refuses a command or event type declared by two modules, and Sketchy Saurus must declare the shared `IMPOSTOR_*` types as well. P9-04 needs a rule for family-shared types (or one module owning the shared declarations) before its module can register; that is a registry change outside the engine SPI.

## 10. Tests that matter most

- **Secrecy** (highest risk): one harness that records every frame each client receives over a full game of each and checks that before `ROUND_END` the word never reaches the impostor or spectators, and the impostor's ID never reaches anyone. SS: no other player gets your strokes while sketching; with `impostorKnows = false` the impostor's role event and snapshot can't be told apart from an artist's except by the word itself.
- **DI turn integrity:** a stroke out of turn, a second stroke in one turn, the eraser, someone else's colour, a batch after the deadline (`[C-6]`) — all refused, the canvas unchanged.
- **Fair rotation** property test (`n = 4…10`, `rounds = 3…10`).
- **Vote edge cases:** tie at the top, all abstain, a vote changed at the deadline, the target leaves, the voter leaves.
- **Rejoining** in every phase of both games gets the same view as before the refresh.

## 11. Risks

| Risk | Mitigation |
|---|---|
| The impostor can tell their role from event timing, order or size. | Every player gets an `IMPOSTOR_ROLE` of the same shape at the same moment. The secrecy harness checks timing and order, not just content. |
| Players share the word outside the game (voice, same room). | It's a social game; this can't be enforced and isn't a goal. |
| DI with 10 players × 2 laps × 25 s turns is long (≈ 8 min drawing). | A turn ends as soon as the stroke is done, so real turns are much shorter. Tune the defaults during P9-07 play. |
| SS decoy too close to the real word (nobody gets caught) or too far (the impostor stands out). | Same category and difficulty, must differ after normalising. Watch play tests in P9-07; a hand-picked list of decoy pairs could come later. |
| Not enough categories/words in the catalog for 10 rounds plus decoys. | `prepare` rejects the start with a clear error (as D&G); check the catalog size in P9-01. |
| A 10-drawing gallery is too big for one snapshot or a phone screen. | Half-size per-canvas budget (D64), the maximal-snapshot test, and a scrolling `GalleryGrid`. |
| Moving `GuessMatcher` breaks Draw & Guess. | Its own commit in P9-02 with D&G's tests unchanged. |

## 12. Not in v1

More than one impostor, custom word packs, saving each round's drawings (round meta is stored, strokes are not), voice, a shared engine `VoteService`, and `impostorKnows` for Dino Impostor.
