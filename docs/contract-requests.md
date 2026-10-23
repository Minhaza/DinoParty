# DinoParty — Contract Requests & Ambiguities

Documented during implementation of **P9-05** (`dino-impostor` shared pieces & screens) without modifying `docs/api-contracts.md` or `docs/game-rules.md`.

---

## G1 — `colors` and `seatOrder` distribution on initial game start (High Priority)

- **Issue:** According to `api-contracts.md` §3.11, the author colour mapping (`colors: Record<string, string>`) appears only in `DinoImpostorSnapshot`. A player who is present in the lobby when the game starts receives `GAME_STARTED` and subsequent `PHASE_STARTED` events, but never receives a snapshot unless a reconnect or resync occurs. As a consequence, on initial game start, the client has no `colors` map to know its assigned stroke colour or populate the player legend.
- **Request:** Include `colors: Record<string, string>` and `seatOrder: string[]` in `PHASE_STARTED` with `phase = 'ROLE_REVEAL'` (or on all Dino Impostor `PHASE_STARTED` payloads).
- **Interim client behaviour (P9-05):** If `DRAWING` phase begins and `colors` is empty, the store sets `needsColors = true` and `needsResync = true`, triggering a snapshot fetch over REST/STOMP to recover the colour assignment.
- **Resolution (P9 contract fix):** accepted. `ROLE_REVEAL`'s `PHASE_STARTED` carries `seatOrder` and `colors` (`api-contracts.md` §3.11). The client should read them there and keep `needsColors`/`needsResync` only as a safety net.

---

## G2 — Player nicknames in `ImpostorStroke` and `seatOrder` (Medium Priority)

- **Issue:** Neither `ImpostorStroke` nor `seatOrder` include author nicknames. If a player disconnects or leaves (`PLAYER_LEFT`), their details are removed from the active session roster. When historical strokes or seat order are rendered in the legend or turn strip, the client cannot display the nickname of a player who left.
- **Request:** Provide an `authorNickname` field on `ImpostorStroke` or keep a read-only historical roster map in the snapshot (similar to Telephone's `RevealEntryDto.authorNickname`).
- **Interim client behaviour (P9-05):** The client displays `"Left the game"` with an initial badge of `"?"` when the `authorGuestId` cannot be found in the active roster.
- **Resolution (P9 contract fix):** accepted in a simpler form: both snapshots carry `nicknames:{guestId:string}` for the whole frozen roster; use the live room roster first and this map for players who left. Strokes and seats stay free of nicknames.

---

## G3 — Zero-indexing vs one-indexing for `lap` counter (Clarification)

- **Issue:** The contract specifies that `turn` is 0-indexed (`turn: 0 .. totalTurns - 1`), but does not explicitly specify whether `lap` is 0-indexed or 1-indexed.
- **Clarification Request:** Confirm whether `lap` in `DinoImpostorPhaseStartedPayload` is 0-based (`0 .. laps - 1`) or 1-based (`1 .. laps`).
- **Interim client behaviour (P9-05):** The client treats `lap` as 0-indexed (consistent with `turn`) and displays it as 1-based (`lap + 1 / laps`) in the user interface.
- **Resolution:** `lap` is **1-based** (`1…laps`), fixed in P9-02 (`api-contracts.md` §3.11). **The P9-05 client is wrong:** it treats `lap` as 0-based and shows `lap + 1`, so the first lap would read "lap 2". Show `lap` as it is; fix the fixtures and tests.

---

## G4 — Reconnect mid-drawing turn stroke status (Low Priority)

- **Issue:** When reconnecting during an active `DRAWING` phase where the reconnected player is the current drawer, `DinoImpostorSnapshot` contains existing strokes but does not explicitly state whether the current drawer has already submitted their single stroke for the ongoing turn.
- **Request:** Consider adding `turnStrokeId: string | null` or `turnStrokeSubmitted: boolean` to `DinoImpostorSnapshot`.
- **Interim client behaviour (P9-05):** The client allows the drawer to attempt drawing if no locally known stroke exists; if the server rejects it with `ALREADY_SUBMITTED`, the client locks the canvas for the remainder of the turn.
- **Resolution (P9 contract fix):** accepted as `DinoImpostorSnapshot.turnStroke:{strokeId, nextBatchNo, closed}|null`. Open stroke → the drawer may continue it; closed → locked; `null` → free to draw. Keep the `ALREADY_SUBMITTED` fallback.

---

## G5 — Stroke line width consistency (Informational / No Change Needed)

- **Issue:** The contract allows `size` on `DrawStrokePayload` to range from 1 to 32. In Dino Impostor, players share a single canvas and should draw with consistent line weight.
- **Client behaviour (P9-05):** The client fixes stroke tool to `PENCIL` and `size: 2` for all players, preventing stroke thickness variations.
- **Resolution:** no change. Note the premise was wrong: `DRAW_STROKE`-style batches allow `size` 1–4 (`api-contracts.md` §3.9, `StrokeBatch`); a fixed `size: 2` is valid.

