import { beforeEach, describe, expect, it } from 'vitest'

import type { EventEnvelope, GameSnapshot, PlayerDto, RoomDto, SnapshotDto } from '@/api/types'
import { useDrawGuessStore } from '@/features/drawguess/drawGuessStore'
import {
  albumRevealed,
  describePhaseStarted,
  describeTask,
  drawProgress,
  drawSnapshotWithDraft,
  drawStepEnded,
  PHASE_DESCRIBE,
  PHASE_DRAW,
  PHASE_REVEAL,
  revealPhaseStarted,
  revealSnapshot,
} from '@/features/telephone/fixtures'
import { useRevealStore } from '@/features/telephone/revealStore'
import { useTelephoneStore } from '@/features/telephone/telephoneStore'

import { useGameSessionStore } from './gameSessionStore'
import { useRoomStore } from './roomStore'

// One holder's view of a Telephone game, fed envelope by envelope as the STOMP client would.

const player = (guestId: string, isHost = false): PlayerDto => ({
  guestId,
  nickname: guestId,
  avatarKey: 'dino-rex',
  avatarColor: null,
  ready: false,
  connection: 'CONNECTED',
  isHost,
  score: null,
})

const room: RoomDto = {
  id: 'room-1',
  code: 'TELE01',
  inviteUrl: 'http://localhost/join/TELE01',
  status: 'PLAYING',
  gameKey: 'telephone',
  hostGuestId: 'guest-1',
  minPlayers: 4,
  maxPlayers: 8,
  settings: {},
  players: ['guest-1', 'guest-2', 'guest-3', 'guest-4'].map((id, i) => player(id, i === 0)),
  sessionId: 'session-tp-01',
  createdAt: '2026-10-14T10:00:00.000Z',
}

let roomSeq = 0
let privateSeq = 0
const envelope = (
  stream: 'ROOM' | 'PRIVATE',
  type: string,
  payload: unknown,
  actionId: string | null = null,
): EventEnvelope => ({
  type,
  roomId: 'room-1',
  roomCode: 'TELE01',
  ts: '2026-10-14T10:00:30.000Z',
  seq: stream === 'ROOM' ? ++roomSeq : ++privateSeq,
  stream,
  actionId,
  payload,
})
const pub = (type: string, payload: unknown) => envelope('ROOM', type, payload)
const priv = (type: string, payload: unknown, actionId: string | null = null) =>
  envelope('PRIVATE', type, payload, actionId)

const apply = (e: EventEnvelope) => useRoomStore.getState().applyEnvelope(e)
const telephone = () => useTelephoneStore.getState()

describe('roomStore × Dino Telephone', () => {
  beforeEach(() => {
    useRoomStore.getState().reset()
    roomSeq = 20
    privateSeq = 6
    const snapshot: SnapshotDto = {
      room,
      serverNow: '2026-10-14T10:00:20.000Z',
      seq: { room: roomSeq, private: privateSeq },
      game: drawSnapshotWithDraft as unknown as GameSnapshot,
    }
    useRoomStore.getState().applySnapshot(snapshot)
  })

  it('hydrates the Telephone store from the room snapshot', () => {
    expect(useGameSessionStore.getState().phaseId).toBe(PHASE_DRAW)
    expect(telephone().kind).toBe('DRAW')
    expect(telephone().strokes).toHaveLength(1)
    // Draw & Guess state is untouched by a Telephone game.
    expect(useDrawGuessStore.getState().strokes).toEqual([])
  })

  it('routes progress, step end, the next phase and its private task', () => {
    apply(pub('TELEPHONE_PROGRESS', drawProgress))
    expect(telephone().progress?.submittedCount).toBe(2)

    apply(pub('TELEPHONE_STEP_ENDED', drawStepEnded))
    expect(telephone().stepEnded?.reason).toBe('TIME')

    apply(pub('PHASE_STARTED', describePhaseStarted))
    apply(priv('TELEPHONE_TASK', describeTask))
    expect(useGameSessionStore.getState().phaseId).toBe(PHASE_DESCRIBE)
    expect(telephone().kind).toBe('DESCRIBE')
    expect(telephone().task?.source).toEqual(describeTask.source)
    expect(telephone().strokes).toEqual([])
  })

  it('accepts a submission only on the server ACK', () => {
    telephone().trackCommand('act-1', 'TELEPHONE_SUBMIT_DRAWING', PHASE_DRAW)
    expect(telephone().submission).toBe('submitting')
    apply(priv('ACTION_ACK', {}, 'act-1'))
    expect(telephone().submission).toBe('submitted')
  })

  it('routes errors of Telephone commands to the Telephone store, without a banner', () => {
    telephone().trackCommand('act-2', 'TELEPHONE_DRAW_STROKE', PHASE_DRAW, 'stroke-x')
    apply(
      priv(
        'GAME_ERROR',
        { actionId: 'act-2', code: 'PAYLOAD_TOO_LARGE', message: 'canvas full', retryable: false },
        'act-2',
      ),
    )
    expect(telephone().canvasFull).toBe(true)
    expect(useRoomStore.getState().lastError).toBeNull()
    expect(useRoomStore.getState().needsResync).toBe(true)
  })

  it('still shows errors for commands no game store sent', () => {
    apply(priv('GAME_ERROR', { code: 'NOT_IN_ROOM', message: 'Not in room', retryable: false }))
    expect(useRoomStore.getState().lastError).toBe('Not in room')
  })

  it('TP13 a rematch back to WAITING resets the Telephone store', () => {
    apply(pub('ROOM_STATUS_CHANGED', { status: 'WAITING', reason: 'REMATCH' }))
    expect(telephone().strokes).toEqual([])
    expect(telephone().kind).toBeNull()
  })

  it('a duplicated private task is applied once and changes nothing the second time', () => {
    apply(pub('PHASE_STARTED', describePhaseStarted))
    const task = priv('TELEPHONE_TASK', describeTask)
    expect(apply(task)).toBe('applied')
    telephone().setTextDraft('a heron in a hat')
    expect(apply(task)).toBe('ignored')
    expect(telephone().textDraft).toBe('a heron in a hat')
  })

  it('a late task of the previous session never reaches the rematch session', () => {
    apply(
      pub('GAME_STARTED', {
        sessionId: 'session-tp-02',
        gameKey: 'telephone',
        settings: {},
        order: ['guest-2', 'guest-1', 'guest-4', 'guest-3'],
      }),
    )
    apply(pub('PHASE_STARTED', { ...describePhaseStarted, phaseId: 'phase-rematch-prompt' }))
    // Phase ids are server UUIDs, unique across sessions: the old task cannot match the new step.
    apply(priv('TELEPHONE_TASK', describeTask))
    expect(telephone().task).toBeNull()
  })

  it('TP8 the assignment and draft are dropped when the game finishes', () => {
    telephone().setTextDraft('half typed')
    apply(
      pub('GAME_FINISHED', {
        sessionId: 'session-tp-01',
        endReason: 'COMPLETED',
        standings: [],
        resultsReady: true,
      }),
    )
    expect(telephone().task).toBeNull()
    expect(telephone().strokes).toEqual([])
    expect(telephone().textDraft).toBe('')
  })

  it('entering another room drops every trace of the previous room', () => {
    telephone().setTextDraft('a draft of room 1')
    useRoomStore.getState().enterRoom({ ...room, id: 'room-2', code: 'OTHER2', sessionId: null })
    expect(telephone().textDraft).toBe('')
    expect(telephone().kind).toBeNull()
    expect(useGameSessionStore.getState().phaseId).toBeNull()
    expect(useRoomStore.getState().lastSeq).toEqual({ room: 0, private: 0 })
    expect(useRoomStore.getState().room?.code).toBe('OTHER2')

    // Re-entering the same room keeps its state.
    telephone().setTextDraft('kept')
    useRoomStore.getState().enterRoom({ ...room, id: 'room-2', code: 'OTHER2', sessionId: null })
    expect(telephone().textDraft).toBe('kept')
  })

  it('a new GAME_STARTED clears the previous session', () => {
    apply(
      pub('GAME_STARTED', {
        sessionId: 'session-tp-02',
        gameKey: 'telephone',
        settings: {},
        order: ['guest-2', 'guest-1', 'guest-4', 'guest-3'],
      }),
    )
    expect(telephone().task).toBeNull()
    expect(telephone().strokes).toEqual([])
  })
})

describe('roomStore × Dino Telephone reveal', () => {
  const reveal = () => useRevealStore.getState()

  beforeEach(() => {
    useRoomStore.getState().reset()
    roomSeq = 40
    privateSeq = 9
    useRoomStore.getState().applySnapshot({
      room,
      serverNow: '2026-10-14T10:00:20.000Z',
      seq: { room: roomSeq, private: privateSeq },
      game: drawSnapshotWithDraft as unknown as GameSnapshot,
    })
    apply(pub('PHASE_STARTED', revealPhaseStarted))
    apply(pub('ALBUM_REVEALED', albumRevealed(0, 0)))
  })

  it('TP6 routes the reveal phase and its entries to the reveal store', () => {
    expect(useGameSessionStore.getState().phase).toBe('REVEAL')
    expect(reveal().phaseId).toBe(PHASE_REVEAL)
    expect(reveal().sessionId).toBe('session-tp-01')
    expect(reveal().entries['0:0']?.text).toBe('Prompt 1')
    // the step store holds no task or draft in the reveal ([TP-8])
    expect(telephone().task).toBeNull()
    expect(telephone().strokes).toEqual([])

    apply(pub('ALBUM_REVEALED', albumRevealed(0, 1)))
    expect(reveal().cursor).toEqual({ chainIndex: 0, entryIndex: 1 })
  })

  it('settles a host move on its ACK and resyncs a stale one without a banner', () => {
    reveal().trackNav('nav-1')
    apply(priv('ACTION_ACK', {}, 'nav-1'))
    expect(reveal().pendingActionId).toBeNull()

    reveal().trackNav('nav-2')
    apply(
      priv(
        'GAME_ERROR',
        { actionId: 'nav-2', code: 'STALE_ACTION', message: 'moved on', retryable: false },
        'nav-2',
      ),
    )
    expect(reveal().pendingActionId).toBeNull()
    expect(useRoomStore.getState().needsResync).toBe(true)
    expect(useRoomStore.getState().lastError).toBeNull()
  })

  it('TP6 a reconnect snapshot restores the reveal position', () => {
    useRoomStore.getState().applySnapshot({
      room,
      serverNow: '2026-10-14T10:05:00.000Z',
      seq: { room: 90, private: 12 },
      game: revealSnapshot as unknown as GameSnapshot,
    })
    expect(reveal().cursor).toEqual({ chainIndex: 0, entryIndex: 3 })
    expect(reveal().highWater).toEqual({ chainIndex: 1, entryIndex: 0 })
  })

  it('the end of the game hands the albums over to the results (store cleared)', () => {
    apply(
      pub('GAME_FINISHED', {
        sessionId: 'session-tp-01',
        endReason: 'COMPLETED',
        standings: [],
        resultsReady: false,
      }),
    )
    expect(reveal().phaseId).toBeNull()
    expect(reveal().entries).toEqual({})
  })

  it('TP13 a rematch, a new game or another room leaves no album behind', () => {
    apply(pub('ROOM_STATUS_CHANGED', { status: 'WAITING', reason: 'REMATCH' }))
    expect(reveal().entries).toEqual({})

    apply(pub('PHASE_STARTED', revealPhaseStarted))
    apply(pub('ALBUM_REVEALED', albumRevealed(0, 0)))
    apply(
      pub('GAME_STARTED', {
        sessionId: 'session-tp-02',
        gameKey: 'telephone',
        settings: {},
        order: room.players.map((p) => p.guestId),
      }),
    )
    expect(reveal().entries).toEqual({})

    apply(pub('PHASE_STARTED', revealPhaseStarted))
    apply(pub('ALBUM_REVEALED', albumRevealed(0, 0)))
    useRoomStore.getState().enterRoom({ ...room, id: 'room-3', code: 'OTHER3', sessionId: null })
    expect(reveal().phaseId).toBeNull()
    expect(reveal().entries).toEqual({})
  })
})
