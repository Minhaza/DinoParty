import { beforeEach, describe, expect, it } from 'vitest'

import type { EventEnvelope, GameSnapshot, RoomDto, SnapshotDto } from '@/api/types'
import { useDrawGuessStore } from '@/features/drawguess/drawGuessStore'
import { useDinoImpostorStore } from '@/features/impostor/dino/dinoImpostorStore'
import {
  ARTIST_ROLE,
  DI_PLAYERS,
  drawingOtherTurnArtist,
  drawingStarted,
  guessOpenEvent,
  roleRevealStarted,
  rolePayload,
  roundEndedPayload,
  strokeBatches,
  voteClosedCaught,
  voteStarted,
} from '@/features/impostor/dino/fixtures'

import { useGameSessionStore } from './gameSessionStore'
import { useRoomStore } from './roomStore'

// One artist's view of a Dino Impostor round, fed envelope by envelope as the STOMP client would.

const room: RoomDto = {
  id: 'room-di',
  code: 'DINO01',
  inviteUrl: 'http://localhost/join/DINO01',
  status: 'PLAYING',
  gameKey: 'dino-impostor',
  hostGuestId: 'di-g1',
  minPlayers: 4,
  maxPlayers: 10,
  settings: {},
  players: DI_PLAYERS,
  sessionId: 'session-di-fixture',
  createdAt: '2026-10-22T10:00:00.000Z',
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
  roomId: 'room-di',
  roomCode: 'DINO01',
  ts: '2026-10-22T10:00:30.000Z',
  seq: stream === 'ROOM' ? ++roomSeq : ++privateSeq,
  stream,
  actionId,
  payload,
})
const pub = (type: string, payload: unknown) => envelope('ROOM', type, payload)
const priv = (type: string, payload: unknown, actionId: string | null = null) =>
  envelope('PRIVATE', type, payload, actionId)
const apply = (e: EventEnvelope) => useRoomStore.getState().applyEnvelope(e)
const impostor = () => useDinoImpostorStore.getState()

const snapshotDto = (game: GameSnapshot | null): SnapshotDto => ({
  room,
  serverNow: '2026-10-22T10:00:30.000Z',
  seq: { room: 40, private: 8 },
  game,
})

describe('roomStore × Dino Impostor', () => {
  beforeEach(() => {
    useRoomStore.getState().reset()
    roomSeq = 40
    privateSeq = 8
    useRoomStore
      .getState()
      .applySnapshot(snapshotDto(drawingOtherTurnArtist as unknown as GameSnapshot))
  })

  it('hydrates the Dino Impostor store from the room snapshot only', () => {
    expect(impostor().strokes).toHaveLength(3)
    expect(impostor().colors['di-g1']).toBeDefined()
    expect(useGameSessionStore.getState().gameKey).toBe('dino-impostor')
    expect(useDrawGuessStore.getState().strokes).toEqual([])
  })

  it('routes a whole round of events to the impostor store, not to Draw & Guess', () => {
    expect(apply(pub('PHASE_STARTED', roleRevealStarted))).toBe('applied')
    expect(apply(priv('IMPOSTOR_ROLE', rolePayload(ARTIST_ROLE)))).toBe('applied')
    expect(impostor().role).toEqual(ARTIST_ROLE)
    expect(impostor().strokes).toEqual([])

    apply(pub('PHASE_STARTED', drawingStarted(0)))
    expect(useGameSessionStore.getState().phase).toBe('DRAWING')
    for (const batch of strokeBatches('di-g2')) apply(pub('IMPOSTOR_DRAWING_UPDATED', batch))
    expect(impostor().strokes).toHaveLength(1)
    expect(impostor().strokes[0]).toMatchObject({ authorGuestId: 'di-g2', closed: true })
    expect(useDrawGuessStore.getState().strokes).toEqual([])
    expect(useDrawGuessStore.getState().drawerGuestId).toBeNull()

    apply(pub('PHASE_STARTED', voteStarted))
    apply(pub('IMPOSTOR_CHAT_MESSAGE', { guestId: 'di-g2', text: 'hm', ts: 't' }))
    apply(pub('IMPOSTOR_VOTE_PROGRESS', { phaseId: voteStarted.phaseId, votedGuestIds: ['di-g2'] }))
    expect(impostor().chat).toHaveLength(1)
    expect(impostor().votedGuestIds).toEqual(['di-g2'])

    impostor().trackCommand('vote-1', { type: 'IMPOSTOR_VOTE', target: 'di-g4' })
    apply(priv('ACTION_ACK', { actionId: 'vote-1', accepted: true }, 'vote-1'))
    expect(impostor().myVote).toBe('di-g4')

    apply(pub('IMPOSTOR_VOTE_CLOSED', voteClosedCaught))
    apply(
      pub('PHASE_STARTED', {
        ...voteStarted,
        phase: 'GUESS',
        phaseId: 'g',
        impostorGuestId: 'di-g4',
      }),
    )
    apply(priv('IMPOSTOR_GUESS_OPEN', guessOpenEvent))
    expect(impostor().voteClosed?.caught).toBe(true)

    const ended = roundEndedPayload('CAUGHT')
    apply(pub('IMPOSTOR_ROUND_ENDED', ended))
    expect(impostor().roundEnd?.outcome).toBe('CAUGHT')
    expect(useGameSessionStore.getState().scores).toMatchObject(ended.scores)
  })

  it('asks for a resync on a seq gap, like the other games', () => {
    roomSeq += 1 // one public event lost
    expect(apply(pub('IMPOSTOR_DRAWING_UPDATED', strokeBatches('di-g5')[0]))).toBe('gap')
    expect(useRoomStore.getState().needsResync).toBe(true)
    expect(impostor().strokes).toHaveLength(3)

    // The snapshot replaces the store and the remembered seq wholesale.
    useRoomStore
      .getState()
      .applySnapshot(snapshotDto(drawingOtherTurnArtist as unknown as GameSnapshot))
    expect(useRoomStore.getState().needsResync).toBe(false)
    expect(useRoomStore.getState().lastSeq).toEqual({ room: 40, private: 8 })
  })

  it('asks for a snapshot when a turn starts and the colours are unknown (G1)', () => {
    useDinoImpostorStore.setState({ colors: {} })
    apply(pub('PHASE_STARTED', drawingStarted(4)))
    expect(useRoomStore.getState().needsResync).toBe(true)
  })

  it('routes errors of impostor commands to the impostor store', () => {
    impostor().trackCommand('chat-1', { type: 'IMPOSTOR_CHAT' })
    // RATE_LIMITED arrives unsequenced on /user/queue/errors.
    apply({
      type: 'ERROR',
      roomId: null,
      roomCode: 'DINO01',
      ts: '2026-10-22T10:00:30.000Z',
      seq: 0,
      stream: 'PRIVATE',
      actionId: 'chat-1',
      payload: { actionId: 'chat-1', code: 'RATE_LIMITED', message: 'slow', retryable: true },
    } as unknown as EventEnvelope)
    expect(impostor().chatError).toMatch(/moment/i)
    expect(useRoomStore.getState().lastError).toBeNull()

    impostor().trackCommand('s-1', {
      type: 'IMPOSTOR_DRAW_STROKE',
      strokeId: 'x',
      phaseId: drawingOtherTurnArtist.phaseId,
    })
    apply(
      priv(
        'GAME_ERROR',
        { actionId: 's-1', code: 'NOT_DRAWER', message: 'no', retryable: false },
        's-1',
      ),
    )
    expect(useRoomStore.getState().needsResync).toBe(true)
    expect(impostor().lockedTurnPhaseId).toBe(drawingOtherTurnArtist.phaseId)
  })

  it('resets on abort, on return to the lobby and when the room is left', () => {
    apply(pub('GAME_ABORTED', { sessionId: 'session-di-fixture', reason: 'INTERNAL_ERROR' }))
    expect(impostor().strokes).toEqual([])

    useRoomStore
      .getState()
      .applySnapshot(snapshotDto(drawingOtherTurnArtist as unknown as GameSnapshot))
    roomSeq = 40
    apply(pub('ROOM_STATUS_CHANGED', { status: 'WAITING', reason: 'REMATCH' }))
    expect(impostor().strokes).toEqual([])

    useRoomStore
      .getState()
      .applySnapshot(snapshotDto(drawingOtherTurnArtist as unknown as GameSnapshot))
    useRoomStore.getState().reset()
    expect(impostor().seatOrder).toEqual([])
  })
})
