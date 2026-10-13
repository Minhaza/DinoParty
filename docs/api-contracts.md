# DinoParty — API Contracts (REST + STOMP)

This file is the **single source of truth** for client/server contracts. Backend DTOs and frontend types must match it. Changes require a contract-change commit (see `AGENTS.md`). Status: design (v0, pre-implementation); fields may be refined in Phase 2–3 but only via edits to this file.

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

- Error codes: `VALIDATION_FAILED, UNAUTHENTICATED, FORBIDDEN, NOT_HOST, ROOM_NOT_FOUND, ROOM_FULL, ROOM_IN_PROGRESS, ROOM_CLOSED, NOT_ENOUGH_PLAYERS, ALREADY_IN_ROOM, NOT_IN_ROOM, GAME_NOT_FOUND, SESSION_NOT_FOUND, RESULTS_NOT_READY, RATE_LIMITED, PAYLOAD_TOO_LARGE, STALE_ACTION, DUPLICATE_ACTION, INVALID_PHASE, INVALID_ACTION, NOT_DRAWER, IS_DRAWER, SERVER_ERROR, NOT_FOUND, METHOD_NOT_ALLOWED, UNSUPPORTED_MEDIA_TYPE`. The last three are generic framework-level codes for unmapped paths, wrong HTTP methods and wrong content types.
- Rate-limit responses: `429` + `Retry-After`.
- Idempotency: mutating room endpoints accept optional `Idempotency-Key` header (join/leave/start).

## 2. REST endpoints

| Method & path | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `GET /health` | no | — | `200 {status:"UP", version, db:"UP", time}` | `503` with the same body shape (`status:"DOWN"`, `db:"DOWN"`) if the DB is unreachable (not problem+json) |
| `POST /guests` | no | `{nickname, avatarKey, avatarColor?}` — nickname 2–20 chars (letters, digits, single spaces, `_ . ' -`; must start with a letter/digit), `avatarKey` from the fixed catalog, `avatarColor` `#RRGGBB` | `201 {guest: GuestDto, token, expiresAt}` | 400, 429 (per client IP) |
| `GET /guests/me` | yes | — | `200 GuestDto` (also renews sliding expiry) | 401 |
| `PATCH /guests/me` | yes | `{nickname?, avatarKey?, avatarColor?}` (absent/null = unchanged) | `200 GuestDto` | 400, 401, 409 (`INVALID_PHASE` while locked in a playing room — enforced from Phase 4) |
| `GET /games` | no | — | `200 GameSummaryDto[]` | |
| `GET /games/{gameKey}` | no | — | `200 GameDetailDto` (rules text, settings schema, bounds) | 404 |
| `POST /rooms` | yes | `{gameKey, settings?, maxPlayers?}` (defaults from the game; see §2.2) | `201 RoomDto` (+ `inviteUrl`) | 400, 404 `GAME_NOT_FOUND`, 409 (`ALREADY_IN_ROOM`) |
| `GET /rooms/{roomCode}` | yes | — | `200 RoomDto` (members only for details; non-members get `RoomPreviewDto`; a closed room is still returned with `status: "CLOSED"`) | 404 `ROOM_NOT_FOUND` (also for a malformed code) |
| `POST /rooms/{roomCode}/join` | yes | `{}` | `200 RoomDto`. Idempotent: joining a room you are already in returns it unchanged | 404, 409 (`ROOM_FULL`, `ROOM_IN_PROGRESS`, `ROOM_CLOSED`, `ALREADY_IN_ROOM` when in a *different* room), 429 (per guest) |
| `POST /rooms/{roomCode}/leave` | yes | — | `204`. Idempotent: a non-member (or already-left) caller is a no-op. If the host leaves, the longest-standing remaining player becomes host; the last player leaving closes the room | 404 |
| `PATCH /rooms/{roomCode}/settings` | host | `{gameKey?, settings?, maxPlayers?}` | `200 RoomDto` | 400, 403 `NOT_HOST` (also for non-members), 404, 409 `INVALID_PHASE` (room not in `WAITING`) |
| `POST /rooms/{roomCode}/ready` | member | `{ready: boolean}` | `200 RoomDto` | (WS command equivalent) |
| `POST /rooms/{roomCode}/start` | host | `{}` | `202 RoomDto` (status `STARTING`) | 403, 409 `NOT_ENOUGH_PLAYERS` |
| `POST /rooms/{roomCode}/kick` | host | `{guestId}` | `204` | 403 |
| `POST /rooms/{roomCode}/close` | host | — | `204` | 403 |
| `GET /rooms/{roomCode}/snapshot` | member | — | `200 SnapshotDto` (viewer-specific) | 403 `NOT_IN_ROOM` |
| `GET /sessions/{sessionId}/results` | member of the session's room | — | `200 ResultsDto` | 404, 409 `RESULTS_NOT_READY` |
| `GET /sessions/{sessionId}/albums/{chainIndex}` | member | — | `200 AlbumDto` (telephone, after reveal) | 404 |

### 2.1 DTOs (shape summaries)

```
GuestDto        { id, nickname, avatarKey, avatarColor|null, createdAt }
GameSummaryDto  { key, name, tagline, minPlayers, maxPlayers, iconKey, enabled }
GameDetailDto   { …GameSummaryDto, description, howToPlay[], settingsSchema: SettingField[], defaultSettings }
SettingField    { key, label, type:"int|bool|enum", min?, max?, step?, options?, default }
RoomDto         { id, code, inviteUrl, status, gameKey, hostGuestId, minPlayers, maxPlayers,
                  settings, players:PlayerDto[], sessionId|null, createdAt }
RoomPreviewDto  { code, gameKey, status, playerCount, maxPlayers, hostNickname }
PlayerDto       { guestId, nickname, avatarKey, avatarColor, ready, connection:"CONNECTED|DISCONNECTED|LEFT",
                  isHost, score|null }
SnapshotDto     { room:RoomDto, serverNow, seq:{room:int, private:int}, game: GameSnapshot|null }
ResultsDto      { sessionId, gameKey, startedAt, finishedAt, standings:[{rank, guestId, nickname, score, detail}],
                  rounds:[{roundNo, drawerGuestId?, word?, …}] }
AlbumDto        { chainIndex, ownerGuestId, entries:[{step, kind:"TEXT|DRAWING", authorGuestId, text?, strokes?, timedOut}] }
```

### 2.2 Room rules (Phase 2)

- A guest may be an active member of at most one room (`ALREADY_IN_ROOM` on create/join of another room). Leaving or the room closing frees them.
- `maxPlayers` defaults to 8 clamped to the game's bounds (`draw-guess` 2–10, `telephone` 4–8) and must stay within them; it cannot be set below the current player count (400).
- `settings` is a flat object validated against the game's schema (`draw-guess`: `cycles` 1–5, `drawSeconds` 30–180, `wordChoiceSeconds` 5–30, `hintReveal` bool, `difficulty` `easy|mixed|hard`; `telephone`: `promptSeconds` 20–120, `drawSeconds` 30–180, `describeSeconds` 20–90, `allowSkipTimeout` bool). Unknown keys or out-of-range values are 400 with `fieldErrors`; omitted keys take defaults. Supplying `settings` replaces the whole object. Changing `gameKey` resets settings to the supplied/default values.
- `players[]` lists active members ordered by join time; `ready` is `false`, `connection` is `CONNECTED` and `score` is `null` until presence/ready (Phase 3) and games (Phase 4+) exist. `sessionId` is `null`.
- `inviteUrl` = `{APP_PUBLIC_URL}/join/{CODE}`.
- Rate limits: `POST /guests` per client IP and join per guest answer `429 RATE_LIMITED` with `Retry-After`.

`GameSnapshot` is game-specific and viewer-specific:

```
DrawGuessSnapshot { phase:"WORD_CHOICE|DRAWING|ROUND_END|…", phaseId, round, totalRounds, drawerGuestId,
                    deadlineAt, hint, word|null /*drawer only*/, wordChoices|null /*drawer, WORD_CHOICE only*/,
                    strokes:Stroke[], guessedGuestIds[], scores:{guestId:int}, recentChat[] }
TelephoneSnapshot { phase:"PROMPT|DRAW|DESCRIBE|WAITING|REVEAL", phaseId, step, totalSteps, deadlineAt,
                    task:{kind, source:{kind, text|strokes}|null} /*viewer only*/, submitted:boolean,
                    waitingOn:int, reveal:{chainIndex, entryIndex}|null }
Stroke            { strokeId, tool:"PENCIL|ERASER", color:"#RRGGBB", size:int(1-4 index), points:[[x,y],…] /*0..1*/ }
```

## 3. STOMP

### 3.1 Connection

- Endpoint `wss://<api-host>/ws` (native WebSocket, no SockJS).
- `CONNECT` headers: `accept-version:1.2`, `heart-beat:10000,10000`, `Authorization: Bearer <token>`.
- Subscriptions (allowed only for room members):
  - `/topic/rooms/{code}` — public stream (`stream: "ROOM"`).
  - `/user/queue/rooms/{code}` — private stream (`stream: "PRIVATE"`).
  - `/user/queue/errors` — errors not tied to a room.
- Client sends to `/app/rooms/{code}/command`.

### 3.2 Envelope (all server events)

```json
{ "type": "…", "roomId": "uuid", "roomCode": "ABCD12", "ts": "ISO", "seq": 17,
  "stream": "ROOM|PRIVATE", "actionId": "echoed client id or null", "payload": { } }
```

Client commands use `{ "type": "…", "actionId": "uuid", "phaseId": "uuid|null", "payload": { } }`.
`seq` is gapless within a stream; a gap means the client must resync via `REQUEST_SNAPSHOT` / `GET …/snapshot`.

### 3.3 Client → server commands

| `type` | Who | Payload | Notes |
|---|---|---|---|
| `SET_READY` | member (WAITING) | `{ready}` | |
| `START_GAME` | host | `{}` | Same effect as REST start |
| `REQUEST_SNAPSHOT` | member | `{}` | Reply: private `ROOM_SNAPSHOT` |
| `CHOOSE_WORD` | drawer (WORD_CHOICE) | `{wordIndex:0-2}` | Index only; server holds words |
| `DRAW_STROKE` | drawer (DRAWING) | `{strokeId, tool, color, size, points:[[x,y]…], final}` | ≤200 points, normalized |
| `DRAW_UNDO` | drawer | `{}` | Removes last stroke |
| `DRAW_CLEAR` | drawer | `{}` | |
| `SUBMIT_GUESS` | non-drawer | `{text}` | Also serves as chat in Draw & Guess |
| `TELEPHONE_SUBMIT_TEXT` | active player | `{text}` | prompt or description |
| `TELEPHONE_SUBMIT_DRAWING` | active player | `{strokes:Stroke[]}` | one-shot |
| `REVEAL_NEXT` / `REVEAL_PREV` | host (REVEAL) | `{}` | |
| `REMATCH_VOTE` | member (RESULTS) | `{vote:true|false}` | |
| `PING` | any | `{}` | optional app-level latency probe |

### 3.4 Server → client events

Public stream (`/topic/rooms/{code}`):

| `type` | Payload |
|---|---|
| `PLAYER_JOINED` | `{player:PlayerDto}` |
| `PLAYER_LEFT` | `{guestId, reason:"LEFT|KICKED|TIMEOUT"}` |
| `READY_UPDATED` | `{guestId, ready}` |
| `HOST_CHANGED` | `{hostGuestId}` |
| `ROOM_SETTINGS_UPDATED` | `{gameKey, settings, maxPlayers}` |
| `ROOM_STATUS_CHANGED` | `{status}` (`STARTING` includes `startsAt`) |
| `GAME_STARTED` | `{sessionId, gameKey, settings, order:[guestId]}` |
| `PHASE_STARTED` | `{phase, phaseId, round, deadlineAt, … game-specific public data}` (e.g. `drawerGuestId`, `hint`) |
| `DRAWING_UPDATED` | `{kind:"STROKE|UNDO|CLEAR", stroke?, strokeId?}` (not sent to the drawer) |
| `HINT_UPDATED` | `{hint}` |
| `GUESS_ACCEPTED` | `{guestId, scoreDelta, totalScore}` (no word) |
| `GUESS_REJECTED_PUBLIC`/chat | `{guestId, text}` shown as chat |
| `ROUND_ENDED` | `{round, word, reason:"TIME|ALL_GUESSED|DRAWER_LEFT", scoreDeltas:{guestId:int}, scores:{guestId:int}}` |
| `TELEPHONE_PROGRESS` | `{submittedCount, total}` |
| `ALBUM_REVEALED` | `{chainIndex, entryIndex, entry}` |
| `GAME_FINISHED` | `{sessionId, standings:[…]}` |
| `REMATCH_VOTES_UPDATED` | `{votes:{guestId:boolean}}` |
| `PLAYER_DISCONNECTED` | `{guestId, graceEndsAt}` |
| `PLAYER_RECONNECTED` | `{guestId}` |
| `ROOM_CLOSED` | `{reason}` |

Private stream (`/user/queue/rooms/{code}`):

| `type` | Payload |
|---|---|
| `ROOM_SNAPSHOT` | `SnapshotDto` |
| `WORD_CHOICES` | `{phaseId, choices:[string×3], deadlineAt}` — drawer only |
| `DRAWER_WORD` | `{phaseId, word}` — drawer only |
| `GUESS_FEEDBACK` | `{result:"CLOSE|DUPLICATE|ALREADY_GUESSED"}` |
| `TELEPHONE_TASK` | `{phaseId, step, kind:"PROMPT|DRAW|DESCRIBE", source:{kind, text?, strokes?}|null, deadlineAt}` |
| `ACTION_ACK` | `{actionId, accepted:true}` |
| `GAME_ERROR` | `{actionId?, code, message, retryable}` — rejected command (also `PAYLOAD_TOO_LARGE`, `RATE_LIMITED`, `STALE_ACTION`) |
| `SESSION_REPLACED` | `{}` then connection closed |

### 3.5 Ordering and delivery guarantees

- Events within a stream are ordered by `seq`; STOMP over a single WebSocket preserves order.
- No exactly-once delivery: clients dedupe by `seq`; commands dedupe by `actionId` server-side.
- On reconnect the client resubscribes, calls `REQUEST_SNAPSHOT`, applies the snapshot, then processes stream events with `seq > snapshot.seq` for that stream.
- Secret data (`DRAWER_WORD`, `WORD_CHOICES`, `TELEPHONE_TASK`, snapshot fields marked viewer-only) never appears on the public stream or in another viewer's snapshot (contract-tested).

### 3.6 Extension rule for new games

New games add command and event `type` values with a game prefix (e.g. `FAKEARTIST_*`) and a `GameSnapshot` variant; they must not change the envelope, common events, or existing game payloads.
