import { beforeEach, describe, expect, it } from 'vitest'

import type { EventEnvelope, GameSnapshot, PlayerDto, RoomDto, SnapshotDto } from '@/api/types'
import { useDrawGuessStore } from '@/features/drawguess/drawGuessStore'
import { sampleDrawingSnapshotDrawer } from '@/features/drawguess/fixtures'

import { useGameSessionStore } from './gameSessionStore'
import { useRoomStore } from './roomStore'

// One client's view of a Draw & Guess round, fed envelope by envelope as the STOMP client would.

const player = (guestId: string, isHost = false): PlayerDto => ({
  guestId,
  nickname: guestId,
  avatarKey: 'rex',
  avatarColor: null,
  ready: false,
  connection: 'CONNECTED',
  isHost,
  score: 0,
})

const room: RoomDto = {
  id: 'room-1',
  code: 'ABCDEF',
  inviteUrl: 'http://localhost/join/ABCDEF',
  status: 'PLAYING',
  gameKey: 'draw-guess',
  hostGuestId: 'guest-1',
  minPlayers: 2,
  maxPlayers: 8,
  settings: {},
  players: [player('guest-1', true), player('guest-2'), player('guest-3')],
  sessionId: 'session-dg-01',
  createdAt: '2026-10-14T10:00:00.000Z',
}

const snapshot = (seqRoom: number, seqPrivate: number): SnapshotDto => ({
  room,
  serverNow: '2026-10-14T10:00:20.000Z',
  seq: { room: seqRoom, private: seqPrivate },
  // GameSnapshot is the generic header + Record; the module snapshot is a typed refinement of it.
  game: sampleDrawingSnapshotDrawer as unknown as GameSnapshot,
})

let roomSeq = 0
let privateSeq = 0
const pub = (type: string, payload: unknown): EventEnvelope => ({
  type,
  roomId: 'room-1',
  roomCode: 'ABCDEF',
  ts: '2026-10-14T10:00:30.000Z',
  seq: ++roomSeq,
  stream: 'ROOM',
  actionId: null,
  payload,
})
const priv = (type: string, payload: unknown, actionId: string | null = null): EventEnvelope => ({
  type,
  roomId: 'room-1',
  roomCode: 'ABCDEF',
  ts: '2026-10-14T10:00:30.000Z',
  seq: ++privateSeq,
  stream: 'PRIVATE',
  actionId,
  payload,
})

const apply = (envelope: EventEnvelope) => useRoomStore.getState().applyEnvelope(envelope)

describe('roomStore × Draw & Guess', () => {
  beforeEach(() => {
    useRoomStore.getState().reset()
    roomSeq = 10
    privateSeq = 4
    useRoomStore.getState().applySnapshot(snapshot(roomSeq, privateSeq))
  })

  it('restores the full canvas, word and phase from a reconnect snapshot', () => {
    expect(useGameSessionStore.getState().phase).toBe('DRAWING')
    expect(useGameSessionStore.getState().phaseId).toBe('phase-dr-01')
    expect(useDrawGuessStore.getState().strokes).toHaveLength(1)
    expect(useDrawGuessStore.getState().word).toBe('VOLCANO')
  })

  it('ignores a duplicated stroke envelope and resyncs on a gap', () => {
    const stroke = pub('DRAWING_UPDATED', {
      kind: 'STROKE',
      strokeId: 's-remote',
      batchNo: 0,
      tool: 'PENCIL',
      color: '#2B2B2B',
      size: 2,
      points: [[5, 5]],
      final: false,
    })
    expect(apply(stroke)).toBe('applied')
    expect(apply(stroke)).toBe('ignored')
    expect(useDrawGuessStore.getState().strokes).toHaveLength(2)

    roomSeq += 1 // one public event lost
    expect(apply(pub('HINT_UPDATED', { hint: 'v______' }))).toBe('gap')
    expect(useRoomStore.getState().needsResync).toBe(true)
    expect(useDrawGuessStore.getState().hint).toBe('_______')
  })

  it('turns a rejected stroke batch into a silent canvas resync, not an error banner', () => {
    useDrawGuessStore.getState().trackCommand('act-1', 'DRAW_STROKE', 's-local')
    apply(
      priv(
        'GAME_ERROR',
        { actionId: 'act-1', code: 'INVALID_ACTION', message: 'bad batch', retryable: false },
        'act-1',
      ),
    )
    expect(useRoomStore.getState().lastError).toBeNull()
    expect(useRoomStore.getState().needsResync).toBe(true)
  })

  it('handles canvas-full arriving on the private stream', () => {
    useDrawGuessStore.getState().trackCommand('act-2', 'DRAW_STROKE', 's-local')
    apply(
      priv(
        'GAME_ERROR',
        { actionId: 'act-2', code: 'PAYLOAD_TOO_LARGE', message: 'canvas full', retryable: false },
        'act-2',
      ),
    )
    expect(useDrawGuessStore.getState().canvasFull).toBe(true)
    expect(useRoomStore.getState().lastError).toBeNull()
  })

  it('still shows errors for untracked commands', () => {
    apply(
      priv('GAME_ERROR', {
        code: 'NOT_DRAWER',
        message: 'Only the drawer can draw',
        retryable: false,
      }),
    )
    expect(useRoomStore.getState().lastError).toBe('Only the drawer can draw')
  })

  it('syncs a correct guess, round end, results and rematch for every player', () => {
    apply(pub('GUESS_ACCEPTED', { guestId: 'guest-3', scoreDelta: 400, totalScore: 400, order: 1 }))
    expect(useDrawGuessStore.getState().guessedGuestIds).toEqual(['guest-2', 'guest-3'])
    expect(useGameSessionStore.getState().scores?.['guest-3']).toBe(400)

    apply(
      pub('ROUND_ENDED', {
        round: 1,
        drawerGuestId: 'guest-1',
        word: 'volcano',
        reason: 'ALL_GUESSED',
        scoreDeltas: { 'guest-1': 150, 'guest-2': 0, 'guest-3': 400 },
        scores: { 'guest-1': 150, 'guest-2': 500, 'guest-3': 400 },
      }),
    )
    apply(
      pub('PHASE_STARTED', {
        phase: 'ROUND_END',
        phaseId: 'phase-re-01',
        round: 1,
        totalRounds: 6,
        drawerGuestId: 'guest-1',
        deadlineAt: '2026-10-14T10:00:36.000Z',
      }),
    )
    expect(useDrawGuessStore.getState().word).toBe('volcano')
    expect(useGameSessionStore.getState().scores).toEqual({
      'guest-1': 150,
      'guest-2': 500,
      'guest-3': 400,
    })
    // The final canvas stays visible during ROUND_END.
    expect(useDrawGuessStore.getState().strokes).toHaveLength(1)

    apply(
      pub('GAME_FINISHED', {
        sessionId: 'session-dg-01',
        endReason: 'COMPLETED',
        standings: [{ rank: 1, guestId: 'guest-2', score: 500, detail: {} }],
        resultsReady: false,
      }),
    )
    apply(pub('ROOM_STATUS_CHANGED', { status: 'RESULTS', reason: 'GAME_FINISHED' }))
    expect(useRoomStore.getState().room?.status).toBe('RESULTS')
    expect(useGameSessionStore.getState().results?.standings[0].guestId).toBe('guest-2')

    apply(pub('REMATCH_VOTES_UPDATED', { votes: { 'guest-1': true, 'guest-2': true } }))
    // Full tally: guest-2 disconnected and dropped out.
    apply(pub('REMATCH_VOTES_UPDATED', { votes: { 'guest-1': true } }))
    expect(useGameSessionStore.getState().rematch?.votes).toEqual({ 'guest-1': true })

    apply(pub('ROOM_STATUS_CHANGED', { status: 'WAITING', reason: 'REMATCH' }))
    const after = useRoomStore.getState().room
    expect(after?.status).toBe('WAITING')
    expect(after?.sessionId).toBeNull()
    expect(after?.players.every((p) => p.score === null && !p.ready)).toBe(true)
    expect(useGameSessionStore.getState().phase).toBeNull()
    expect(useDrawGuessStore.getState().strokes).toHaveLength(0)
    expect(useDrawGuessStore.getState().word).toBeNull()
  })
})
