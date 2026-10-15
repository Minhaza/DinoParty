import { beforeEach, describe, expect, it } from 'vitest'

import type { EventEnvelope, GameSnapshot, PlayerDto, RoomDto, SnapshotDto } from '@/api/types'
import { useDrawGuessStore } from '@/features/drawguess/drawGuessStore'
import {
  describePhaseStarted,
  describeTask,
  drawProgress,
  drawSnapshotWithDraft,
  drawStepEnded,
  PHASE_DESCRIBE,
  PHASE_DRAW,
} from '@/features/telephone/fixtures'
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
