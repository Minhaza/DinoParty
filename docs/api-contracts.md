# DinoParty — API Contracts (REST + STOMP)

This file is the **single source of truth** for client/server contracts. Backend DTOs and frontend types must match it. Changes require a contract-change commit (see `AGENTS.md`). Status: REST (Phase 2) and the lobby STOMP layer (Phase 3) are implemented as written; rows marked *Phase 4+* are still design. Fields may be refined, but only via edits to this file. **Phase 6 (Dino Telephone, `docs/telephone-design.md`): the step commands/events of §3.10 (`TELEPHONE_*`, `TELEPHONE_TASK`/`_PROGRESS`/`_STEP_ENDED`) and the step part of `TelephoneSnapshot` are implemented as written (P6-02, integrated with the frontend in P6-04); `REVEAL_*`, `ALBUM_REVEALED`, the snapshot `reveal`, the §2 album rows and `AlbumDto`/`AlbumSummaryDto` are implemented as written too (P6-03, with the reveal UI of P6-05); `Retry-After` on the albums' `RESULTS_NOT_READY` is not sent yet, as for results.** The Phase 4 additions (game lifecycle, generic game events, snapshot `game`, rematch) are **design pending approval** (`docs/decisions-and-risks.md` D13–D23); the engine behind them is specified in `docs/game-engine.md`. Phase 4 changes to existing Phase 2/3 contract text are listed in §4.

**Phase 4 implementation status (backend, verified by the integration tests listed in `docs/testing.md`):** implemented as written: `START_GAME` and `REMATCH_VOTE`, game commands with `phaseId`/`STALE_ACTION`, per-type rate and size limits, `ROOM_STATUS_CHANGED`, `GAME_STARTED`, `PHASE_STARTED`, `GAME_FINISHED`, `GAME_ABORTED`, `REMATCH_VOTES_UPDATED`, snapshot `game`, `RoomDto.sessionId` and `PlayerDto.score`, `POST /rooms/{code}/start|kick|close`, `GET /sessions/{id}/results`, restart recovery. **Not implemented yet (still contract only):** `Retry-After` on `RESULTS_NOT_READY`, `Idempotency-Key` on start. `GAME_FINISHED.resultsReady` is always `false`, and a client reconnecting during `STARTING` receives no `startsAt` in its snapshot. `maxPayloadBytes` of a `CommandSpec` is checked against the serialized payload object, and the rate is a fixed one-second window of `max(burst, ceil(rate))` commands. The frame cap stays 64 KiB (D20 is decided in Phase 6).

**P7-01 (platform):** `GET /games`, `GET /games/{gameKey}` (served from the backend game registry, §2.6), `RoomDto.lastSession`, the `PATCH /guests/me` lock and the idle-room sweep (`ROOM_CLOSED {reason:"IDLE"}`) are implemented as written below (verified by the tests in `docs/testing.md` "P7-01 verification record").

## 1. Conventions

- Base path `/api/v1`. JSON, UTF-8, `camelCase`. IDs are UUID strings; timestamps ISO-8601 UTC with millis. Durations in seconds (`…Seconds`) unless named `…Ms`.
- Auth: `Authorization: Bearer <guestToken>` on all endpoints except `POST /guests`, `GET /health`, `GET /games`, `GET /games/{gameKey}`.
- Room codes: 6 chars from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`; case-insensitive on input, uppercase in output.
- Errors: RFC 9457 `application/problem+json` with extension fields:

```json
{ "type": "https://dinoparty.app/errors/room-full", "title": "Room is full", "status": 409,
  "code": "ROOM_FULL", "detail": "This room already has 8 players.",
  "traceId": "…", "fieldErrors": [ { "field": "nickname", "message": "must be 2–20 characters" } ] }
```

- Optional extension `details` (object): machine-readable facts about a failure, only on codes that document it (currently `WORD_POOL_TOO_SMALL`: `{required:int, available:int}`, §2.4; `WORD_PACK_LIMIT_REACHED`: `{limit:int, kind:"PACKS"|"WORDS", remaining?:int}`, §2.5). Never contains secrets such as words.
- Error codes: `VALIDATION_FAILED, UNAUTHENTICATED, FORBIDDEN, NOT_HOST, ROOM_NOT_FOUND, ROOM_FULL, ROOM_IN_PROGRESS, ROOM_CLOSED, NOT_ENOUGH_PLAYERS, WORD_POOL_TOO_SMALL, ALREADY_IN_ROOM, NOT_IN_ROOM, GAME_NOT_FOUND, SESSION_NOT_FOUND, RESULTS_NOT_READY, RATE_LIMITED, PAYLOAD_TOO_LARGE, STALE_ACTION, DUPLICATE_ACTION, INVALID_PHASE, INVALID_ACTION, NOT_DRAWER, IS_DRAWER, ALREADY_SUBMITTED, ALBUM_NOT_FOUND, WORD_PACK_NOT_FOUND, WORD_PACK_LIMIT_REACHED, IMPORT_STALE, SERVER_ERROR, NOT_FOUND, METHOD_NOT_ALLOWED, UNSUPPORTED_MEDIA_TYPE`. The last three are generic framework-level codes for unmapped paths, wrong HTTP methods and wrong content types.
- Rate-limit responses: `429` + `Retry-After`.
- Idempotency: mutating room endpoints accept optional `Idempotency-Key` header (join/leave/start).

## 2. REST endpoints

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `GET /health` | no | — | `200 {status:"UP", version, db:"UP", time}` | `503` with the same body shape (`status:"DOWN"`, `db:"DOWN"`) if the DB is unreachable (not problem+json) |
| `POST /guests` | no | `{nickname, avatarKey, avatarColor?}` — nickname 2–20 chars (letters, digits, single spaces, `_ . ' -`; must start with a letter/digit), `avatarKey` from the fixed catalog, `avatarColor` `#RRGGBB` | `201 {guest: GuestDto, token, expiresAt}` | 400, 429 (per client IP) |
| `GET /guests/me` | yes | — | `200 GuestDto` (also renews sliding expiry) | 401 |
| `PATCH /guests/me` | yes | `{nickname?, avatarKey?, avatarColor?}` (absent/null = unchanged) | `200 GuestDto` | 400, 401, 409 (`INVALID_PHASE` while the guest's room is `STARTING` or `PLAYING` — enforced from Phase 4; allowed again in `RESULTS`/`WAITING`) |
| `GET /games` | no | — | `200 GameSummaryDto[]`: every registered game in key order, disabled ones included with `enabled:false` (§2.6) | |
| `GET /games/{gameKey}` | no | — | `200 GameDetailDto` (rules text, settings schema, defaults, bounds); also for a disabled game (`enabled:false`) | 404 `GAME_NOT_FOUND` (no game with this key) |
| `POST /rooms` | yes | `{gameKey, settings?, maxPlayers?}` (defaults from the game; see §2.2) | `201 RoomDto` (+ `inviteUrl`) | 400, 404 `GAME_NOT_FOUND`, 409 (`ALREADY_IN_ROOM`) |
| `GET /rooms/{roomCode}` | yes | — | `200 RoomDto` (members only for details; non-members get `RoomPreviewDto`; a closed room is still returned with `status: "CLOSED"`) | 404 `ROOM_NOT_FOUND` (also for a malformed code) |
| `POST /rooms/{roomCode}/join` | yes | `{}` | `200 RoomDto`. Idempotent: joining a room you are already in returns it unchanged | 404, 409 (`ROOM_FULL`, `ROOM_IN_PROGRESS`, `ROOM_CLOSED`, `ALREADY_IN_ROOM` when in a *different* room), 429 (per guest) |
| `POST /rooms/{roomCode}/leave` | yes | — | `204`. Idempotent: a non-member (or already-left) caller is a no-op. If the host leaves, the longest-standing remaining player becomes host; the last player leaving closes the room | 404 |
| `PATCH /rooms/{roomCode}/settings` | host | `{gameKey?, settings?, maxPlayers?}` | `200 RoomDto` | 400, 403 `NOT_HOST` (also for non-members), 404, 409 `INVALID_PHASE` (room not in `WAITING`) |
| `POST /rooms/{roomCode}/ready` | member | `{ready: boolean}` | `200 RoomDto`. Same effect as the WS `SET_READY` command; only while `WAITING` | 403 `NOT_IN_ROOM`, 404, 409 `INVALID_PHASE` |
| `POST /rooms/{roomCode}/start` | host | `{}` | `202 RoomDto` (status `STARTING`; the 3 s countdown runs on the server, see §3.7). Idempotent while already `STARTING` (returns the room unchanged). Same effect as the WS `START_GAME` command | 403 `NOT_HOST` (also non-members), 404, 409 `INVALID_PHASE` (room not `WAITING`/`STARTING`), 409 `NOT_ENOUGH_PLAYERS` (connected members `< minPlayers`), 400 `VALIDATION_FAILED` (word settings that are only checkable at start: `settings.wordCategories`, `settings.wordPackIds`, §2.4), 409 `WORD_POOL_TOO_SMALL` (`details:{required, available}`, §2.4). A rejected start changes nothing (no countdown, no event) |
| `POST /rooms/{roomCode}/kick` | host | `{guestId}` | `204`. Same effect as a `PLAYER_LEFT reason:"KICKED"`; allowed in every room status except `CLOSED`; the kicked guest is removed from the active session like a player who left | 400 (host kicks self), 403 `NOT_HOST`, 404 (room, or target not a member) |
| `POST /rooms/{roomCode}/close` | host | — | `204`. Emits `ROOM_CLOSED {reason:"HOST_CLOSED"}`; an active session ends `ABORTED` | 403 `NOT_HOST`, 404. Idempotent on an already-closed room |
| `GET /rooms/{roomCode}/snapshot` | member | — | `200 SnapshotDto` (viewer-specific; `game` is `null` unless a session is `RUNNING`/`FINISHED`) | 403 `NOT_IN_ROOM`, 404 `ROOM_NOT_FOUND` |
| `GET /sessions/{sessionId}/results` | member of the session's room | — | `200 ResultsDto` (read from the database only) | 404 `SESSION_NOT_FOUND`, 403 `NOT_IN_ROOM`, 409 `RESULTS_NOT_READY` (session `RUNNING`, or `FINISHED` but its result rows are not persisted yet; `Retry-After: 2`). `ABORTED`/`INTERRUPTED` sessions answer `200` with empty `standings` |
| `GET /sessions/{sessionId}/albums` | member of the session's room | — | `200 {sessionId, complete, albums: AlbumSummaryDto[]}` (telephone, §2.1, §3.10). Read from the database only; `albums` is empty for `ABORTED`/`INTERRUPTED` sessions | 404 `SESSION_NOT_FOUND` (also for a session of another game), 403 `NOT_IN_ROOM`, 409 `RESULTS_NOT_READY` (session `RUNNING`, so the reveal cannot be bypassed; or `FINISHED` but submissions not persisted yet; `Retry-After: 2`) |
| `GET /sessions/{sessionId}/albums/{chainIndex}` | member of the session's room | — | `200 AlbumDto` (all entries, with authors; telephone, only once the session is `FINISHED`) | 404 `SESSION_NOT_FOUND`, 404 `ALBUM_NOT_FOUND` (chain index out of range, or no albums), 403 `NOT_IN_ROOM`, 409 `RESULTS_NOT_READY` (as above) |
| `GET /word-catalog/categories?locale=` | yes | — | `200 WordCategoriesDto` (§2.3) | 400 `VALIDATION_FAILED` (`locale`), 401 |
| `GET /word-catalog/difficulties` | yes | — | `200 WordDifficultiesDto` (§2.3) | 401 |
| `GET /word-catalog/counts?locale=&category=&difficulty=` | yes | — | `200 WordCountsDto` (§2.3) | 400 `VALIDATION_FAILED` (`locale`, `category`, `difficulty`), 401 |
| `POST /rooms/{roomCode}/word-packs` | host | `{name, description?}` | `201 WordPackDto` (§2.5) | 400, 403 `NOT_HOST`/`NOT_IN_ROOM`, 404, 409 `ROOM_CLOSED`/`WORD_PACK_LIMIT_REACHED`, 429 |
| `GET /rooms/{roomCode}/word-packs` | member | — | `200 {packs: WordPackDto[]}` (names and counts only) | 403 `NOT_IN_ROOM`, 404 |
| `GET /rooms/{roomCode}/word-packs/{packId}` | member | — | `200 WordPackDto` | 403, 404 `ROOM_NOT_FOUND`/`WORD_PACK_NOT_FOUND` |
| `PATCH /rooms/{roomCode}/word-packs/{packId}` | host | `{name?, description?}` | `200 WordPackDto` | 400, 403, 404, 409 `ROOM_CLOSED`, 429 |
| `DELETE /rooms/{roomCode}/word-packs/{packId}` | host | — | `204` | 403, 404, 409 `ROOM_CLOSED`, 429 |
| `GET /rooms/{roomCode}/word-packs/{packId}/words?offset=&limit=` | host | — | `200 WordPackWordsDto` (§2.5) | 400, 403 `NOT_HOST`, 404 |
| `POST /rooms/{roomCode}/word-packs/{packId}/words` | host | `{words:[{text, category?, difficulty?}]}` (1–100) | `200 AddWordsResultDto` | 400, 403, 404, 409 `WORD_PACK_LIMIT_REACHED`/`ROOM_CLOSED`, 429 |
| `DELETE /rooms/{roomCode}/word-packs/{packId}/words/{wordId}` | host | — | `204` | 403, 404, 409 `ROOM_CLOSED`, 429 |
| `POST /rooms/{roomCode}/word-packs/{packId}/imports/preview` | host | multipart: `file` (`.txt`/`.csv`) | `200 ImportPreviewDto`; persists nothing | 400, 403, 404, 413 `PAYLOAD_TOO_LARGE`, 415, 429 |
| `POST /rooms/{roomCode}/word-packs/{packId}/imports/confirm` | host | multipart: `file` (same bytes), `sha256`, `packVersion` | `200 ImportResultDto` | 400, 403, 404, 409 `IMPORT_STALE`/`WORD_PACK_LIMIT_REACHED`/`ROOM_CLOSED`, 413, 415, 429 |

### 2.1 DTOs (shape summaries)

```
GuestDto        { id, nickname, avatarKey, avatarColor|null, createdAt }
GameSummaryDto  { key, name, tagline, minPlayers, maxPlayers, iconKey, enabled }
GameDetailDto   { …GameSummaryDto, description, howToPlay[], settingsSchema: SettingField[], defaultSettings }
SettingField    { key, label, type:"int|bool|enum|list", default, min?, max?, autoValue?, options?, maxItems?,
                  itemFormat?:"category-key|uuid", exclusiveOption? }  /* absent members are omitted, see §2.6 */
RoomDto         { id, code, inviteUrl, status, gameKey, hostGuestId, minPlayers, maxPlayers,
                  settings, players:PlayerDto[], sessionId|null, createdAt,
                  lastSession: LastSessionDto|null /* Phase 4, additive */ }
LastSessionDto  { sessionId, status:"FINISHED|ABORTED|INTERRUPTED", endReason, finishedAt }
                  /* newest session of this room that is no longer running; null before the first game and
                     cleared when the next session starts running (GAME_STARTED; a STARTING countdown, or one
                     that is cancelled, keeps the previous value). In RESULTS it is the session that just
                     finished (same id as sessionId). endReason: the FinishReason for FINISHED, the GAME_ABORTED
                     reason for ABORTED, SERVER_RESTART for INTERRUPTED. INTERRUPTED/SERVER_RESTART is how
                     clients learn that a restart ended their game (game-engine.md §9) */
RoomPreviewDto  { code, gameKey, status, playerCount, maxPlayers, hostNickname }
PlayerDto       { guestId, nickname, avatarKey, avatarColor, ready, connection:"CONNECTED|DISCONNECTED|LEFT",
                  isHost, score|null }
SnapshotDto     { room:RoomDto, serverNow, seq:{room:int, private:int}, game: GameSnapshot|null /* null when no session is RUNNING/FINISHED */ }
ResultsDto      { sessionId, gameKey, status:"FINISHED|ABORTED|INTERRUPTED", endReason, startedAt, finishedAt,
                  standings:[{rank, guestId, nickname, score, detail}] /* empty for unscored games (telephone) */,
                  rounds:[{roundNo, startedAt, endedAt, endReason, scoreDeltas:{guestId:int}, meta}] /* meta: game-specific, e.g. drawerGuestId, word */ }
AlbumSummaryDto { chainIndex, ownerGuestId, entryCount, entriesPerChain, complete:boolean /* entryCount = entriesPerChain */,
                  hasPlaceholders:boolean }
AlbumDto        { sessionId, chainIndex, ownerGuestId, entriesPerChain, complete:boolean, entries:[AlbumEntryDto] /* step order */ }
AlbumEntryDto   { step /* 0-based */, kind:"TEXT|DRAWING", authorGuestId, authorNickname /* roster snapshot */,
                  text? /* kind TEXT */, strokes?:Stroke[] /* kind DRAWING; never null for a DRAWING entry in AlbumDto */,
                  fill:"NONE|TIMEOUT|LEFT_PLAYER|AUTO_PROMPT", timedOut:boolean /* fill = TIMEOUT */,
                  placeholder:boolean /* "(no description)" or an empty canvas inserted by the server */ }
                  /* Telephone (design: telephone-design.md §9, §12): AlbumDto is the REST form, §3.10 `RevealEntryDto` the
                     STOMP form of the same entry (strokes omitted/null only in a reveal snapshot, see §3.10) */
```

### 2.2 Room rules (Phase 2)

- A guest may be an active member of at most one room (`ALREADY_IN_ROOM` on create/join of another room). Leaving or the room closing frees them.
- `maxPlayers` defaults to 8 clamped to the game's bounds (`draw-guess` 2–10, `telephone` 4–8, `dino-impostor` 4–10, `sketchy-saurus` 4–10) and must stay within them; it cannot be set below the current player count (400).
- `settings` is a flat object validated against the game's schema (`draw-guess`: `cycles` 1–5, `drawSeconds` 30–180, `wordChoiceSeconds` 5–30, `hintReveal` bool, `difficulty` `easy|medium|hard|mixed`, `wordSource` `built-in|custom|combined`, `wordCategories` list of ≤ 20 distinct category keys or `["random-mix"]`, `wordPackIds` list of ≤ 10 distinct lowercase UUID strings — word keys in §2.4; `telephone`: `promptSeconds` 20–120, `drawSeconds` 30–180, `describeSeconds` 20–90, `entriesPerChain` `0` (auto) or integer 4–8 *(Phase 6, proposed D38)*, `allowSkipTimeout` bool — only `true` accepted *(Phase 6, proposed D39)*; `dino-impostor` *(Phase 9, proposed)*: `rounds` `0` (auto) or 3–10, `laps` 1–3, `turnSeconds` 10–60, `voteSeconds` 30–120, `guessSeconds` 15–60, `difficulty` `easy|medium|mixed`; `sketchy-saurus` *(Phase 9, proposed)*: `rounds`, `voteSeconds`, `guessSeconds`, `difficulty` as `dino-impostor`, `sketchSeconds` 30–120, `impostorKnows` bool, `unawareGuess` bool). Unknown keys or out-of-range values are 400 with `fieldErrors`; omitted keys take defaults. Supplying `settings` replaces the whole object. Changing `gameKey` resets settings to the supplied/default values.
- `players[]` lists active members ordered by join time. `score` is the engine's running total while a session is `RUNNING`/`FINISHED` (after `RESULTS`→`WAITING` it is `null` again) and always `null` for unscored games; `sessionId` is the current session (`RUNNING` or awaiting rematch) or `null`. Members who left or timed out during a session are no longer in `players[]`; their final score stays in the results.
- `ready` (Phase 3) is server-authoritative and persisted per membership: `false` on join/rejoin, changed only by `SET_READY` / `POST …/ready` while the room is `WAITING`.
- `connection` (Phase 3): `DISCONNECTED` while a member's STOMP connection is gone and the grace period (`app.realtime.disconnect-grace`, default 30 s) has not expired; otherwise `CONNECTED`. A member who has never opened a STOMP connection (REST-only client) is reported `CONNECTED` and is never timed out. `LEFT` is not used in `players[]` (left members are removed).
- When the host leaves or is removed on timeout, the new host is the longest-standing remaining player who is not currently `DISCONNECTED`; if everyone left is disconnected, the longest-standing one.
- `inviteUrl` = `{APP_PUBLIC_URL}/join/{CODE}`.
- Rate limits: `POST /guests` per client IP and join per guest answer `429 RATE_LIMITED` with `Retry-After`.

`GameSnapshot` is viewer-specific (Dino Impostor and Sketchy Saurus snapshots: §3.11, §3.12). Every game's snapshot starts with the engine-owned common header (Phase 4) followed by the game's own fields, flat in the same object:

```
GameSnapshotHeader { sessionId, gameKey, status:"RUNNING|FINISHED", order:[guestId],
                     phase:string, phaseId, phaseStartedAt, deadlineAt|null,
                     scores:{guestId:int}|null /* null for unscored games */,
                     players:[{guestId, status:"ACTIVE|DISCONNECTED|LEFT"}] /* session roster incl. members who left */,
                     results:{standings:[…]}|null /* only when status = FINISHED */,
                     rematch:{votes:{guestId:boolean}}|null /* only when status = FINISHED */ }
```

`phase` is the game's phase name; the engine adds no phases of its own inside a session (`STARTING` and `RESULTS` are room statuses). `deadlineAt` is the absolute server time at which the phase ends (the advertised deadline; a game may close the phase slightly later, never earlier). The game-specific parts, for reference:

```
DrawGuessSnapshot { /* phase ∈ WORD_CHOICE|DRAWING|ROUND_END, phaseId, deadlineAt and scores are in the common header */
                    round, totalRounds, drawerGuestId,
                    hint:string|null /* null in WORD_CHOICE; "____ ___" pattern, see §3.9 */,
                    word:string|null /* DRAWING: drawer only; ROUND_END: everyone; else null */,
                    wordChoices:string[3]|null /* WORD_CHOICE, drawer only */,
                    strokes:Stroke[] /* complete live canvas, in drawing order */,
                    guessedGuestIds:[guestId] /* solvers of this round, in guess order */,
                    recentChat:[{guestId, text, scope:"PUBLIC|GUESSERS", ts}] /* ≤ 30, viewer-filtered, oldest first */,
                    canvasBudget:{points:int, maxPoints:12000, strokes:int, maxStrokes:300} /* live usage of the drawer canvas */,
                    roundEnd:{reason:"TIME|ALL_GUESSED|DRAWER_LEFT", scoreDeltas:{guestId:int}}|null /* ROUND_END only */ }
TelephoneSnapshot { /* phase ∈ PROMPT|DRAW|DESCRIBE|REVEAL, phaseId, deadlineAt (null in REVEAL) and scores:null are in the common header;
                       there is no WAITING phase: a viewer who has submitted shows submitted:true (design TC5) */
                    step /* 0-based; in REVEAL = entriesPerChain */, totalSteps /* = entriesPerChain, effective E */,
                    entriesPerChain, kind:"PROMPT|DRAW|DESCRIBE"|null /* null in REVEAL */,
                    task:{kind, source:{kind:"TEXT|DRAWING", text?, strokes?, placeholder:boolean}|null}|null /* viewer only; source null in PROMPT; task null in REVEAL */,
                    draft:{text?:string, strokes?:Stroke[], canvasBudget?:{points, maxPoints:12000, strokes, maxStrokes:300}}|null /* viewer only: own work in progress; strokes in DRAW, text otherwise */,
                    submitted:boolean /* viewer's submission is in (final) */,
                    submittedGuestIds:[guestId], waitingOn:int /* eligible holders not yet submitted */,
                    reveal:{cursor:{chainIndex, entryIndex}, highWater:{chainIndex, entryIndex}, autoAdvanceAt, chains:int,
                            albums:[{chainIndex, ownerGuestId, entryCount, revealedCount, entries:[RevealEntryDto]}]}|null /* REVEAL only */ }
RevealEntryDto    { entryIndex, kind, authorGuestId, authorNickname /* roster snapshot, so a player who left is still attributed */,
                    text?, strokes?:Stroke[]|null, omitted:boolean /* strokes withheld by the snapshot size budget (§3.10) */,
                    fill, timedOut, placeholder }
Stroke            { strokeId:uuid, tool:"PENCIL|ERASER", color:"#RRGGBB", size:int(1-4 index),
                    points:[[x,y],…] /* integers 0..10000 = fixed-point fraction of canvas width/height (16:10); shared with Telephone (D28) */,
                    closed?:boolean /* Draw & Guess snapshots only; false while the drawer's stroke is still open */ }
```

### 2.3 Word catalog (WP-01, read-only metadata)

Built-in Draw & Guess words, stored in PostgreSQL (Flyway `V5`/`V6`, `docs/draw-guess-design.md` §18, decision D32). From WP-02 games draw their built-in words from this catalog (§2.4); `counts` is therefore the size of a built-in selection's pool. These endpoints return **metadata only**: they never list or reveal a word, so they cannot leak a word chosen or offered in a running game. Any authenticated guest may call them; no room membership is needed.

```
WordCategoriesDto   { locale, categories:[{key, label}] /* active, display order */,
                      presets:[{key:"random-mix", label:"Random Mix", includes:[categoryKey]}] }
WordDifficultiesDto { levels:[{key:"EASY|MEDIUM|HARD", label}],
                      presets:[{key:"MIXED", label:"Mixed", includes:["EASY","MEDIUM","HARD"]}] }
WordCountsDto       { locale, category /* key or "random-mix" */, difficulty:"EASY|MEDIUM|HARD|MIXED",
                      total, byDifficulty:{EASY?:int, MEDIUM?:int, HARD?:int},
                      byCategory:[{key, total, byDifficulty:{…}}] /* selected categories, display order */ }
```

- **Categories** have stable machine-readable keys: `animals`, `food-drinks`, `nature`, `objects`, `places`, `movies-games`, `sports`, `technology`, `actions`. Labels are localized per locale (falls back per category to English). `random-mix` is a selection preset (all active categories), never a stored category.
- **Difficulties**: every word stores exactly one of `EASY`, `MEDIUM`, `HARD`. `MIXED` is a filter (all three), never stored. Difficulty and preset labels are English for now; clients may localize them by key.
- **`counts` parameters** (all optional): `locale` (default `en`), `category` = a category key or `random-mix` (default), `difficulty` = `EASY|MEDIUM|HARD|MIXED` (case-insensitive, default `MIXED`). `byDifficulty` holds exactly the difficulties of the filter (zeros included); `total` is their sum. Only active words in active categories count. An unknown or inactive category key, an unknown difficulty, or a malformed locale is `400 VALIDATION_FAILED` with the parameter in `fieldErrors`.
- **Locale**: input is a language tag `ll` or `ll-RR` (2–3 letter language, optional 2-letter region; case-insensitive; `_` accepted), normalized to `en`, `pt-BR`, … The catalog locale used is the first of *tag → language → `en`* that has active words, and is returned in `locale` (e.g. `en-US` → `en`). The built-in catalog is English only (`en`).

### 2.4 Word selection (WP-02, Draw & Guess settings)

How the room settings choose the words of a Draw & Guess game (rules: `game-rules.md` `[DG-15]`; decision D33). All four keys are ordinary room settings (§2.2): host-only, `WAITING` only, validated for shape when saved, with these defaults from the catalog row.

| Key | Default | Values | Applies to |
|---|---|---|---|
| `wordSource` | `built-in` | `built-in` (catalog), `custom` (selected packs only), `combined` (both) | — |
| `wordCategories` | `["random-mix"]` | category keys of §2.3, or `["random-mix"]`; `[]` also means Random Mix; `random-mix` cannot be combined with keys | built-in words only |
| `difficulty` | `mixed` | `easy`, `medium`, `hard` = that stored difficulty; `mixed` = all three | built-in words only |
| `wordPackIds` | `[]` | custom word pack ids (WP-03), in the host's order | custom words only; ignored (kept) when `wordSource = built-in` |

- **Custom entries.** Category and difficulty filters never apply to custom pack words: a pack's entries are all eligible, whether or not they carry optional category/difficulty metadata. Only entries that are not usable words are left out: after trimming and Unicode NFC, a usable word is 3–20 characters of letters (any script) with single inner spaces or hyphens (`[DG-15]` shape). They are simply not counted in `available`.
- **Pool.** Built-in words (filtered; locale `en`) come first, then the packs in `wordPackIds` order. Duplicates are removed by the `[DG-4]` guess equivalence: two words are the same when they normalize to the same text without spaces (`ice-cream` = `ice cream` = `Ice Cream`, `café` = `cafe`); the first occurrence is kept (so a built-in spelling wins over a pack's).
- **Checked at start** (REST start and `START_GAME`), in this order, rejecting the start without initializing anything: an unknown or inactive category key (only checked when built-in words are used) → `400 VALIDATION_FAILED` `settings.wordCategories`; `custom`/`combined` without packs, or with a pack that does not exist or the host may not use → `400 VALIDATION_FAILED` `settings.wordPackIds`; fewer distinct usable words than one draw of choices (3, `[DG-2]`) → `409 WORD_POOL_TOO_SMALL` with `details:{required:3, available:n}`. A pool smaller than the number of rounds is allowed: words then repeat once the pool is exhausted (`[DG-15]`).
- **Frozen at start.** The settings and the pool are fixed when the host starts (before the countdown). Later settings changes, catalog changes or pack edits never reach a running game; a rematch is a new start and uses the room's settings and pack contents at that moment, with an empty used-word set.
- **Secrecy.** Settings carry pack ids, never words; `GAME_STARTED.settings` echoes them as stored. Errors (`message`, `details`) never contain words.

### 2.5 Custom word packs (WP-03, room-scoped; decision D34)

A word pack belongs to **one room** and lives and dies with it (database `ON DELETE CASCADE`; a closed room's packs are unreachable because nobody is a member any more). Only the **current host** creates, renames, edits, imports into or deletes packs. Any active member may list packs and read their metadata (name, description, word count); **word texts are only returned to the host**, by `GET …/words` and the import preview. No other endpoint, event, snapshot, room DTO or error ever contains a pack word. There are no accounts and no cross-room sharing: a pack id used under another room's code is `404 WORD_PACK_NOT_FOUND`. Pack ids are what `settings.wordPackIds` (§2.4) selects; at game start only packs of the host's own room resolve.

```
WordPackDto        { id, roomId, name, description /* "" when unset */, wordCount, createdAt, updatedAt }
WordPackWordsDto   { words:[{id, text, category, difficulty}], offset, limit, total }   // host only
AddWordsResultDto  { added, skipped /* duplicates */, pack: WordPackDto }
ImportPreviewDto   { format:"TXT"|"CSV", sha256 /* hex of the uploaded bytes */, packVersion,
                     totalRows /* non-blank rows */, validCount, duplicateCount, invalidCount,
                     remainingCapacity, exceedsPackLimit,
                     preview:[{line, text, category, difficulty}] /* ≤ 50 accepted rows */,
                     duplicates:[{line, of:"FILE"|"PACK"}] /* ≤ 100 */,
                     errors:[{line, code, message}] /* invalid rows, ≤ 100 */, truncated /* a list was cut */ }
ImportResultDto    { imported, skipped /* duplicates + invalid */, duplicates, invalid, pack: WordPackDto }
```

- **Limits (server-side, starting values).** File 1 MiB (`413 PAYLOAD_TOO_LARGE`); 10 000 non-blank rows per file (`413`); 1 000 words per pack and 20 packs per room (`409 WORD_PACK_LIMIT_REACHED`, `details:{limit, kind}`); word 1–80 Unicode code points; pack name 1–80 characters; description 0–280; category 1–40 characters; 1–100 words per add request. Writes are rate limited per guest (`429`; imports stricter).
- **Word normalization.** Text is trimmed, Unicode NFC, runs of whitespace collapsed to one space. Control characters (a tab counts as whitespace), invisible format characters other than the zero-width joiner/non-joiner (e.g. bidi overrides, BOM), unassigned, surrogate and private-use characters, and `<` `>` are invalid. Accents and case are **preserved** as entered. Duplicates are detected case-insensitively on the NFKC + lowercase form with accents kept (`Café` = `CAFÉ`, but `Café` ≠ `cafe`); the first occurrence wins, within the file and against words already in the pack. The game pool applies the stricter `[DG-4]` equivalence and `[DG-15]` shape on top (§2.4): longer or non-letter entries are stored but never played.
- **Metadata defaults.** Missing or blank `category` → `Custom`; missing or blank `difficulty` → `MEDIUM`. `difficulty` is `EASY|MEDIUM|HARD`, case-insensitive on import; anything else (including `MIXED`) is an invalid row.
- **TXT.** UTF-8 (BOM allowed, invalid UTF-8 rejected); one word per line; blank lines ignored; `line` is the physical line number.
- **CSV.** UTF-8 (BOM allowed), RFC 4180 quoting. The first row is the header with columns `word` (required), `category`, `difficulty` (case-insensitive, any order, no repeats, no other columns). A row with fewer cells than the header leaves the missing optional cells empty; one with more is an invalid row `MALFORMED_ROW`. `line` is the physical line the record ends on. An unterminated quote, bad header, empty file or non-UTF-8 content rejects the whole file: `400 VALIDATION_FAILED` with `fieldErrors:[{field:"file", message}]`. The format comes from the file extension (`.txt`/`.csv`, case-insensitive), otherwise `415`.
- **Row error codes** (`errors[].code`): `BLANK_WORD`, `TOO_LONG`, `INVALID_CHARACTER`, `INVALID_DIFFICULTY`, `INVALID_CATEGORY`, `MALFORMED_ROW`. Messages never echo file content.
- **Import workflow.** `preview` parses and validates, returns counts and bounded lists, and persists nothing. `confirm` takes the **same file again** plus the `sha256` and `packVersion` from the preview; the server re-parses the upload (client-supplied rows are never trusted) and applies it in one transaction under the room's lock. `409 IMPORT_STALE` when the bytes do not hash to `sha256` or the pack changed since the preview (`packVersion` is bumped by every add, remove and import); nothing is written. If the accepted rows exceed the remaining capacity the whole import is rejected (`WORD_PACK_LIMIT_REACHED`, `details:{limit, kind:"WORDS", remaining}`); there are no partial imports. Invalid rows are skipped and counted, not fatal. Re-confirming the same preview is `IMPORT_STALE`, so an import cannot insert twice; a unique `(pack, normalized text)` index backs this up.
- **Logging.** Imported content is never logged; logs carry room code, pack id and counts only.


### 2.6 Game discovery (P7-01)

Public, read-only, generated from the backend game registry (each game module declares its own metadata and settings schema; architecture.md §3.8). The `game_definition` row only decides availability (`enabled`).

```
GameSummaryDto { key, name, tagline, minPlayers, maxPlayers, iconKey, enabled }
GameDetailDto  { key, name, tagline, minPlayers, maxPlayers, iconKey, enabled,
                 description, howToPlay:string[], settingsSchema:SettingField[], defaultSettings:{key:value} }
```

- `enabled:false` games are listed and have a detail, but `POST /rooms` and a game switch answer `404 GAME_NOT_FOUND` for them. A key without a game module answers `404 GAME_NOT_FOUND`.
- `iconKey` is a stable asset id for the frontend (currently equal to the game key).
- `settingsSchema` lists every key accepted in `settings` (§2.2) in display order; `defaultSettings` are the values used for omitted keys. Server-side validation of room settings uses the same schema; a client must still expect `400` with `fieldErrors` (e.g. category keys and word-pack ids are checked again at start, §2.4).

| `type` | Members | Accepted values |
|---|---|---|
| `int` | `min`, `max`, `autoValue?` | whole numbers `min…max`; with `autoValue`, also exactly that value ("automatic") |
| `bool` | `options?` | `true`/`false`; with `options`, only the listed values (e.g. `allowSkipTimeout`: `[true]`) |
| `enum` | `options` | one of the listed strings |
| `list` | `maxItems`, `itemFormat`, `exclusiveOption?` | a list of at most `maxItems` distinct strings of `itemFormat` (`category-key` = `[a-z][a-z0-9-]{0,39}`, `uuid` = lowercase canonical UUID); `exclusiveOption` may only appear on its own |

Example (`GET /games/telephone`, abridged):

```json
{ "key": "telephone", "name": "Dino Telephone", "minPlayers": 4, "maxPlayers": 8, "iconKey": "telephone", "enabled": true,
  "settingsSchema": [
    { "key": "promptSeconds", "label": "Prompt time", "type": "int", "default": 60, "min": 20, "max": 120 },
    { "key": "entriesPerChain", "label": "Entries per album", "type": "int", "default": 0, "min": 4, "max": 8, "autoValue": 0 },
    { "key": "allowSkipTimeout", "label": "Skip on timeout", "type": "bool", "default": true, "options": [true] } ],
  "defaultSettings": { "promptSeconds": 60, "drawSeconds": 90, "describeSeconds": 45, "entriesPerChain": 0, "allowSkipTimeout": true } }
```

## 3. STOMP

### 3.1 Connection

- Endpoint `wss://<api-host>/ws` (native WebSocket, no SockJS). The HTTP upgrade is unauthenticated (browsers cannot set headers on a WebSocket) but is **Origin-checked** against `CORS_ALLOWED_ORIGINS`: a disallowed `Origin` gets `403`, and more than `app.realtime.connect-limit-per-minute` handshakes per client address get `429` + `Retry-After`. Authentication happens in the STOMP `CONNECT` frame.
- `CONNECT` headers: `accept-version:1.2`, `heart-beat:10000,10000`, `Authorization: Bearer <token>`. The server answers `heart-beat:10000,10000`. Invalid, missing, expired or revoked token ⇒ STOMP `ERROR` frame (`message: Authentication failed`, `code: UNAUTHENTICATED`) and the server closes the connection right after (close code 1002 or 1008; clients must treat any close that follows an `ERROR` frame as final); repeated failures from one client address answer `code: RATE_LIMITED`. The token is never echoed, logged or forwarded after `CONNECT`. The first frame of a socket must be `CONNECT`/`STOMP`; anything else is answered with `ERROR` `UNAUTHENTICATED` and the socket is closed with 1008 without being processed.
- Authorization identity = the guest id (`GuestDto.id`). The STOMP user name is `<guestId>_<connectionId>` (returned as `user-name` in `CONNECTED`); it is unique per connection, opaque to clients, and is why private events reach exactly one socket. Clients never address user destinations themselves.
- One live connection per guest: a new authenticated `CONNECT` replaces the previous one. The old connection receives private `SESSION_REPLACED` (if it subscribed to its private queue; sent while the new `CONNECT` is still being processed, so the new connection cannot receive it) and is closed about 0.5 s later with WebSocket close code 1008, reason `SESSION_REPLACED`; its disconnect does **not** mark the guest disconnected. Clients must not auto-reconnect after `SESSION_REPLACED`.
- Server-side limits: STOMP frame ≤ `app.realtime.max-message-bytes` (default 65536; larger frames close the connection with 1009), ≤ 8 subscriptions per connection.
- A connection whose guest session expires or is revoked is closed by the server with close code 1008, reason `SESSION_EXPIRED` (checked every `app.realtime.session-recheck-interval`, default 30 s).
- Subscriptions (allowed only for **current members** of the room, checked against the database at `SUBSCRIBE` time; everything else is refused with an `ERROR` frame and the connection is closed):
  - `/topic/rooms/{code}` — public stream (`stream: "ROOM"`).
  - `/user/queue/rooms/{code}` — private stream (`stream: "PRIVATE"`).
  - `/user/queue/errors` — errors not tied to a room (always allowed once authenticated).
  - No other destination may be subscribed to (no wildcards, no `/topic/**`, no other user's queue). `{code}` must be a well-formed room code (uppercase, §1).
  - When a member leaves, times out or the room closes, the server stops delivering that room's streams to their connection (the `PLAYER_LEFT` event itself is still delivered).
- Client sends to `/app/rooms/{code}/command` only (any other `SEND` destination is refused). Membership is verified for every command inside the room's serialized mailbox.

### 3.2 Envelope (all server events)

```json
{ "type": "…", "roomId": "uuid", "roomCode": "ABCD12", "ts": "2026-01-01T12:00:00.000Z", "seq": 17,
  "stream": "ROOM|PRIVATE", "actionId": "echoed client id or null", "payload": { } }
```

Client commands use `{ "type": "…", "actionId": "uuid", "phaseId": "uuid|null", "payload": { } }` sent as a JSON text frame (`content-type:application/json`). `type` is 1–40 chars `[A-Z_]`, `actionId` is a UUID string (required for every command except `PING`), `payload` is an object (may be omitted for payload-less commands).

- `seq` is gapless and starts at `1` **per stream**: the `ROOM` stream counts per room; the `PRIVATE` stream counts per (room, recipient). A gap means the client must resync via `REQUEST_SNAPSHOT` / `GET …/snapshot`.
- `ts` is server time (ISO-8601 UTC millis).
- Counters live in memory. After a server restart they restart from 1; therefore a client **replaces** its remembered `lastSeq` with `snapshot.seq` after every snapshot, even when lower.
- `/user/queue/errors` envelopes use `type: "ERROR"`, `stream: "PRIVATE"`, `seq: 0` (unsequenced), `roomId: null`, and `roomCode` = the destination's code when it was well-formed, else `null`. Payload as `GAME_ERROR`.

### 3.3 Client → server commands

Implemented in Phase 3: `SET_READY`, `REQUEST_SNAPSHOT`, `PING`. Phase 4 adds `START_GAME` and `REMATCH_VOTE` (engine-level, game independent). Every other `type` in the table is reserved for the game phases and is answered with `GAME_ERROR` `INVALID_ACTION` (`retryable:false`) until its game module is registered, as is an unknown `type`. From Phase 4 the set of accepted game command types is the union of the `CommandSpec`s declared by registered game modules (`docs/game-engine.md` §5); a command type that belongs to a game other than the room's is `INVALID_ACTION`.

| `type` | Who | Payload | Notes |
|---|---|---|---|
| `SET_READY` | member (`WAITING`) | `{ready:boolean}` | Idempotent (setting the current value emits no event but is acknowledged). `INVALID_PHASE` if the room is not `WAITING` |
| `REQUEST_SNAPSHOT` | member | `{}` | Reply: private `ROOM_SNAPSHOT` (consumes one private `seq`; `snapshot.seq.private` equals that envelope's `seq`) |
| `PING` | any member | `{}` | Reply: private `PONG {serverNow}`; no `actionId` required |
| `START_GAME` | host (`WAITING`) | `{}` | *Phase 4*. Same effect as REST start; no `phaseId`. `NOT_HOST`, `INVALID_PHASE` (not `WAITING`/`STARTING`), `NOT_ENOUGH_PLAYERS`, `VALIDATION_FAILED`/`WORD_POOL_TOO_SMALL` (word settings, §2.4; with `details`). Repeating it while `STARTING` is acknowledged without effect |
| `CHOOSE_WORD` | drawer (WORD_CHOICE) | `{wordIndex:0-2}` | *Phase 5*, specified in §3.9. Index only; server holds words |
| `DRAW_STROKE` | drawer (DRAWING) | `{strokeId, batchNo, tool, color, size, points:[[x,y]…], final}` | *Phase 5*, §3.9. 0–200 points, integers 0–10000 |
| `DRAW_UNDO` | drawer (DRAWING) | `{}` | *Phase 5*, §3.9. Removes last stroke |
| `DRAW_CLEAR` | drawer (DRAWING) | `{}` | *Phase 5*, §3.9 |
| `SUBMIT_GUESS` | non-drawer (DRAWING) | `{text}` | *Phase 5*, §3.9. Also serves as chat in Draw & Guess (solvers: restricted chat) |
| `TELEPHONE_SUBMIT_TEXT` | roster member, not `LEFT` (`PROMPT`, `DESCRIBE`) | `{text}` | *Phase 6*, §3.10. Prompt or description; final |
| `TELEPHONE_TEXT_DRAFT` | same | `{text}` | *Phase 6*, §3.10. Private draft; kept at the deadline |
| `TELEPHONE_DRAW_STROKE` / `TELEPHONE_DRAW_UNDO` / `TELEPHONE_DRAW_CLEAR` | same (`DRAW`) | as `DRAW_STROKE` / `DRAW_UNDO` / `DRAW_CLEAR` | *Phase 6*, §3.10. Same protocol, applied to the sender's private draft; no broadcast |
| `TELEPHONE_SUBMIT_DRAWING` | same (`DRAW`) | `{}` | *Phase 6*, §3.10. Commits the draft; final (replaces the earlier one-shot `{strokes}`, design TC1) |
| `REVEAL_NEXT` / `REVEAL_PREV` | host (`REVEAL`) | `{chainIndex, entryIndex}` = the cursor the host saw | *Phase 6*, §3.10 |
| `REMATCH_VOTE` | member (`RESULTS`) | `{vote:true|false}` | *Phase 4*. No `phaseId`. A voter may change their vote until the room resets. The host's `true` resets the room to `WAITING`; so does the last connected member's `true` once every connected member voted `true` (game-engine.md §10). `INVALID_PHASE` outside `RESULTS` |

Game command rules (Phase 4, apply to every row from `CHOOSE_WORD` down except `REMATCH_VOTE`; the Telephone rows are specified in §3.10):

- `phaseId` is **required** and must equal the current phase's id; otherwise `GAME_ERROR` `STALE_ACTION` (`retryable:false`). A command that arrives after its phase's deadline is therefore `STALE_ACTION` too: the server applies every due deadline before it looks at a command, so there is no window in which a late action is accepted. A missing `phaseId` is `VALIDATION_FAILED`.
- A command for a game phase that is valid in no phase of the game (wrong role, wrong kind) is `INVALID_ACTION`/`NOT_DRAWER`/`IS_DRAWER`; one valid in some phase but not the current one **with the current `phaseId`** is `INVALID_PHASE`.
- Rate limits are per command type and declared by the game module (e.g. strokes allow bursts, guesses do not); they replace the generic `command-limit-per-10s` bucket for game commands and answer `RATE_LIMITED` (`retryable:true`) like it does. Payload byte caps are per type as well (`PAYLOAD_TOO_LARGE`), bounded above by the transport frame limit (§3.1).
- While a session exists the room's in-memory roster is the membership source for game commands (no database lookup per stroke); a guest who left the session gets `NOT_IN_ROOM`.

Command handling rules:

- **Authorization** always comes from the authenticated principal and the database membership, never from client data. A non-member (or a member of a different room) gets `NOT_IN_ROOM` on `/user/queue/errors`, as does a malformed room code (`ROOM_NOT_FOUND`, `roomCode: null`). Failures that need no membership knowledge — `RATE_LIMITED`, `PAYLOAD_TOO_LARGE` — are also sent there; every failure of a *member's* command is a private-stream `GAME_ERROR` carrying the `actionId`.
- **Rate limit**: `app.realtime.command-limit-per-10s` (default 60) commands per guest per 10 s; excess ⇒ `RATE_LIMITED` (`retryable:true`) delivered on `/user/queue/errors` (it is decided before any database lookup so a flood stays cheap), command not executed and not recorded for duplicate detection. Rate limits for high-frequency game commands (strokes, guesses) are added with those commands.
- **Duplicates**: `actionId`s are remembered per guest and room for the last 256 commands / 5 minutes. A repeated `actionId` is **not** executed again and is answered with `GAME_ERROR` `DUPLICATE_ACTION` (`retryable:false`); clients that did not see the ack should rely on events/snapshot, or retry with a new `actionId`.
- **Validation**: malformed JSON, bad `type`/`actionId`, or a bad payload ⇒ `GAME_ERROR` `VALIDATION_FAILED` (`retryable:false`; the message never echoes client data). Frames larger than the limit close the connection (1009).
- Accepted commands are acknowledged with private `ACTION_ACK` after their events were published.

### 3.4 Server → client events

Public stream (`/topic/rooms/{code}`):

| `type` | Payload | Phase |
|---|---|---|
| `PLAYER_JOINED` | `{player:PlayerDto}` | 3 |
| `PLAYER_LEFT` | `{guestId, reason:"LEFT|KICKED|TIMEOUT"}` (`TIMEOUT` = grace period expired) | 3 |
| `READY_UPDATED` | `{guestId, ready}` | 3 |
| `HOST_CHANGED` | `{hostGuestId}` | 3 |
| `ROOM_SETTINGS_UPDATED` | `{gameKey, settings, minPlayers, maxPlayers}` | 3 |
| `PLAYER_DISCONNECTED` | `{guestId, graceEndsAt}` | 3 |
| `PLAYER_RECONNECTED` | `{guestId}` | 3 |
| `ROOM_CLOSED` | `{reason:"EMPTY"|"HOST_CLOSED"|"IDLE"}`. `EMPTY`: the last member left, so nobody is subscribed any more. `HOST_CLOSED`: `POST …/close`. `IDLE` (P7-01): the maintenance sweep closed a `WAITING` room in which nobody had a live connection or any activity for `app.room-orphan-ttl` (default 1 h); its members are removed and may join another room | 3 |
| `ROOM_STATUS_CHANGED` | `{status, reason, startsAt?}` — `reason`: `HOST_STARTED` (→`STARTING`, with `startsAt`), `COUNTDOWN_DONE` (→`PLAYING`), `GAME_FINISHED` (→`RESULTS`), `START_CANCELLED` (→`WAITING`, fewer than `minPlayers` connected), `REMATCH`, `RESULTS_TIMEOUT`, `GAME_ABORTED` (→`WAITING`), `SERVER_RESTART` (→`WAITING`, see §3.8). Its position relative to `GAME_STARTED`/`GAME_FINISHED`/`GAME_ABORTED` is fixed in §3.7 | 4 |
| `GAME_STARTED` | `{sessionId, gameKey, settings, order:[guestId]}` — `order` is the engine's shuffled play order (roster, fixed for the session) | 4 |
| `PHASE_STARTED` | `{phase, phaseId, round, deadlineAt, … game-specific public data}` (e.g. `drawerGuestId`, `hint`). Emitted by the engine for **every** phase change of every game, always after the state change and never carrying secrets; `phaseId` is fresh for every phase | 4 (engine), 5 (content) |
| `DRAWING_UPDATED` | `{kind:"STROKE", strokeId, batchNo, tool, color, size, points, final}` \| `{kind:"UNDO", strokeId}` \| `{kind:"CLEAR"}` (§3.9). Delivered on the public stream, therefore **also to the drawer**, whose client must ignore it (it is its own echo). Changed from "not sent to the drawer": per-recipient delivery would consume one private `seq` per member per stroke | 5 |
| `HINT_UPDATED` | `{hint}` — `"_a__ ___"` pattern (§3.9) | 5 |
| `GUESS_ACCEPTED` | `{guestId, scoreDelta, totalScore, order}` (no word; `order` = 0-based solver index) | 5 |
| `GUESS_CHAT` | `{guestId, text}` — a wrong guess shown as public chat (replaces the earlier name `GUESS_REJECTED_PUBLIC`) | 5 |
| `ROUND_ENDED` | `{round, drawerGuestId, word, reason:"TIME|ALL_GUESSED|DRAWER_LEFT", scoreDeltas:{guestId:int}, scores:{guestId:int}}`; `word` is `null` only when the drawer left before a word was chosen. `scoreDeltas` lists every roster member in play order (0 for no points this round, including members who left); `scores` are the running totals of every roster member | 5 |
| `TELEPHONE_PROGRESS` | `{phaseId, submittedCount, total, submittedGuestIds:[guestId]}` — names only, never content; `total` = eligible holders at the time. Published after every accepted submission and when a holder becomes `LEFT` | 6 |
| `TELEPHONE_STEP_ENDED` | `{step, kind, reason:"ALL_SUBMITTED|TIME", timedOutGuestIds:[guestId], leftGuestIds:[guestId]}` — closing event of a step (before the next `PHASE_STARTED`) | 6 |
| `ALBUM_REVEALED` | `{chainIndex, entryIndex, newlyRevealed:boolean, entry:RevealEntryDto|null, autoAdvanced:boolean, autoAdvanceAt}` — `entry` present only when `newlyRevealed` (§3.10) | 6 |
| `GAME_FINISHED` | `{sessionId, endReason:"COMPLETED|INSUFFICIENT_PLAYERS", standings:[{rank, guestId, score, detail}], resultsReady}` — nicknames are not repeated, clients use the roster. `standings` is empty for unscored games. `resultsReady:false` means `GET …/results` may still answer `RESULTS_NOT_READY` | 4 |
| `GAME_ABORTED` | `{sessionId, reason:"NO_COMPLETED_ROUND|INTERNAL_ERROR|SERVER_RESTART|SERVER_SHUTDOWN|ROOM_CLOSED"}`. The session ended without results; the room goes to `WAITING` (or `CLOSED`). `SERVER_RESTART` is never emitted live (only `lastSession` shows it, §3.8) | 4 |
| `REMATCH_VOTES_UPDATED` | `{votes:{guestId:boolean}}` (full tally; only connected roster members appear) | 4 |

Ordering of the events produced by one operation: leaving ⇒ `PLAYER_LEFT`, then `HOST_CHANGED` (if the host left), then `ROOM_CLOSED` (if the room emptied). A joining member produces `PLAYER_JOINED` only when the join is new (idempotent re-joins emit nothing). Disconnect/reconnect produce `PLAYER_DISCONNECTED` / `PLAYER_RECONNECTED` once per transition.

Private stream (`/user/queue/rooms/{code}`):

| `type` | Payload | Phase |
|---|---|---|
| `ROOM_SNAPSHOT` | `SnapshotDto` | 3 |
| `ACTION_ACK` | `{actionId, accepted:true}` (envelope `actionId` echoes it) | 3 |
| `GAME_ERROR` | `{actionId?, code, message, retryable, details?}` — `details` as in §1, only when the code documents it (e.g. `WORD_POOL_TOO_SMALL` on `START_GAME`); rejected command of a member (`VALIDATION_FAILED`, `INVALID_ACTION`, `INVALID_PHASE`, `NOT_HOST`, `DUPLICATE_ACTION`, `STALE_ACTION`; `RATE_LIMITED`, `PAYLOAD_TOO_LARGE`, `NOT_IN_ROOM` and `SERVER_ERROR` arrive as `ERROR` on `/user/queue/errors` with the same payload; exception: `PAYLOAD_TOO_LARGE`, `NOT_DRAWER`, `IS_DRAWER` and `ALREADY_SUBMITTED` raised by a *game module* — e.g. the Draw & Guess and Telephone stroke budgets — are private `GAME_ERROR`s like any module rejection, because only the transport-level byte caps are decided before the mailbox) | 3 |
| `PONG` | `{serverNow}` | 3 |
| `SESSION_REPLACED` | `{}` then the connection is closed | 3 |
| `WORD_CHOICES` | `{phaseId, choices:[string×3], deadlineAt}` — drawer only | 5 |
| `DRAWER_WORD` | `{phaseId, word}` — drawer only | 5 |
| `GUESS_FEEDBACK` | `{result:"CLOSE|DUPLICATE"}` — only to the sender of the guess (`ALREADY_GUESSED` is dropped: a solver's text is restricted chat) | 5 |
| `GUESSER_CHAT` | `{guestId, text}` — restricted chat from a solver, delivered only to the other solvers and the drawer | 5 |
| `TELEPHONE_TASK` | `{phaseId, step, kind:"PROMPT|DRAW|DESCRIBE", source:{kind:"TEXT|DRAWING", text?, strokes?, placeholder:boolean}|null, deadlineAt}` — the previous entry of the chain the recipient holds; `source` is `null` in `PROMPT`; the author is never included (§3.10) | 6 |

`SESSION_REPLACED` is delivered to the *replaced* connection only; `ACTION_ACK`, `GAME_ERROR`, `ROOM_SNAPSHOT` and `PONG` go to the guest's current connection.

### 3.5 Ordering and delivery guarantees

- Events within a stream are ordered by `seq`; the server publishes in `seq` order (`preservePublishOrder`), and room events are produced inside the room's serialized mailbox after the state change has committed. Frames from one connection are processed in the order they were sent (`preserveReceiveOrder`).
- No exactly-once delivery: clients dedupe by `seq`; commands dedupe by `actionId` server-side.
- **Snapshot consistency**: a snapshot (REST or WS) is built inside the room mailbox, so it is exactly the state after the last event with `seq ≤ snapshot.seq.room` (and `≤ snapshot.seq.private` for the private stream). A reconnecting client resubscribes, sends `REQUEST_SNAPSHOT`, applies the snapshot, then processes stream events with `seq > snapshot.seq` for that stream.
- `SnapshotDto.serverNow` is the server clock in ISO-8601 UTC millis; clients derive a clock offset from it.
- Secret data (`DRAWER_WORD`, `WORD_CHOICES`, `TELEPHONE_TASK`, snapshot fields marked viewer-only) never appears on the public stream or in another viewer's snapshot (contract-tested).
- Error responses and logs never contain tokens, stack traces or echoed client input. (Spring logs raw STOMP headers, including `Authorization`, when `org.springframework.messaging` is set to `TRACE`; the default configuration pins it to `INFO` and it must never be enabled in production.)

### 3.6 Extension rule for new games

New games add command and event `type` values with a game prefix (e.g. `FAKEARTIST_*`) and a `GameSnapshot` variant; they must not change the envelope, common events, or existing game payloads. (Draw & Guess and Telephone keep their unprefixed names from §3.3/§3.4; they are the reference games.) A **family** of games built on shared code may share one prefix for the types they have in common (Dino Impostor and Sketchy Saurus: `IMPOSTOR_*`, §3.11) and use their own prefix for the rest (`SKETCHY_*`, §3.12); a shared type has one payload in every game of the family. **Registry rule (D65):** the same command `type` may be declared by more than one game only if the `CommandSpec`s are equal (payload cap, rate, burst, legal phases), and the same event `type` only on the same audience (public or private) in each; any difference is a startup failure as before. The legal phases of a shared command are the union over the family (for example `VOTE` and `GALLERY`), and each module rejects the ones its own game never enters.

### 3.7 Game session lifecycle events (Phase 4)

Room status and session events, in the order they are published (all inside one room-mailbox command, so each group is gapless in `seq`):

| Moment | Events (public stream unless noted) |
|---|---|
| Host starts | `ROOM_STATUS_CHANGED {STARTING, HOST_STARTED, startsAt}` |
| Countdown cancelled (connected members `< minPlayers`) | `ROOM_STATUS_CHANGED {WAITING, START_CANCELLED}` |
| Countdown done | `ROOM_STATUS_CHANGED {PLAYING, COUNTDOWN_DONE}`, `GAME_STARTED`, then the first phase: `PHASE_STARTED` and the game's own events (private secrets after the public `PHASE_STARTED`) |
| Phase change | the game's closing events of the old phase (e.g. `ROUND_ENDED`), `PHASE_STARTED` of the new phase, the new phase's private events |
| Game ends | closing events of the last phase, `GAME_FINISHED`, `ROOM_STATUS_CHANGED {RESULTS, GAME_FINISHED}` |
| Game aborted | `GAME_ABORTED`, `ROOM_STATUS_CHANGED {WAITING, GAME_ABORTED}` (or `ROOM_CLOSED`) |
| Rematch | `ROOM_STATUS_CHANGED {WAITING, REMATCH}` only. The reset clears every `ready` flag and score server-side without per-member `READY_UPDATED` events; clients clear them on `reason: REMATCH|RESULTS_TIMEOUT|GAME_ABORTED|SERVER_RESTART` |

Rules: every game event is produced by the room mailbox command that changed the state, after the change is committed in memory; a client never sees an event for state a snapshot taken at that `seq` would not contain. `PHASE_STARTED.phaseId` is the value commands must carry from that moment on. A `ROOM_STATUS_CHANGED` always carries the full new `status`; clients do not infer status from other events.

### 3.8 Server restart (Phase 4, pending approval D13)

Active games are in memory and do not survive a restart (see `docs/game-engine.md` §9). On the next start the server marks every session left `RUNNING` as `INTERRUPTED`/`SERVER_RESTART` and moves rooms stuck in `STARTING`/`PLAYING`/`RESULTS` back to `WAITING` (members and settings kept, `ready` cleared). Observable contract:

- Clients reconnect as usual (sequence counters restart at 1; they replace `lastSeq` with `snapshot.seq`). The first snapshot after a restart has `room.status:"WAITING"`, `game:null` and `room.lastSession.status:"INTERRUPTED"`, `endReason:"SERVER_RESTART"`. That, not a live event, is how a client learns the game was lost; it shows a notice until the next `GAME_STARTED`.
- Commands for the lost session (`phaseId` of a phase that no longer exists) are answered `STALE_ACTION`.
- Before a *graceful* shutdown the server publishes `GAME_ABORTED {SERVER_SHUTDOWN}` and `ROOM_STATUS_CHANGED {WAITING, GAME_ABORTED}` to every active session, then closes sockets; the room is `WAITING` in the database when it comes back.

### 3.9 Draw & Guess (Phase 5 — decisions D24–D31 approved, D25 and D28 as amended; backend implemented in P5-02)

Rules: `docs/game-rules.md` (`[DG-*]`); design and state machine: `docs/draw-guess-design.md`. Phases (`PHASE_STARTED.phase`): `WORD_CHOICE`, `DRAWING`, `ROUND_END`. All commands carry `actionId` and the current `phaseId` (§3.3); the validation order is the engine pipeline (`game-engine.md` §4.1), so the checks below run after membership, dedupe, due deadlines and the phase guard. `Retry` = `retryable` in `GAME_ERROR`.

**Commands**

| `type` | Sender | Legal phase | Payload (all fields required unless noted) | Per-type limits | Effect | Errors (code — when) |
|---|---|---|---|---|---|---|
| `CHOOSE_WORD` | the round's drawer | `WORD_CHOICE` | `{wordIndex:int 0–2}` | 256 B; 2/s, burst 3 | Starts `DRAWING` with that word | `NOT_DRAWER` (sender is not the drawer), `VALIDATION_FAILED` (index missing/outside 0–2/not an int), `STALE_ACTION` (wrong `phaseId`, or deadline already passed), `INVALID_PHASE` (current phase is not `WORD_CHOICE`) |
| `DRAW_STROKE` | drawer | `DRAWING` | `{strokeId:uuid, batchNo:int ≥ 0, tool:"PENCIL"\|"ERASER", color:"#RRGGBB", size:int 1–4, points:[[x,y]] 0–200 items, x,y integers 0–10000, final:boolean}`; `points` may be empty only when `final` is true and `batchNo > 0`; the first batch (`batchNo` 0) needs ≥ 1 point | 8 KiB; 30/s, burst 40 | Appends the batch to the stroke, publishes `DRAWING_UPDATED{STROKE}` | `NOT_DRAWER`, `VALIDATION_FAILED` (schema, coordinates out of range, > 200 points, bad uuid/colour/tool/size), `INVALID_ACTION` (`batchNo` is not the expected next number for this `strokeId`; style differs from batch 0; batch for a stroke already `final`), `PAYLOAD_TOO_LARGE` (stroke would exceed 2000 points, round would exceed 300 live strokes or 12 000 live points; the whole batch is rejected, nothing is stored or published, the stroke so far stays, `message` says "canvas full"), `RATE_LIMITED` (transport), `STALE_ACTION`, `INVALID_PHASE` |
| `DRAW_UNDO` | drawer | `DRAWING` | `{}` | 128 B; 5/s, burst 5 | Removes the last stroke (open or closed); no-op ack on an empty canvas | `NOT_DRAWER`, `STALE_ACTION`, `INVALID_PHASE` |
| `DRAW_CLEAR` | drawer | `DRAWING` | `{}` | 128 B; 2/s, burst 3 | Removes all strokes; no-op ack on an empty canvas | same as `DRAW_UNDO` |
| `SUBMIT_GUESS` | any roster member that is not the drawer and not `LEFT` | `DRAWING` | `{text:string 1–80 chars after trim}` | 1 KiB; 2/s, burst 5 | Non-solver: correct → `GUESS_ACCEPTED`; close → private `GUESS_FEEDBACK{CLOSE}`; duplicate wrong → private `GUESS_FEEDBACK{DUPLICATE}`; otherwise public `GUESS_CHAT`. Solver: `GUESSER_CHAT` to the other solvers and the drawer (never evaluated) | `IS_DRAWER` (sender is the drawer), `VALIDATION_FAILED` (empty after trim/normalization, > 80 chars), `RATE_LIMITED`, `STALE_ACTION`, `INVALID_PHASE` (`WORD_CHOICE`/`ROUND_END`), `NOT_IN_ROOM` (left the session) |

All rejections leave game state unchanged and never end the round. `ACTION_ACK` follows accepted commands (after their events). A command from a guest who left the session is `NOT_IN_ROOM`. Role is decided from the authenticated principal; the payload carries no guest id.

**Drawing protocol.** One stroke = one or more `DRAW_STROKE` batches with the same `strokeId`, `batchNo` 0, 1, 2 … without gaps, the last with `final:true`. Batch 0 fixes `tool`, `color` and `size`; later batches repeat them and must match (rejected otherwise). The server stores the stroke at batch 0 and publishes each accepted batch unchanged as `DRAWING_UPDATED{kind:"STROKE", strokeId, batchNo, tool, color, size, points, final}` on the public stream, in acceptance order (gapless `seq`; a gap ⇒ resync, §3.2). A new `strokeId` from the drawer implicitly closes any still-open stroke. `DRAW_UNDO` publishes `{kind:"UNDO", strokeId}` naming the removed stroke; `DRAW_CLEAR` publishes `{kind:"CLEAR"}`. Eraser strokes are strokes (the client paints them with the background colour; `color` is ignored), they count against the budgets and undo removes them like any other. Clients should batch every ≈ 50 ms, simplify before sending and send at most ≈ 20 batches/s; a client whose batch is rejected with `INVALID_ACTION` or `VALIDATION_FAILED` ends the stroke locally and continues with a new `strokeId` from its last point, and may request a snapshot. A `PAYLOAD_TOO_LARGE` rejection means the canvas budget (12 000 live points, 2000 per stroke, 300 strokes) is exhausted: the batch was **not** stored or shown to anyone (nothing is ever truncated), so the drawer client must discard it locally, show "canvas full" and stop sending until an undo or clear frees budget (`canvasBudget` in the snapshot; `final` batches with no points always fit). The drawer's client ignores its own `DRAWING_UPDATED` echoes (it recognizes them by `strokeId`/the drawer id in the roster). Canvas state is *not* persisted and *not* sent per recipient: a reconnecting viewer gets all live strokes from the snapshot (`strokes`).

**Hint format.** `hint` is a string with one character per character of the word: `_` hidden letter, a revealed letter in lowercase, and `' '`/`'-'` as in the word. `"ice cream"` → `"___ _____"`; after a reveal `"i__ _____"`. It never reveals more than ⌈letters/2⌉ letters. It is `null` outside `DRAWING`/`ROUND_END` snapshots (in `ROUND_END` the full word is public).

**Phase events and secrets (what each audience sees)**

| Moment | Public stream | Private stream |
|---|---|---|
| Round starts | `PHASE_STARTED{phase:"WORD_CHOICE", phaseId, round, totalRounds, deadlineAt, drawerGuestId}` | drawer: `WORD_CHOICES{phaseId, choices, deadlineAt}` |
| Word chosen/auto-picked | `PHASE_STARTED{phase:"DRAWING", phaseId, round, totalRounds, deadlineAt, drawerGuestId, hint}` | drawer: `DRAWER_WORD{phaseId, word}` |
| During drawing | `DRAWING_UPDATED`, `HINT_UPDATED`, `GUESS_CHAT`, `GUESS_ACCEPTED` | sender: `GUESS_FEEDBACK`; solvers + drawer: `GUESSER_CHAT` |
| Round ends | `GUESS_ACCEPTED` (if the last guess ended it), `ROUND_ENDED{… word …}`, `PHASE_STARTED{phase:"ROUND_END", phaseId, round, totalRounds, deadlineAt, drawerGuestId}` | — |
| Last round done | after its `ROUND_END` phase: `GAME_FINISHED` + status event (§3.7) | — |

The word and the three choices appear on no public event, in no other viewer's snapshot, in no error message and in no log line before `ROUND_ENDED`; the two unchosen words are never revealed at all.

**Standings detail** (`GAME_FINISHED.standings[].detail`, `ResultsDto.standings[].detail`): `{correctGuesses, guesserPoints, drawerPoints, roundsDrawn}` (`roundsDrawn` counts recorded rounds the player drew to the end, i.e. not `DRAWER_LEFT` ones; guesser counters include guesses of a round discarded by an early finish, matching the ledger). `ResultsDto.rounds[].meta`: `{drawerGuestId, word|null, autoPicked, correctGuessers:[guestId in guess order]}`; `endReason` ∈ `TIME|ALL_GUESSED|DRAWER_LEFT`.

### 3.10 Dino Telephone (Phase 6 — steps (P6-02, P6-04), reveal and albums (P6-03, P6-05) implemented)

Rules: `docs/game-rules.md` (`[TP-1]…[TP-14]`); design, state machine, limits and open decisions: `docs/telephone-design.md`. Game key `telephone`; `GameDefinition`: 4–8 players, `minActivePlayers` 3, `scored:false` (`scores:null`, `standings:[]`, `PlayerDto.score:null`). Phases (`PHASE_STARTED.phase`): `PROMPT` (step 0), `DRAW` (odd steps), `DESCRIBE` (even steps > 0), `REVEAL`. A new `phaseId` per step; one `phaseId` for `REVEAL`. All commands carry `actionId` and the current `phaseId` (§3.3); the validation order is the engine pipeline (`game-engine.md` §4.1). Every roster member not `LEFT` has exactly one task per step, so there is no role error: a command legal in some phase but not the current one (current `phaseId`) is `INVALID_PHASE`, an old `phaseId` is `STALE_ACTION`.

**Commands**

| `type` | Sender | Legal phase | Payload | Per-type limits | Effect | Errors (code — when) |
|---|---|---|---|---|---|---|
| `TELEPHONE_SUBMIT_TEXT` | roster member, not `LEFT` | `PROMPT`, `DESCRIBE` | `{text}`: after stripping control/format characters (`Cc`/`Cf`) and trimming, 1–140 Unicode code points | 1 KiB; 2/s, burst 3 | Final submission of the sender's cell; clears the draft; `TELEPHONE_PROGRESS`; ends the step if it was the last eligible holder | `VALIDATION_FAILED` (missing, empty after trim, > 140), `ALREADY_SUBMITTED`, `STALE_ACTION`, `INVALID_PHASE` (current phase is `DRAW`/`REVEAL`), `NOT_IN_ROOM` (left the session) |
| `TELEPHONE_TEXT_DRAFT` | same | `PROMPT`, `DESCRIBE` | `{text}`: as above but may be empty (clears the draft) | 1 KiB; 2/s, burst 3 | Replaces the sender's private draft; nothing published | as above except no emptiness error; `ALREADY_SUBMITTED` after submitting |
| `TELEPHONE_DRAW_STROKE` | same | `DRAW` | identical to `DRAW_STROKE` (§3.9): `{strokeId, batchNo, tool, color, size, points, final}` | 8 KiB; 30/s, burst 40 | Appends the batch to the sender's private draft (`drawing.CanvasBuffer`, limits of `[DG-14]`: 200 points/batch, 2000/stroke, 300 strokes, 12 000 live points). **Not published**; `ACTION_ACK` only | `VALIDATION_FAILED`, `INVALID_ACTION` (batch order/style), `PAYLOAD_TOO_LARGE` (a budget; whole batch rejected, draft unchanged; private `GAME_ERROR`), `ALREADY_SUBMITTED`, `STALE_ACTION`, `INVALID_PHASE`, `RATE_LIMITED` (transport) |
| `TELEPHONE_DRAW_UNDO` | same | `DRAW` | `{}` | 128 B; 5/s, burst 5 | Removes the draft's last stroke; no-op ack when empty | `ALREADY_SUBMITTED`, `STALE_ACTION`, `INVALID_PHASE` |
| `TELEPHONE_DRAW_CLEAR` | same | `DRAW` | `{}` | 128 B; 2/s, burst 3 | Empties the draft; no-op ack when empty | same |
| `TELEPHONE_SUBMIT_DRAWING` | same | `DRAW` | `{}` | 128 B; 2/s, burst 3 | Commits the draft (possibly empty) as the sender's final entry; same effects as `TELEPHONE_SUBMIT_TEXT` | `ALREADY_SUBMITTED`, `STALE_ACTION`, `INVALID_PHASE` |
| `REVEAL_NEXT` | the current host | `REVEAL` | `{chainIndex:int, entryIndex:int}` — the cursor the host saw | 256 B; 5/s, burst 5 | Moves the cursor forward (revealing the entry the first time); on the last entry of the last album finishes the game | `NOT_HOST`, `VALIDATION_FAILED` (indices missing/not ints), `STALE_ACTION` (wrong `phaseId`, or the cursor differs from the server's), `INVALID_PHASE` |
| `REVEAL_PREV` | the current host | `REVEAL` | same | same | Moves the cursor back through revealed entries; acknowledged no-op at `(0,0)` | same |

Rejections leave state unchanged. `ACTION_ACK` follows accepted commands. Roles come from the authenticated principal; the payload carries no guest id. An action after the step's deadline is `STALE_ACTION` (`[C-6]`); the draft already became the entry (`[TP-5]`).

**Phase events and secrets (what each audience sees)**

| Moment | Public stream | Private stream |
|---|---|---|
| Step starts | `PHASE_STARTED{phase:"PROMPT|DRAW|DESCRIBE", phaseId, step, totalSteps, kind, deadlineAt}`; `TELEPHONE_PROGRESS{submittedCount:0 …}` | each eligible holder: `TELEPHONE_TASK{phaseId, step, kind, source, deadlineAt}` |
| During a step | `TELEPHONE_PROGRESS` | — (drafts are not echoed; commands are answered by `ACTION_ACK` / `GAME_ERROR`) |
| Step ends | closing `TELEPHONE_STEP_ENDED{…}` then the next `PHASE_STARTED` (or `REVEAL`) | next tasks |
| `REVEAL` starts | `PHASE_STARTED{phase:"REVEAL", phaseId, deadlineAt:null, totalSteps, chains, cursor:{0,0}, autoAdvanceAt}`, then `ALBUM_REVEALED{0, 0, newlyRevealed:true, entry}` | — |
| Host moves / auto-advance | `ALBUM_REVEALED` | — |
| Last entry passed | `GAME_FINISHED{standings:[]}` + status event (§3.7) | — |

`PHASE_STARTED` for step phases is public and carries no entry content. No entry text or stroke appears in a public event before `ALBUM_REVEALED`, in another viewer's snapshot, in a `GAME_ERROR` or in a log line; the private `TELEPHONE_TASK` reaches only the next holder, and the source's author is not part of it.

**`TelephoneSnapshot`** is §2.1. A holder's snapshot has their `task`, `draft`, `submitted`; during `REVEAL` the viewer gets `reveal` instead. Worst-case sizes (design §8.3): `DESCRIBE`/`DRAW` ≈ 210 KB; `REVEAL` ≈ 240 KB. **Reveal size budget:** the cursor entry is always included with its strokes, then earlier revealed entries newest-first while the cumulative serialized size of the included `strokes` arrays is ≤ 205 000 bytes; every other drawing entry is sent with `strokes:null, omitted:true` (its text/metadata stays). Omitted drawings become available through `GET /sessions/{id}/albums/{chainIndex}` once the session is `FINISHED`. A test serializes the maximal snapshots and asserts `< 262 144` bytes.

**`ALBUM_REVEALED`**: `entry` is a `RevealEntryDto` (`omitted:false`, `strokes` present for drawings); `newlyRevealed:false` (cursor moved over an entry the clients already received) carries `entry:null`. A client that lacks such an entry (it reconnected after it was omitted) shows "available in the results". `autoAdvanced` is true when the server moved the cursor because of the idle deadline. `autoAdvanceAt` is display-only: when the host becomes `DISCONNECTED` the server brings the pending auto-advance forward to at most 15 s from then without publishing an event; snapshots carry the current value.

**Results.** `GAME_FINISHED.standings` and `ResultsDto.standings` are empty. `ResultsDto.rounds[]` lists the completed steps: `roundNo = step + 1`, `endReason` ∈ `ALL_SUBMITTED|TIME`, `meta:{step, kind, submittedCount, timedOutCount, leftCount}` (no content). Content is only in the album endpoints (§2), authorized for current members of the session's room, read from the database, available once the session is `FINISHED` (`complete:false` albums after an early finish, `INSUFFICIENT_PLAYERS`).

**Settings and start.** `entriesPerChain` is resolved when the roster freezes (`min(setting, n)`, auto = `n` if even else `n − 1`); the effective value is `totalSteps`. Start errors are the engine's; no Telephone-specific start check exists besides the roster size.

### 3.11 Impostor games: shared part and Dino Impostor (Phase 9 — design only; decisions D50–D64, approved rows in `decisions-and-risks.md` §9)

Rules: `docs/game-rules.md` (`[DI-1]…[DI-14]`); plan: `docs/dino-impostor-plan.md`. Game key `dino-impostor`; `GameDefinition`: 4–10 players, `minActivePlayers` 3, scored. Phases (`PHASE_STARTED.phase`): `ROLE_REVEAL`, `DRAWING` (**one phase per turn**, each with a fresh `phaseId`), `VOTE`, `GUESS`, `ROUND_END`. All commands carry `actionId` and the current `phaseId` (§3.3); the validation order is the engine pipeline (`game-engine.md` §4.1). The **shared** commands and events below (prefix `IMPOSTOR_*`, D62) are also used by Sketchy Saurus (§3.12), whose voting phase is called `GALLERY`.

**Commands**

| `type` | Sender | Legal phase | Payload | Per-type limits | Effect | Errors (code — when) |
|---|---|---|---|---|---|---|
| `IMPOSTOR_DRAW_STROKE` | the turn's drawer | `DRAWING` | identical to `DRAW_STROKE` (§3.9) except `tool` must be `"PENCIL"` and `color` the sender's session colour | 8 KiB; 30/s, burst 40 | Appends the batch to the shared canvas (`drawing.CanvasBuffer`, `[DG-14]` limits) and publishes `IMPOSTOR_DRAWING_UPDATED`; the `final` batch ends the turn | `NOT_DRAWER` (not the drawer of this turn), `VALIDATION_FAILED` (schema; `ERASER`; wrong colour), `INVALID_ACTION` (batch order/style), `ALREADY_SUBMITTED` (a second stroke in the turn), `PAYLOAD_TOO_LARGE`, `STALE_ACTION` (an earlier turn's `phaseId`, or after the turn deadline), `INVALID_PHASE`, `RATE_LIMITED` |
| `IMPOSTOR_CHAT` *(shared)* | roster member, not `LEFT` | `VOTE` / `GALLERY` | `{text}`: after stripping `Cc`/`Cf` and trimming, 1–120 code points | 1 KiB; 5 per 10 s | Publishes `IMPOSTOR_CHAT_MESSAGE`. The module enforces the exact limit as a sliding 10 s window per player (`RATE_LIMITED`, nothing changes); the transport limit (0.5/s, burst 5) is only a per-second backstop | `VALIDATION_FAILED`, `RATE_LIMITED`, `STALE_ACTION`, `INVALID_PHASE`, `NOT_IN_ROOM` |
| `IMPOSTOR_VOTE` *(shared)* | roster member, not `LEFT` | `VOTE` / `GALLERY` | `{targetGuestId:uuid\|null}` — `null` withdraws the vote | 256 B; 10 per 10 s | Records/replaces/withdraws the sender's vote; publishes `IMPOSTOR_VOTE_PROGRESS`; may close the phase early (`[DI-9]`). Same 10 s sliding window in the module (10 votes); transport backstop 1/s, burst 10 | `VALIDATION_FAILED` (not a uuid; self; not a roster member; a member who is `LEFT`), `STALE_ACTION`, `INVALID_PHASE`, `NOT_IN_ROOM` |
| `IMPOSTOR_GUESS` *(shared)* | the caught impostor | `GUESS` | `{text}`: 1–80 code points after trim (as `SUBMIT_GUESS`) | 1 KiB; 2/s, burst 3 | Final guess; ends `GUESS` | `FORBIDDEN` (anyone else), `VALIDATION_FAILED`, `ALREADY_SUBMITTED`, `STALE_ACTION`, `INVALID_PHASE` |

A second guess, or a stroke after the turn's `turnStroke` is `closed`, ends in practice with `STALE_ACTION`: the final batch ends the turn and a guess ends `GUESS` in the same transition, so the `phaseId` is already old (or the phase is `INVALID_PHASE`). `ALREADY_SUBMITTED` stays as a defensive check in the module.

Rejections leave state unchanged; `ACTION_ACK` follows accepted commands. Roles, turns and targets are checked against the authenticated principal; the payload never names the sender.

**Events**

| `type` | Stream | Payload |
|---|---|---|
| `PHASE_STARTED` | public | common fields plus `round`, `totalRounds`; `ROLE_REVEAL`: `seatOrder:[guestId]`, `colors:{guestId:"#RRGGBB"}`, `nicknames:{guestId:string}` (the snapshot's map, G8) (P9 contract fix G1: a client that was in the room at the start never gets a snapshot, so it learns the round's seats and the session colours here); `DRAWING`: `lap` (1-based, 1…`laps`), `laps`, `turn` (0-based), `totalTurns`, `drawerGuestId`, `seatOrder:[guestId]`; `GUESS`: `impostorGuestId` |
| `IMPOSTOR_ROLE` *(shared)* | private, every roster member not `LEFT`, in the `ROLE_REVEAL` transition | `{phaseId, round, role:"ARTIST"\|"IMPOSTOR", category:{key, label}, word:string\|null}` — same shape for everyone; see §3.12 for the unaware impostor |
| `IMPOSTOR_DRAWING_UPDATED` | public | `{kind:"STROKE", authorGuestId, strokeId, batchNo, tool, color, size, points, final}` — each accepted batch unchanged plus its author; the drawer's client ignores its own echo |
| `IMPOSTOR_CHAT_MESSAGE` *(shared)* | public | `{guestId, text, ts}` |
| `IMPOSTOR_VOTE_PROGRESS` *(shared)* | public | `{phaseId, votedGuestIds:[guestId]}` — who has a vote in, never the targets. Sent once with `[]` when voting opens, after every accepted vote, and when a player becoming `LEFT` changes the voted list |
| `IMPOSTOR_VOTE_CLOSED` *(shared)* | public, closing event of `VOTE`/`GALLERY` | `{round, votes:{voterGuestId:targetGuestId}, accusedGuestId:guestId\|null /* strict top, else null */, caught:boolean, impostorGuestId:guestId\|null /* only when caught */}` |
| `IMPOSTOR_GUESS_OPEN` *(shared)* | private, the caught impostor | `{phaseId, round, category:{key, label}, deadlineAt}` |
| `IMPOSTOR_ROUND_ENDED` *(shared)* | public, closing event of the round | `{round, outcome:"ESCAPED"\|"GUESSED"\|"CAUGHT"\|"IMPOSTOR_LEFT", impostorGuestId, category:{key, label}, word, decoyWord:string\|null, votes:{voter:target}, guess:string\|null, scoreDeltas:{guestId:int}, scores:{guestId:int}}` — `CAUGHT` = caught and wrong or no guess. `scoreDeltas` has every player seated this round (0 included). With `IMPOSTOR_LEFT`, `votes` holds the votes still standing after the leaver's votes and the votes for them were dropped, and nobody scores. `ScoreDelta` reasons: `IMPOSTOR_ESCAPED`, `IMPOSTOR_GUESSED`, `ARTIST_CAUGHT`, `ARTIST_VOTE` |

**Secrets.** The word and the decoy appear in no public event before `IMPOSTOR_ROUND_ENDED`; the impostor's identity in none before `IMPOSTOR_VOTE_CLOSED` (when caught) or `IMPOSTOR_ROUND_ENDED`; vote targets in none before `IMPOSTOR_VOTE_CLOSED`. None of them appear in another viewer's snapshot, a `GAME_ERROR` or a log line. Every `IMPOSTOR_ROLE` of a round is sent in the same transition and serializes to the same set of fields.

**Turns (Dino Impostor).** The round's `seatOrder` and `totalTurns` (= `laps × n`, `n` = seated players) are fixed when the round starts; turn `t` belongs to `seatOrder[t mod n]` in lap `⌊t / n⌋ + 1`. A seat whose player is `LEFT` is skipped, so `turn` may jump. Session colours in `colors` are uppercase `#RRGGBB`; a batch's `color` must equal the sender's, compared case-insensitively. When a turn ends with the drawer's stroke still open (deadline, drawer `LEFT`), the server closes it as it stands without publishing a batch: clients close an open stroke when the next `PHASE_STARTED` arrives, and snapshots show it with `closed:true`.

**Names and reconnecting drawers.** `nicknames` is taken from the roster frozen at start (`PlayerInfo`, as Telephone's `authorNickname`), so strokes, seats, votes and the reveal can be labelled after a player left; clients prefer the live room roster and fall back to this map. A drawer who reconnects mid-turn finds their stroke in `turnStroke`: if it is open (`closed:false`) they may continue it with `strokeId` and `nextBatchNo`; if it exists and is closed, the turn's stroke is used and the canvas stays locked (`ALREADY_SUBMITTED` for anything more); `null` means no stroke has been started yet.

**`DinoImpostorSnapshot`** (common header first, §2.1):

```
DinoImpostorSnapshot { round, totalRounds, laps, seatOrder:[guestId], colors:{guestId:"#RRGGBB"},
                       nicknames:{guestId:string} /* every member of the frozen session roster, incl. those who left (G2) */,
                       lap|null, turn|null, totalTurns, drawerGuestId|null /* DRAWING only */,
                       turnStroke:{strokeId, nextBatchNo:int, closed:boolean}|null /* DRAWING only: the drawer's one stroke of this turn if it was started (G4); public */,
                       role:{role, category:{key, label}, word|null}|null /* viewer only; null for spectators */,
                       strokes:ImpostorStroke[] /* the shared canvas in drawing order */,
                       canvasBudget:{points, maxPoints:12000, strokes, maxStrokes:300},
                       votedGuestIds:[guestId] /* VOTE…ROUND_END; empty before, cleared at the next ROLE_REVEAL */, myVote:guestId|null /* VOTE…ROUND_END: the viewer's own vote only */,
                       recentChat:[{guestId, text, ts}] /* ≤ 30, oldest first; empty outside VOTE…ROUND_END */,
                       voteClosed:IMPOSTOR_VOTE_CLOSED payload|null /* GUESS, ROUND_END */,
                       roundEnd:IMPOSTOR_ROUND_ENDED payload|null /* ROUND_END */ }
ImpostorStroke       { …Stroke (§2.1), closed:boolean, authorGuestId }
```

**Results.** `standings[].detail`: `{roundsAsImpostor, escapes, correctGuesses, impostorsFound}` (`impostorsFound` = rounds the player voted for a caught impostor). All four count recorded rounds only; `roundsAsImpostor` includes an `IMPOSTOR_LEFT` round. `ResultsDto.rounds[].meta`: `{impostorGuestId, word, category, decoyWord|null, outcome, accusedGuestId|null}`; `endReason` = `outcome`.

### 3.12 Sketchy Saurus (Phase 9 — design only)

Rules: `docs/game-rules.md` (`[SS-1]…[SS-3]` plus the shared `[DI-*]`). Game key `sketchy-saurus`; 4–10 players, `minActivePlayers` 3, scored. Phases: `ROLE_REVEAL`, `SKETCH`, `GALLERY` (voting), `GUESS`, `ROUND_END`. Shared commands and events as §3.11 (`GALLERY` wherever §3.11 says `VOTE`).

**Commands** (Sketchy only)

| `type` | Sender | Legal phase | Payload | Per-type limits | Effect | Errors |
|---|---|---|---|---|---|---|
| `SKETCHY_DRAW_STROKE` | roster member, not `LEFT` | `SKETCH` | identical to `DRAW_STROKE` (§3.9) | 8 KiB; 30/s, burst 40 | Appends to the sender's private draft (≤ 1 000 live points, 60 strokes per drawing, other `[DG-14]` limits); **not published** | as `TELEPHONE_DRAW_STROKE` (§3.10) |
| `SKETCHY_DRAW_UNDO` / `SKETCHY_DRAW_CLEAR` | same | `SKETCH` | `{}` | as `TELEPHONE_DRAW_UNDO`/`CLEAR` | On the sender's draft | `ALREADY_SUBMITTED`, `STALE_ACTION`, `INVALID_PHASE` |
| `SKETCHY_SUBMIT` | same | `SKETCH` | `{}` | 128 B; 2/s, burst 3 | Makes the draft (possibly empty) final; `SKETCHY_PROGRESS`; ends `SKETCH` if it was the last | `ALREADY_SUBMITTED`, `STALE_ACTION`, `INVALID_PHASE` |

**Events** (Sketchy only)

| `type` | Stream | Payload |
|---|---|---|
| `SKETCHY_PROGRESS` | public | `{phaseId, submittedGuestIds:[guestId]}`: sent (empty) after `PHASE_STARTED{SKETCH}`, after every accepted submit and when a player becomes `LEFT` in `SKETCH`; the one sent by the last submit is a closing event of `SKETCH`, before `PHASE_STARTED{GALLERY}`. Players not `LEFT` only, in session order |
| `SKETCHY_GALLERY_OPENED` | public, after `PHASE_STARTED{GALLERY}` | `{round, drawings:[{guestId, strokes:Stroke[], empty:boolean}]}` in seat order of the roster (`order`), one per player not `LEFT` when `SKETCH` ended; every stroke has `closed:true` (a stroke still open at the end is closed as it stands) |

**Phase events.** `PHASE_STARTED` carries `round`, `totalRounds` and, in `ROLE_REVEAL`, `nicknames:{guestId:string}` (as §3.11, G8); Sketchy Saurus has no seats or colours.

**Who may guess, after a reconnect (G7).** The snapshot's `voteClosed` is the public `IMPOSTOR_VOTE_CLOSED` payload, so in `GUESS` it names the caught impostor (`caught:true`, `impostorGuestId`) in every viewer's snapshot, the impostor's own included. A client decides "I may guess now" from `phase = GUESS && voteClosed.impostorGuestId = me`; `role` stays `ARTIST` for an unaware impostor and is not updated. The guess's `category` is `role.category`. No `guessOpen` field is added.

**Unaware impostor** (`impostorKnows = false`, D63). Their `IMPOSTOR_ROLE` is `{role:"ARTIST", category, word:<decoy>}`: the same fields, types and transition as every artist's, and their snapshot's `role` likewise; nothing they receive before `IMPOSTOR_VOTE_CLOSED` differs in shape. When caught with `unawareGuess = true` they get `IMPOSTOR_GUESS_OPEN`; with `unawareGuess = false` the round goes from `GALLERY` straight to `ROUND_END` (`outcome:"CAUGHT"`). `IMPOSTOR_ROUND_ENDED.decoyWord` names the decoy.

**`SketchySaurusSnapshot`**:

```
SketchySaurusSnapshot { round, totalRounds, nicknames:{guestId:string} /* as DinoImpostorSnapshot */,
                        role:{role, category:{key, label}, word|null}|null /* viewer only */,
                        draft:{strokes:Stroke[], canvasBudget:{points, maxPoints:1000, strokes, maxStrokes:60}}|null /* SKETCH: viewer's own; after submitting it holds the submitted drawing until SKETCH ends (G6) */,
                        submitted:boolean /* false outside SKETCH */, submittedGuestIds:[guestId] /* empty outside SKETCH */,
                        gallery:[{guestId, strokes:Stroke[], empty}]|null /* GALLERY onward */,
                        votedGuestIds, myVote, recentChat, voteClosed, roundEnd /* as DinoImpostorSnapshot */ }
```

Size: with the D64 budget (1 000 live points, 60 strokes per drawing) the maximal `GALLERY` snapshot (10 full drawings + 30 chat lines) and the maximal `SKETCHY_GALLERY_OPENED` serialize to `< 262 144` bytes; tests assert both. A full drawing is about 121 bytes per stroke + 14 per point, so the first figure of 1 500 points and 100 strokes (about 33 KB each, 330 KB for ten) did not fit and was lowered.

**Results** as §3.11.

## 4. Phase 4 changes to existing contract text

Everything below is additive unless stated.

| Where | Change |
|---|---|
| §2 `start`, `kick`, `close`, `results` rows | Behavior and error codes specified; `kick` and `close` are implemented in Phase 4 (they were contract-only in Phases 2–3) |
| §2 `PATCH /guests/me` | `INVALID_PHASE` while the room is `STARTING`/`PLAYING` |
| `RoomDto` | New nullable `lastSession` |
| `SnapshotDto.game` | Now populated; common `GameSnapshotHeader` fields precede the game-specific fields |
| `ResultsDto` | `status`, `endReason`; `rounds[]` generalized (`meta` holds `drawerGuestId`/`word`) |
| §3.3 | `phaseId` required and `STALE_ACTION` semantics for game commands; per-type rate limits |
| §3.4 `DRAWING_UPDATED` | **Behavior change (Phase 5, unimplemented so far):** also delivered to the drawer, who ignores it |
| §3.4 `GAME_FINISHED` | Payload fixed (`endReason`, `resultsReady`; no nicknames) |
| §3.4 | New event `GAME_ABORTED` |
| §3.2 | No change: envelope, `seq`, `actionId` semantics are untouched |

### Phase 5 contract changes (Draw & Guess, decisions D24–D31 approved)

| Where | Change |
|---|---|
| §2.1 `Stroke`, `DrawGuessSnapshot` | `points` are integers 0–10000 (was floats 0–1; shared with Telephone, D28); `closed` flag; `DrawGuessSnapshot` drops fields duplicated by the common header and gains `roundEnd`; `recentChat` is viewer-filtered |
| §3.3 | `DRAW_STROKE` gains `batchNo`; every Draw & Guess command now lists its legal phase; per-type limits and errors in §3.9 |
| §3.4 | `GUESS_REJECTED_PUBLIC` renamed `GUESS_CHAT`; new `GUESSER_CHAT` (private, restricted audience); `GUESS_ACCEPTED` gains `order`; `ROUND_ENDED` gains `drawerGuestId`; `GUESS_FEEDBACK` loses `ALREADY_GUESSED`; `DRAWING_UPDATED` payload specified per `kind` |
| §3.4 `GAME_ERROR` | Module-raised `PAYLOAD_TOO_LARGE`/`NOT_DRAWER`/`IS_DRAWER` are private `GAME_ERROR`s |
| §3.9 | New: Draw & Guess commands, drawing protocol, hint format, phase/secret table, standings detail |
| §2.1 `DrawGuessSnapshot`, §3.9 | `canvasBudget` added; live-point budget 12 000 (not the 15 000 target: does not fit 256 KiB with `[[x,y]]` pairs); `DRAW_STROKE` ≤ 8 KiB; budget exhaustion rejects the whole batch with `PAYLOAD_TOO_LARGE`; `recentChat` ≤ 30 |

### Phase 6 contract changes (Dino Telephone, design only; decisions D35–D48 proposed)

| Where | Change |
|---|---|
| §1 error codes | New `ALREADY_SUBMITTED` (module rejection of a second submit/draft in a step; replaces the `DUPLICATE_ACTION` wording of `[TP-7]`), `ALBUM_NOT_FOUND` |
| §2 | New `GET /sessions/{id}/albums`; `GET …/albums/{chainIndex}` specified (authorization, `RESULTS_NOT_READY` while `RUNNING`, `FINISHED` only) |
| §2.1 | `AlbumDto` extended (`sessionId`, `entriesPerChain`, `complete`, entry `authorNickname`, `fill`, `placeholder`); new `AlbumSummaryDto`, `AlbumEntryDto`, `RevealEntryDto`; `TelephoneSnapshot` rewritten: no `WAITING` phase, `draft`, `submittedGuestIds`, `reveal` with a size budget, `entriesPerChain` |
| §2.2 | Telephone settings gain `entriesPerChain` (0 or 4–8); `allowSkipTimeout` accepts only `true` |
| §3.3 | `TELEPHONE_SUBMIT_DRAWING` payload `{strokes}` → `{}` (commit of a private draft); new `TELEPHONE_TEXT_DRAFT`, `TELEPHONE_DRAW_STROKE`/`UNDO`/`CLEAR`; `REVEAL_NEXT`/`PREV` carry the cursor. The earlier 256 KB one-shot drawing and D20's frame-cap increase are no longer needed |
| §3.4 | `TELEPHONE_PROGRESS` gains `phaseId`, `submittedGuestIds`; new `TELEPHONE_STEP_ENDED`; `ALBUM_REVEALED` payload specified; `TELEPHONE_TASK.source` gains `placeholder` and never carries the author |
| §3.10 | New: Telephone commands, events, secrecy table, snapshot size budget, results |

### P7-01 contract changes (shared platform)

| Where | Change |
|---|---|
| §2, §2.6 | `GET /games` lists every registered game in key order, disabled ones with `enabled:false`; `GET /games/{gameKey}` answers 200 for a disabled game and `404 GAME_NOT_FOUND` for an unknown key. Both are served from the backend game registry |
| §2.1 | `SettingField` gains `type:"list"`, `autoValue`, `maxItems`, `itemFormat`, `exclusiveOption`; `step` removed (never used); absent members are omitted |
| §2.1 | `LastSessionDto` semantics spelled out (countdown keeps the previous value, `RESULTS` shows the finished session, `endReason` per status) |
| §3.4 | `ROOM_CLOSED.reason` gains `IDLE` (idle-room sweep) |

### Phase 9 contract changes (Dino Impostor, Sketchy Saurus; design only, decisions D50–D64)

| Where | Change |
|---|---|
| §1 error codes | None new: out-of-turn strokes use `NOT_DRAWER`, a second stroke/submit `ALREADY_SUBMITTED`, a guess by anyone but the caught impostor `FORBIDDEN`, a too-small word pool `WORD_POOL_TOO_SMALL` |
| §2.2 | `maxPlayers` bounds and settings for `dino-impostor` and `sketchy-saurus` |
| §3.6 | Family prefix for types shared by games built on shared code |
| §3.11 | New: shared impostor commands/events (`IMPOSTOR_*`), Dino Impostor commands, events, secrets, snapshot, results |
| §3.12 | New: Sketchy Saurus commands/events (`SKETCHY_*`), unaware impostor, snapshot and size bound |
| §3.11 (P9-fix) | Contract requests G1–G4 of P9-05 resolved: `ROLE_REVEAL` `PHASE_STARTED` carries `seatOrder` and `colors` (G1); snapshots carry `nicknames` (G2, also in `SketchySaurusSnapshot`); `lap` is 1-based, as written in P9-02 (G3; the P9-05 client assumed 0-based and must be fixed); `DinoImpostorSnapshot.turnStroke` (G4); stroke `size` stays 1–4 (G5: the client's fixed size 2 is valid, the "1 to 32" in the request was wrong) |
| §3.11, §3.12 (P9-fix 2) | Contract requests G6–G8 of P9-06 resolved: Sketchy `draft` keeps the submitted drawing until `SKETCH` ends (G6); `voteClosed` in the snapshot is how a reconnecting impostor knows they may guess, no new field (G7); `ROLE_REVEAL` `PHASE_STARTED` carries `nicknames` in both games (G8) |
| §3.6, `game-engine.md` §1.2 (P9-fix) | Registry: a command or event type may be declared by several modules of one family if every declaration is identical (D65) |
| §3.11 (P9-03) | Clarified: module-enforced 10 s sliding window for chat and vote; `IMPOSTOR_VOTE_PROGRESS` also on opening and on a leave; `IMPOSTOR_LEFT` round-end `votes`; snapshot field lifetimes; second guess / closed stroke are in practice `STALE_ACTION`; stats count recorded rounds; `ScoreDelta` reasons |
| §3.11 (P9-02) | Clarified: `lap` is 1-based; fixed seat order per round with `LEFT` seats skipped; colours uppercase and compared case-insensitively; an open stroke closed at the turn's end gets no event |
| §3.12 (P9-04) | Clarified: per-drawing budget lowered to 1 000 live points and 60 strokes (D64, the first 1 500 and 100 did not fit 256 KiB); `SKETCHY_PROGRESS` timing; gallery strokes are all `closed`; `submitted`/`submittedGuestIds` only inside `SKETCH`; an unaware impostor learns their role from the existing private `IMPOSTOR_GUESS_OPEN` (no new event); an automatic start with `impostorKnows` off needs ten word/decoy pairs or is `WORD_POOL_TOO_SMALL` |
