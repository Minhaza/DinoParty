import { beforeEach, describe, expect, it } from 'vitest'

import type {
  GameAbortedPayload,
  GameFinishedPayload,
  GameSnapshot,
  GameStartedPayload,
  PhaseStartedPayload,
  RoomDto,
  StandingsEntry,
} from '@/api/types'
import { resetServerClock } from '@/lib/serverClock'

import { useGameSessionStore } from './gameSessionStore'

describe('useGameSessionStore', () => {
  beforeEach(() => {
    resetServerClock()
    useGameSessionStore.getState().reset()
  })

  it('initializes with default empty state', () => {
    const state = useGameSessionStore.getState()
    expect(state.sessionId).toBeNull()
    expect(state.status).toBeNull()
    expect(state.phase).toBeNull()
    expect(state.phaseId).toBeNull()
    expect(state.deadlineAt).toBeNull()
    expect(state.scores).toBeNull()
    expect(state.players).toEqual([])
    expect(state.results).toBeNull()
    expect(state.rematch).toBeNull()
    expect(state.interruptedSession).toBeNull()
  })

  it('applies game snapshot wholesale', () => {
    const standings: StandingsEntry[] = [
      { rank: 1, guestId: 'g1', score: 100 },
      { rank: 2, guestId: 'g2', score: 50 },
    ]

    const snapshot: GameSnapshot = {
      sessionId: 'sess-123',
      gameKey: 'draw-guess',
      status: 'FINISHED',
      order: ['g1', 'g2'],
      phase: 'RESULTS',
      phaseId: 'phase-abc',
      phaseStartedAt: '2026-10-14T10:00:00.000Z',
      deadlineAt: '2026-10-14T10:01:00.000Z',
      scores: { g1: 100, g2: 50 },
      players: [
        { guestId: 'g1', status: 'ACTIVE' },
        { guestId: 'g2', status: 'DISCONNECTED' },
      ],
      results: { standings },
      rematch: { votes: { g1: true } },
    }

    useGameSessionStore.getState().applySnapshot(snapshot)

    const state = useGameSessionStore.getState()
    expect(state.sessionId).toBe('sess-123')
    expect(state.gameKey).toBe('draw-guess')
    expect(state.status).toBe('FINISHED')
    expect(state.order).toEqual(['g1', 'g2'])
    expect(state.phase).toBe('RESULTS')
    expect(state.phaseId).toBe('phase-abc')
    expect(state.deadlineAt).toBe('2026-10-14T10:01:00.000Z')
    expect(state.scores).toEqual({ g1: 100, g2: 50 })
    expect(state.players).toHaveLength(2)
    expect(state.results?.standings).toEqual(standings)
    expect(state.rematch?.votes).toEqual({ g1: true })
    expect(state.resultsReady).toBe(true)
  })

  it('detects server restart from room.lastSession in snapshot', () => {
    const room: RoomDto = {
      id: 'room-1',
      code: 'ABCD12',
      inviteUrl: 'http://localhost/join/ABCD12',
      status: 'WAITING',
      gameKey: 'draw-guess',
      hostGuestId: 'g1',
      minPlayers: 2,
      maxPlayers: 8,
      settings: {},
      players: [],
      sessionId: null,
      createdAt: '2026-10-14T09:00:00.000Z',
      lastSession: {
        sessionId: 'old-session',
        status: 'INTERRUPTED',
        endReason: 'SERVER_RESTART',
        finishedAt: '2026-10-14T09:50:00.000Z',
      },
    }

    useGameSessionStore.getState().applySnapshot(null, room)

    const state = useGameSessionStore.getState()
    expect(state.sessionId).toBeNull()
    expect(state.interruptedSession).toEqual(room.lastSession)

    state.clearInterruptedNotice()
    expect(useGameSessionStore.getState().interruptedSession).toBeNull()
  })

  it('handles GAME_STARTED lifecycle transition', () => {
    const payload: GameStartedPayload = {
      sessionId: 'sess-999',
      gameKey: 'draw-guess',
      settings: { rounds: 3 },
      order: ['g1', 'g2', 'g3'],
    }

    useGameSessionStore.getState().applyGameStarted(payload)

    const state = useGameSessionStore.getState()
    expect(state.sessionId).toBe('sess-999')
    expect(state.gameKey).toBe('draw-guess')
    expect(state.status).toBe('RUNNING')
    expect(state.order).toEqual(['g1', 'g2', 'g3'])
    expect(state.players).toEqual([
      { guestId: 'g1', status: 'ACTIVE' },
      { guestId: 'g2', status: 'ACTIVE' },
      { guestId: 'g3', status: 'ACTIVE' },
    ])
    expect(state.results).toBeNull()
  })

  it('handles PHASE_STARTED lifecycle transition', () => {
    const payload: PhaseStartedPayload = {
      phase: 'WORD_CHOICE',
      phaseId: 'phase-123',
      round: 1,
      deadlineAt: '2026-10-14T10:05:00.000Z',
    }

    useGameSessionStore.getState().applyPhaseStarted(payload, '2026-10-14T10:04:30.000Z')

    const state = useGameSessionStore.getState()
    expect(state.phase).toBe('WORD_CHOICE')
    expect(state.phaseId).toBe('phase-123')
    expect(state.deadlineAt).toBe('2026-10-14T10:05:00.000Z')
    expect(state.phaseStartedAt).toBe('2026-10-14T10:04:30.000Z')
  })

  it('handles GAME_FINISHED and REMATCH_VOTES_UPDATED lifecycle transitions', () => {
    const standings: StandingsEntry[] = [{ rank: 1, guestId: 'g1', score: 120 }]
    const finished: GameFinishedPayload = {
      sessionId: 'sess-123',
      endReason: 'COMPLETED',
      standings,
      resultsReady: true,
    }

    useGameSessionStore.getState().applyGameFinished(finished)

    let state = useGameSessionStore.getState()
    expect(state.status).toBe('FINISHED')
    expect(state.endReason).toBe('COMPLETED')
    expect(state.results?.standings).toEqual(standings)
    expect(state.resultsReady).toBe(true)

    useGameSessionStore.getState().applyRematchVotesUpdated({
      votes: { g1: true, g2: false },
    })

    state = useGameSessionStore.getState()
    expect(state.rematch?.votes).toEqual({ g1: true, g2: false })
  })

  it('handles GAME_ABORTED and room status reset to WAITING', () => {
    const aborted: GameAbortedPayload = {
      sessionId: 'sess-123',
      reason: 'INSUFFICIENT_PLAYERS',
    }

    useGameSessionStore.getState().applyGameAborted(aborted)

    let state = useGameSessionStore.getState()
    expect(state.status).toBeNull()
    expect(state.abortReason).toBe('INSUFFICIENT_PLAYERS')

    // Room resets to WAITING with REMATCH or RESULTS_TIMEOUT
    useGameSessionStore.getState().applyRoomStatusChanged({
      status: 'WAITING',
      reason: 'REMATCH',
    })

    state = useGameSessionStore.getState()
    expect(state.sessionId).toBeNull()
    expect(state.abortReason).toBeNull()
  })

  it('tracks player connection and leave status in session roster', () => {
    useGameSessionStore.getState().applyGameStarted({
      sessionId: 'sess-1',
      gameKey: 'telephone',
      settings: {},
      order: ['p1', 'p2'],
    })

    useGameSessionStore.getState().applyPlayerStatusChanged('p2', 'DISCONNECTED')
    expect(useGameSessionStore.getState().players).toEqual([
      { guestId: 'p1', status: 'ACTIVE' },
      { guestId: 'p2', status: 'DISCONNECTED' },
    ])

    useGameSessionStore.getState().applyPlayerStatusChanged('p2', 'ACTIVE')
    expect(useGameSessionStore.getState().players).toEqual([
      { guestId: 'p1', status: 'ACTIVE' },
      { guestId: 'p2', status: 'ACTIVE' },
    ])

    useGameSessionStore.getState().applyPlayerStatusChanged('p1', 'LEFT')
    expect(useGameSessionStore.getState().players[0].status).toBe('LEFT')
  })
})
