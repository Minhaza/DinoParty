# DinoParty — API Contracts (REST + STOMP)

This file is the **single source of truth** for client/server contracts. Backend DTOs and frontend types must match it. Changes require a contract-change commit (see `AGENTS.md`). Status: REST (Phase 2) and the lobby STOMP layer (Phase 3) are implemented as written; rows marked *Phase 4+* are still design. Fields may be refined, but only via edits to this file. The Phase 4 additions (game lifecycle, generic game events, snapshot `game`, rematch) are **design pending approval** (`docs/decisions-and-risks.md` D13–D23); the engine behind them is specified in `docs/game-engine.md`. Phase 4 changes to existing Phase 2/3 contract text are listed in §4.

**Phase 4 implementation status (backend, verified by the integration tests listed in `docs/testing.md`):** implemented as written: `START_GAME` and `REMATCH_VOTE`, game commands with `phaseId`/`STALE_ACTION`, per-type rate and size limits, `ROOM_STATUS_CHANGED`, `GAME_STARTED`, `PHASE_STARTED`, `GAME_FINISHED`, `GAME_ABORTED`, `REMATCH_VOTES_UPDATED`, snapshot `game`, `RoomDto.sessionId` and `PlayerDto.score`, `POST /rooms/{code}/start|kick|close`, `GET /sessions/{id}/results`, restart recovery. **Not implemented yet (still contract only):** `GET /games`, `GET /games/{key}`, `RoomDto.lastSession`, the `PATCH /guests/me` lock (`INVALID_PHASE` while `STARTING`/`PLAYING`), `Retry-After` on `RESULTS_NOT_READY`, `Idempotency-Key` on start. `GAME_FINISHED.resultsReady` is always `false`, and a client reconnecting during `STARTING` receives no `startsAt` in its snapshot. `maxPayloadBytes` of a `CommandSpec` is checked against the serialized payload object, and the rate is a fixed one-second window of `max(burst, ceil(rate))` commands. The frame cap stays 64 KiB (D20 is decided in Phase 6).

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

- Optional extension `details` (object): machine-readable facts about a failure, only on codes that document it (currently `WORD_POOL_TOO_SMALL`: `{required:int, available:int}`, §2.4). Never contains secrets such as words.
- Error codes: `VALIDATION_FAILED, UNAUTHENTICATED, FORBIDDEN, NOT_HOST, ROOM_NOT_FOUND, ROOM_FULL, ROOM_IN_PROGRESS, ROOM_CLOSED, NOT_ENOUGH_PLAYERS, WORD_POOL_TOO_SMALL, ALREADY_IN_ROOM, NOT_IN_ROOM, GAME_NOT_FOUND, SESSION_NOT_FOUND, RESULTS_NOT_READY, RATE_LIMITED, PAYLOAD_TOO_LARGE, STALE_ACTION, DUPLICATE_ACTION, INVALID_PHASE, INVALID_ACTION, NOT_DRAWER, IS_DRAWER, SERVER_ERROR, NOT_FOUND, METHOD_NOT_ALLOWED, UNSUPPORTED_MEDIA_TYPE`. The last three are generic framework-level codes for unmapped paths, wrong HTTP methods and wrong content types.
- Rate-limit responses: `429` + `Retry-After`.
- Idempotency: mutating room endpoints accept optional `Idempotency-Key` header (join/leave/start).

## 2. REST endpoints

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `GET /health` | no | — | `200 {status:"UP", version, db:"UP", time}` | `503` with the same body shape (`status:"DOWN"`, `db:"DOWN"`) if the DB is unreachable (not problem+json) |
| `POST /guests` | no | `{nickname, avatarKey, avatarColor?}` — nickname 2–20 chars (letters, digits, single spaces, `_ . ' -`; must start with a letter/digit), `avatarKey` from the fixed catalog, `avatarColor` `#RRGGBB` | `201 {guest: GuestDto, token, expiresAt}` | 400, 429 (per client IP) |
| `GET /guests/me` | yes | — | `200 GuestDto` (also renews sliding expiry) | 401 |
| `PATCH /guests/me` | yes | `{nickname?, avatarKey?, avatarColor?}` (absent/null = unchanged) | `200 GuestDto` | 400, 401, 409 (`INVALID_PHASE` while the guest's room is `STARTING` or `PLAYING` — enforced from Phase 4; allowed again in `RESULTS`/`WAITING`) |
| `GET /games` | no | — | `200 GameSummaryDto[]` | |
| `GET /games/{gameKey}` | no | — | `200 GameDetailDto` (rules text, settings schema, bounds) | 404 |
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
| `GET /sessions/{sessionId}/albums/{chainIndex}` | member | — | `200 AlbumDto` (telephone, after reveal) | 404 |
| `GET /word-catalog/categories?locale=` | yes | — | `200 WordCategoriesDto` (§2.3) | 400 `VALIDATION_FAILED` (`locale`), 401 |
| `GET /word-catalog/difficulties` | yes | — | `200 WordDifficultiesDto` (§2.3) | 401 |
| `GET /word-catalog/counts?locale=&category=&difficulty=` | yes | — | `200 WordCountsDto` (§2.3) | 400 `VALIDATION_FAILED` (`locale`, `category`, `difficulty`), 401 |

### 2.1 DTOs (shape summaries)

```
GuestDto        { id, nickname, avatarKey, avatarColor|null, createdAt }
GameSummaryDto  { key, name, tagline, minPlayers, maxPlayers, iconKey, enabled }
GameDetailDto   { …GameSummaryDto, description, howToPlay[], settingsSchema: SettingField[], defaultSettings }
SettingField    { key, label, type:"int|bool|enum", min?, max?, step?, options?, default }
RoomDto         { id, code, inviteUrl, status, gameKey, hostGuestId, minPlayers, maxPlayers,
                  settings, players:PlayerDto[], sessionId|null, createdAt,
                  lastSession: LastSessionDto|null /* Phase 4, additive */ }
LastSessionDto  { sessionId, status:"FINISHED|ABORTED|INTERRUPTED", endReason, finishedAt }
                  /* newest session of this room that is no longer running; null before the first game and
                     cleared when the next session starts. INTERRUPTED/SERVER_RESTART is how clients learn that
                     a restart ended their game (game-engine.md §9) */
RoomPreviewDto  { code, gameKey, status, playerCount, maxPlayers, hostNickname }
PlayerDto       { guestId, nickname, avatarKey, avatarColor, ready, connection:"CONNECTED|DISCONNECTED|LEFT",
                  isHost, score|null }
SnapshotDto     { room:RoomDto, serverNow, seq:{room:int, private:int}, game: GameSnapshot|null /* null when no session is RUNNING/FINISHED */ }
ResultsDto      { sessionId, gameKey, status:"FINISHED|ABORTED|INTERRUPTED", endReason, startedAt, finishedAt,
                  standings:[{rank, guestId, nickname, score, detail}] /* empty for unscored games (telephone) */,
                  rounds:[{roundNo, startedAt, endedAt, endReason, scoreDeltas:{guestId:int}, meta}] /* meta: game-specific, e.g. drawerGuestId, word */ }
AlbumDto        { chainIndex, ownerGuestId, entries:[{step, kind:"TEXT|DRAWING", authorGuestId, text?, strokes?, timedOut}] }
```

### 2.2 Room rules (Phase 2)

- A guest may be an active member of at most one room (`ALREADY_IN_ROOM` on create/join of another room). Leaving or the room closing frees them.
- `maxPlayers` defaults to 8 clamped to the game's bounds (`draw-guess` 2–10, `telephone` 4–8) and must stay within them; it cannot be set below the current player count (400).
- `settings` is a flat object validated against the game's schema (`draw-guess`: `cycles` 1–5, `drawSeconds` 30–180, `wordChoiceSeconds` 5–30, `hintReveal` bool, `difficulty` `easy|medium|hard|mixed`, `wordSource` `built-in|custom|combined`, `wordCategories` list of ≤ 20 distinct category keys or `["random-mix"]`, `wordPackIds` list of ≤ 10 distinct lowercase UUID strings — word keys in §2.4; `telephone`: `promptSeconds` 20–120, `drawSeconds` 30–180, `describeSeconds` 20–90, `allowSkipTimeout` bool). Unknown keys or out-of-range values are 400 with `fieldErrors`; omitted keys take defaults. Supplying `settings` replaces the whole object. Changing `gameKey` resets settings to the supplied/default values.
- `players[]` lists active members ordered by join time. `score` is the engine's running total while a session is `RUNNING`/`FINISHED` (after `RESULTS`→`WAITING` it is `null` again) and always `null` for unscored games; `sessionId` is the current session (`RUNNING` or awaiting rematch) or `null`. Members who left or timed out during a session are no longer in `players[]`; their final score stays in the results.
- `ready` (Phase 3) is server-authoritative and persisted per membership: `false` on join/rejoin, changed only by `SET_READY` / `POST …/ready` while the room is `WAITING`.
- `connection` (Phase 3): `DISCONNECTED` while a member's STOMP connection is gone and the grace period (`app.realtime.disconnect-grace`, default 30 s) has not expired; otherwise `CONNECTED`. A member who has never opened a STOMP connection (REST-only client) is reported `CONNECTED` and is never timed out. `LEFT` is not used in `players[]` (left members are removed).
- When the host leaves or is removed on timeout, the new host is the longest-standing remaining player who is not currently `DISCONNECTED`; if everyone left is disconnected, the longest-standing one.
- `inviteUrl` = `{APP_PUBLIC_URL}/join/{CODE}`.
- Rate limits: `POST /guests` per client IP and join per guest answer `429 RATE_LIMITED` with `Retry-After`.

`GameSnapshot` is viewer-specific. Every game's snapshot starts with the engine-owned common header (Phase 4) followed by the game's own fields, flat in the same object:

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
TelephoneSnapshot { phase:"PROMPT|DRAW|DESCRIBE|WAITING|REVEAL", phaseId, step, totalSteps, deadlineAt,
                    task:{kind, source:{kind, text|strokes}|null} /*viewer only*/, submitted:boolean,
                    waitingOn:int, reveal:{chainIndex, entryIndex}|null }
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
| `TELEPHONE_SUBMIT_TEXT` | active player | `{text}` | *Phase 6*. prompt or description |
| `TELEPHONE_SUBMIT_DRAWING` | active player | `{strokes:Stroke[]}` | *Phase 6*. one-shot |
| `REVEAL_NEXT` / `REVEAL_PREV` | host (REVEAL) | `{}` | *Phase 6* |
| `REMATCH_VOTE` | member (`RESULTS`) | `{vote:true|false}` | *Phase 4*. No `phaseId`. A voter may change their vote until the room resets. The host's `true` resets the room to `WAITING`; so does the last connected member's `true` once every connected member voted `true` (game-engine.md §10). `INVALID_PHASE` outside `RESULTS` |

Game command rules (Phase 4, apply to every row from `CHOOSE_WORD` down except `REMATCH_VOTE`):

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
| `ROOM_CLOSED` | `{reason:"EMPTY"|"HOST_CLOSED"}`. Phase 3 only closes a room when its last member leaves (`EMPTY`), so nobody is subscribed any more and no client receives it yet; it becomes observable with host-initiated close | 3 |
| `ROOM_STATUS_CHANGED` | `{status, reason, startsAt?}` — `reason`: `HOST_STARTED` (→`STARTING`, with `startsAt`), `COUNTDOWN_DONE` (→`PLAYING`), `GAME_FINISHED` (→`RESULTS`), `START_CANCELLED` (→`WAITING`, fewer than `minPlayers` connected), `REMATCH`, `RESULTS_TIMEOUT`, `GAME_ABORTED` (→`WAITING`), `SERVER_RESTART` (→`WAITING`, see §3.8). Its position relative to `GAME_STARTED`/`GAME_FINISHED`/`GAME_ABORTED` is fixed in §3.7 | 4 |
| `GAME_STARTED` | `{sessionId, gameKey, settings, order:[guestId]}` — `order` is the engine's shuffled play order (roster, fixed for the session) | 4 |
| `PHASE_STARTED` | `{phase, phaseId, round, deadlineAt, … game-specific public data}` (e.g. `drawerGuestId`, `hint`). Emitted by the engine for **every** phase change of every game, always after the state change and never carrying secrets; `phaseId` is fresh for every phase | 4 (engine), 5 (content) |
| `DRAWING_UPDATED` | `{kind:"STROKE", strokeId, batchNo, tool, color, size, points, final}` \| `{kind:"UNDO", strokeId}` \| `{kind:"CLEAR"}` (§3.9). Delivered on the public stream, therefore **also to the drawer**, whose client must ignore it (it is its own echo). Changed from "not sent to the drawer": per-recipient delivery would consume one private `seq` per member per stroke | 5 |
| `HINT_UPDATED` | `{hint}` — `"_a__ ___"` pattern (§3.9) | 5 |
| `GUESS_ACCEPTED` | `{guestId, scoreDelta, totalScore, order}` (no word; `order` = 0-based solver index) | 5 |
| `GUESS_CHAT` | `{guestId, text}` — a wrong guess shown as public chat (replaces the earlier name `GUESS_REJECTED_PUBLIC`) | 5 |
| `ROUND_ENDED` | `{round, drawerGuestId, word, reason:"TIME|ALL_GUESSED|DRAWER_LEFT", scoreDeltas:{guestId:int}, scores:{guestId:int}}`; `word` is `null` only when the drawer left before a word was chosen. `scoreDeltas` lists every roster member in play order (0 for no points this round, including members who left); `scores` are the running totals of every roster member | 5 |
| `TELEPHONE_PROGRESS` | `{submittedCount, total}` | 6 |
| `ALBUM_REVEALED` | `{chainIndex, entryIndex, entry}` | 6 |
| `GAME_FINISHED` | `{sessionId, endReason:"COMPLETED|INSUFFICIENT_PLAYERS", standings:[{rank, guestId, score, detail}], resultsReady}` — nicknames are not repeated, clients use the roster. `standings` is empty for unscored games. `resultsReady:false` means `GET …/results` may still answer `RESULTS_NOT_READY` | 4 |
| `GAME_ABORTED` | `{sessionId, reason:"NO_COMPLETED_ROUND|INTERNAL_ERROR|SERVER_RESTART|SERVER_SHUTDOWN|ROOM_CLOSED"}`. The session ended without results; the room goes to `WAITING` (or `CLOSED`). `SERVER_RESTART` is never emitted live (only `lastSession` shows it, §3.8) | 4 |
| `REMATCH_VOTES_UPDATED` | `{votes:{guestId:boolean}}` (full tally; only connected roster members appear) | 4 |

Ordering of the events produced by one operation: leaving ⇒ `PLAYER_LEFT`, then `HOST_CHANGED` (if the host left), then `ROOM_CLOSED` (if the room emptied). A joining member produces `PLAYER_JOINED` only when the join is new (idempotent re-joins emit nothing). Disconnect/reconnect produce `PLAYER_DISCONNECTED` / `PLAYER_RECONNECTED` once per transition.

Private stream (`/user/queue/rooms/{code}`):

| `type` | Payload | Phase |
|---|---|---|
| `ROOM_SNAPSHOT` | `SnapshotDto` | 3 |
| `ACTION_ACK` | `{actionId, accepted:true}` (envelope `actionId` echoes it) | 3 |
| `GAME_ERROR` | `{actionId?, code, message, retryable, details?}` — `details` as in §1, only when the code documents it (e.g. `WORD_POOL_TOO_SMALL` on `START_GAME`); rejected command of a member (`VALIDATION_FAILED`, `INVALID_ACTION`, `INVALID_PHASE`, `NOT_HOST`, `DUPLICATE_ACTION`, `STALE_ACTION`; `RATE_LIMITED`, `PAYLOAD_TOO_LARGE`, `NOT_IN_ROOM` and `SERVER_ERROR` arrive as `ERROR` on `/user/queue/errors` with the same payload; exception: `PAYLOAD_TOO_LARGE`, `NOT_DRAWER` and `IS_DRAWER` raised by a *game module* — e.g. the Draw & Guess stroke budgets — are private `GAME_ERROR`s like any module rejection, because only the transport-level byte caps are decided before the mailbox) | 3 |
| `PONG` | `{serverNow}` | 3 |
| `SESSION_REPLACED` | `{}` then the connection is closed | 3 |
| `WORD_CHOICES` | `{phaseId, choices:[string×3], deadlineAt}` — drawer only | 5 |
| `DRAWER_WORD` | `{phaseId, word}` — drawer only | 5 |
| `GUESS_FEEDBACK` | `{result:"CLOSE|DUPLICATE"}` — only to the sender of the guess (`ALREADY_GUESSED` is dropped: a solver's text is restricted chat) | 5 |
| `GUESSER_CHAT` | `{guestId, text}` — restricted chat from a solver, delivered only to the other solvers and the drawer | 5 |
| `TELEPHONE_TASK` | `{phaseId, step, kind:"PROMPT|DRAW|DESCRIBE", source:{kind, text?, strokes?}|null, deadlineAt}` | 6 |

`SESSION_REPLACED` is delivered to the *replaced* connection only; `ACTION_ACK`, `GAME_ERROR`, `ROOM_SNAPSHOT` and `PONG` go to the guest's current connection.

### 3.5 Ordering and delivery guarantees

- Events within a stream are ordered by `seq`; the server publishes in `seq` order (`preservePublishOrder`), and room events are produced inside the room's serialized mailbox after the state change has committed. Frames from one connection are processed in the order they were sent (`preserveReceiveOrder`).
- No exactly-once delivery: clients dedupe by `seq`; commands dedupe by `actionId` server-side.
- **Snapshot consistency**: a snapshot (REST or WS) is built inside the room mailbox, so it is exactly the state after the last event with `seq ≤ snapshot.seq.room` (and `≤ snapshot.seq.private` for the private stream). A reconnecting client resubscribes, sends `REQUEST_SNAPSHOT`, applies the snapshot, then processes stream events with `seq > snapshot.seq` for that stream.
- `SnapshotDto.serverNow` is the server clock in ISO-8601 UTC millis; clients derive a clock offset from it.
- Secret data (`DRAWER_WORD`, `WORD_CHOICES`, `TELEPHONE_TASK`, snapshot fields marked viewer-only) never appears on the public stream or in another viewer's snapshot (contract-tested).
- Error responses and logs never contain tokens, stack traces or echoed client input. (Spring logs raw STOMP headers, including `Authorization`, when `org.springframework.messaging` is set to `TRACE`; the default configuration pins it to `INFO` and it must never be enabled in production.)

### 3.6 Extension rule for new games

New games add command and event `type` values with a game prefix (e.g. `FAKEARTIST_*`) and a `GameSnapshot` variant; they must not change the envelope, common events, or existing game payloads. (Draw & Guess and Telephone keep their unprefixed names from §3.3/§3.4; they are the reference games.)

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
