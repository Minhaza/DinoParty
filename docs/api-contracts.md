# DinoParty — API Contracts (REST + STOMP)

This file is the **single source of truth** for client/server contracts. Backend DTOs and frontend types must match it. Changes require a contract-change commit (see `AGENTS.md`). Status: REST (Phase 2) and the lobby STOMP layer (Phase 3) are implemented as written; rows marked *Phase 4+* are still design. Fields may be refined, but only via edits to this file.

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
| `POST /rooms/{roomCode}/ready` | member | `{ready: boolean}` | `200 RoomDto`. Same effect as the WS `SET_READY` command; only while `WAITING` | 403 `NOT_IN_ROOM`, 404, 409 `INVALID_PHASE` |
| `POST /rooms/{roomCode}/start` | host | `{}` | `202 RoomDto` (status `STARTING`) | 403, 409 `NOT_ENOUGH_PLAYERS` |
| `POST /rooms/{roomCode}/kick` | host | `{guestId}` | `204` | 403 |
| `POST /rooms/{roomCode}/close` | host | — | `204` | 403 |
| `GET /rooms/{roomCode}/snapshot` | member | — | `200 SnapshotDto` (viewer-specific; `game` is `null` until Phase 4) | 403 `NOT_IN_ROOM`, 404 `ROOM_NOT_FOUND` |
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
SnapshotDto     { room:RoomDto, serverNow, seq:{room:int, private:int}, game: GameSnapshot|null /* null until Phase 4 */ }
ResultsDto      { sessionId, gameKey, startedAt, finishedAt, standings:[{rank, guestId, nickname, score, detail}],
                  rounds:[{roundNo, drawerGuestId?, word?, …}] }
AlbumDto        { chainIndex, ownerGuestId, entries:[{step, kind:"TEXT|DRAWING", authorGuestId, text?, strokes?, timedOut}] }
```

### 2.2 Room rules (Phase 2)

- A guest may be an active member of at most one room (`ALREADY_IN_ROOM` on create/join of another room). Leaving or the room closing frees them.
- `maxPlayers` defaults to 8 clamped to the game's bounds (`draw-guess` 2–10, `telephone` 4–8) and must stay within them; it cannot be set below the current player count (400).
- `settings` is a flat object validated against the game's schema (`draw-guess`: `cycles` 1–5, `drawSeconds` 30–180, `wordChoiceSeconds` 5–30, `hintReveal` bool, `difficulty` `easy|mixed|hard`; `telephone`: `promptSeconds` 20–120, `drawSeconds` 30–180, `describeSeconds` 20–90, `allowSkipTimeout` bool). Unknown keys or out-of-range values are 400 with `fieldErrors`; omitted keys take defaults. Supplying `settings` replaces the whole object. Changing `gameKey` resets settings to the supplied/default values.
- `players[]` lists active members ordered by join time. `score` and `sessionId` are `null` until games exist (Phase 4+).
- `ready` (Phase 3) is server-authoritative and persisted per membership: `false` on join/rejoin, changed only by `SET_READY` / `POST …/ready` while the room is `WAITING`.
- `connection` (Phase 3): `DISCONNECTED` while a member's STOMP connection is gone and the grace period (`app.realtime.disconnect-grace`, default 30 s) has not expired; otherwise `CONNECTED`. A member who has never opened a STOMP connection (REST-only client) is reported `CONNECTED` and is never timed out. `LEFT` is not used in `players[]` (left members are removed).
- When the host leaves or is removed on timeout, the new host is the longest-standing remaining player who is not currently `DISCONNECTED`; if everyone left is disconnected, the longest-standing one.
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

Implemented in Phase 3: `SET_READY`, `REQUEST_SNAPSHOT`, `PING`. Every other `type` in the table is reserved for later phases and is currently answered with `GAME_ERROR` `INVALID_ACTION` (`retryable:false`), as is an unknown `type`.

| `type` | Who | Payload | Notes |
|---|---|---|---|
| `SET_READY` | member (`WAITING`) | `{ready:boolean}` | Idempotent (setting the current value emits no event but is acknowledged). `INVALID_PHASE` if the room is not `WAITING` |
| `REQUEST_SNAPSHOT` | member | `{}` | Reply: private `ROOM_SNAPSHOT` (consumes one private `seq`; `snapshot.seq.private` equals that envelope's `seq`) |
| `PING` | any member | `{}` | Reply: private `PONG {serverNow}`; no `actionId` required |
| `START_GAME` | host | `{}` | *Phase 4+*. Same effect as REST start |
| `CHOOSE_WORD` | drawer (WORD_CHOICE) | `{wordIndex:0-2}` | *Phase 5*. Index only; server holds words |
| `DRAW_STROKE` | drawer (DRAWING) | `{strokeId, tool, color, size, points:[[x,y]…], final}` | *Phase 5*. ≤200 points, normalized |
| `DRAW_UNDO` | drawer | `{}` | *Phase 5*. Removes last stroke |
| `DRAW_CLEAR` | drawer | `{}` | *Phase 5* |
| `SUBMIT_GUESS` | non-drawer | `{text}` | *Phase 5*. Also serves as chat in Draw & Guess |
| `TELEPHONE_SUBMIT_TEXT` | active player | `{text}` | *Phase 6*. prompt or description |
| `TELEPHONE_SUBMIT_DRAWING` | active player | `{strokes:Stroke[]}` | *Phase 6*. one-shot |
| `REVEAL_NEXT` / `REVEAL_PREV` | host (REVEAL) | `{}` | *Phase 6* |
| `REMATCH_VOTE` | member (RESULTS) | `{vote:true|false}` | *Phase 4* |

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
| `ROOM_STATUS_CHANGED` | `{status}` (`STARTING` includes `startsAt`) | 4 |
| `GAME_STARTED` | `{sessionId, gameKey, settings, order:[guestId]}` | 4 |
| `PHASE_STARTED` | `{phase, phaseId, round, deadlineAt, … game-specific public data}` (e.g. `drawerGuestId`, `hint`) | 5 |
| `DRAWING_UPDATED` | `{kind:"STROKE|UNDO|CLEAR", stroke?, strokeId?}` (not sent to the drawer) | 5 |
| `HINT_UPDATED` | `{hint}` | 5 |
| `GUESS_ACCEPTED` | `{guestId, scoreDelta, totalScore}` (no word) | 5 |
| `GUESS_REJECTED_PUBLIC`/chat | `{guestId, text}` shown as chat | 5 |
| `ROUND_ENDED` | `{round, word, reason:"TIME|ALL_GUESSED|DRAWER_LEFT", scoreDeltas:{guestId:int}, scores:{guestId:int}}` | 5 |
| `TELEPHONE_PROGRESS` | `{submittedCount, total}` | 6 |
| `ALBUM_REVEALED` | `{chainIndex, entryIndex, entry}` | 6 |
| `GAME_FINISHED` | `{sessionId, standings:[…]}` | 4 |
| `REMATCH_VOTES_UPDATED` | `{votes:{guestId:boolean}}` | 4 |

Ordering of the events produced by one operation: leaving ⇒ `PLAYER_LEFT`, then `HOST_CHANGED` (if the host left), then `ROOM_CLOSED` (if the room emptied). A joining member produces `PLAYER_JOINED` only when the join is new (idempotent re-joins emit nothing). Disconnect/reconnect produce `PLAYER_DISCONNECTED` / `PLAYER_RECONNECTED` once per transition.

Private stream (`/user/queue/rooms/{code}`):

| `type` | Payload | Phase |
|---|---|---|
| `ROOM_SNAPSHOT` | `SnapshotDto` | 3 |
| `ACTION_ACK` | `{actionId, accepted:true}` (envelope `actionId` echoes it) | 3 |
| `GAME_ERROR` | `{actionId?, code, message, retryable}` — rejected command of a member (`VALIDATION_FAILED`, `INVALID_ACTION`, `INVALID_PHASE`, `NOT_HOST`, `DUPLICATE_ACTION`, `STALE_ACTION`; `RATE_LIMITED`, `PAYLOAD_TOO_LARGE`, `NOT_IN_ROOM` and `SERVER_ERROR` arrive as `ERROR` on `/user/queue/errors` with the same payload) | 3 |
| `PONG` | `{serverNow}` | 3 |
| `SESSION_REPLACED` | `{}` then the connection is closed | 3 |
| `WORD_CHOICES` | `{phaseId, choices:[string×3], deadlineAt}` — drawer only | 5 |
| `DRAWER_WORD` | `{phaseId, word}` — drawer only | 5 |
| `GUESS_FEEDBACK` | `{result:"CLOSE|DUPLICATE|ALREADY_GUESSED"}` | 5 |
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

New games add command and event `type` values with a game prefix (e.g. `FAKEARTIST_*`) and a `GameSnapshot` variant; they must not change the envelope, common events, or existing game payloads.
