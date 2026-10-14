import { create } from 'zustand'

import type {
  GameAbortedPayload,
  GameFinishedPayload,
  GameKey,
  GameSnapshot,
  GameStartedPayload,
  LastSessionDto,
  PhaseStartedPayload,
  RematchVotesUpdatedPayload,
  RosterPlayerStatus,
  RoomDto,
  RoomStatusChangedPayload,
  SessionRosterPlayer,
  StandingsEntry,
} from '@/api/types'
import { updateServerClockOffset } from '@/lib/serverClock'

export type SessionStatus = 'RUNNING' | 'FINISHED' | null

export interface GameSessionState {
  sessionId: string | null
  gameKey: GameKey | string | null
  status: SessionStatus
  order: string[]
  phase: string | null
  phaseId: string | null
  phaseStartedAt: string | null
  deadlineAt: string | null
  scores: Record<string, number> | null
  players: SessionRosterPlayer[]
  results: { standings: StandingsEntry[] } | null
  rematch: { votes: Record<string, boolean> } | null
  startsAt: string | null
  endReason: string | null
  abortReason: string | null
  resultsReady: boolean
  interruptedSession: LastSessionDto | null

  // Actions
  applySnapshot: (snapshotGame: GameSnapshot | null, room?: RoomDto | null) => void
  applyGameStarted: (payload: GameStartedPayload) => void
  applyPhaseStarted: (payload: PhaseStartedPayload, envelopeTs?: string) => void
  applyGameFinished: (payload: GameFinishedPayload) => void
  applyGameAborted: (payload: GameAbortedPayload) => void
  applyRematchVotesUpdated: (payload: RematchVotesUpdatedPayload) => void
  applyRoomStatusChanged: (payload: RoomStatusChangedPayload) => void
  applyPlayerStatusChanged: (guestId: string, status: RosterPlayerStatus) => void
  clearInterruptedNotice: () => void
  reset: () => void
}

const initialState = {
  sessionId: null,
  gameKey: null,
  status: null as SessionStatus,
  order: [],
  phase: null,
  phaseId: null,
  phaseStartedAt: null,
  deadlineAt: null,
  scores: null,
  players: [],
  results: null,
  rematch: null,
  startsAt: null,
  endReason: null,
  abortReason: null,
  resultsReady: false,
  interruptedSession: null,
}

export const useGameSessionStore = create<GameSessionState>((set) => ({
  ...initialState,

  applySnapshot: (gameSnapshot, room) => {
    // Check if server restart caused an interruption
    let interrupted: LastSessionDto | null = null
    if (
      room?.status === 'WAITING' &&
      room.lastSession?.status === 'INTERRUPTED' &&
      room.lastSession.endReason === 'SERVER_RESTART'
    ) {
      interrupted = room.lastSession
    }

    if (!gameSnapshot) {
      set({
        ...initialState,
        interruptedSession: interrupted,
      })
      return
    }

    set({
      sessionId: gameSnapshot.sessionId,
      gameKey: gameSnapshot.gameKey,
      status: gameSnapshot.status,
      order: gameSnapshot.order || [],
      phase: gameSnapshot.phase,
      phaseId: gameSnapshot.phaseId,
      phaseStartedAt: gameSnapshot.phaseStartedAt,
      deadlineAt: gameSnapshot.deadlineAt,
      scores: gameSnapshot.scores,
      players: gameSnapshot.players || [],
      results: gameSnapshot.results,
      rematch: gameSnapshot.rematch,
      startsAt: null,
      endReason: null,
      abortReason: null,
      resultsReady: gameSnapshot.status === 'FINISHED' && !!gameSnapshot.results,
      interruptedSession: null, // Clear interrupted notice once an active session snapshot arrives
    })
  },

  applyGameStarted: (payload) => {
    set({
      sessionId: payload.sessionId,
      gameKey: payload.gameKey,
      status: 'RUNNING',
      order: payload.order,
      phase: null,
      phaseId: null,
      phaseStartedAt: null,
      deadlineAt: null,
      scores: null,
      players: payload.order.map((guestId) => ({ guestId, status: 'ACTIVE' })),
      results: null,
      rematch: null,
      startsAt: null,
      endReason: null,
      abortReason: null,
      resultsReady: false,
      interruptedSession: null,
    })
  },

  applyPhaseStarted: (payload, envelopeTs) => {
    if (envelopeTs) {
      updateServerClockOffset(envelopeTs)
    }
    set({
      phase: payload.phase,
      phaseId: payload.phaseId,
      deadlineAt: payload.deadlineAt,
      phaseStartedAt: envelopeTs ?? new Date().toISOString(),
    })
  },

  applyGameFinished: (payload) => {
    set({
      status: 'FINISHED',
      endReason: payload.endReason,
      results: { standings: payload.standings },
      resultsReady: payload.resultsReady,
      rematch: { votes: {} },
    })
  },

  applyGameAborted: (payload) => {
    set({
      status: null,
      abortReason: payload.reason,
      startsAt: null,
    })
  },

  applyRematchVotesUpdated: (payload) => {
    set((state) => ({
      rematch: {
        votes: {
          ...(state.rematch?.votes ?? {}),
          ...payload.votes,
        },
      },
    }))
  },

  applyRoomStatusChanged: (payload) => {
    if (payload.status === 'STARTING') {
      set({
        startsAt: payload.startsAt ?? null,
        abortReason: null,
      })
      return
    }

    if (payload.status === 'WAITING') {
      const resetReasons = [
        'REMATCH',
        'RESULTS_TIMEOUT',
        'GAME_ABORTED',
        'SERVER_RESTART',
        'START_CANCELLED',
      ]
      if (payload.reason && resetReasons.includes(payload.reason)) {
        set({
          sessionId: null,
          status: null,
          phase: null,
          phaseId: null,
          deadlineAt: null,
          startsAt: null,
          results: null,
          rematch: null,
          scores: null,
          abortReason: payload.reason === 'GAME_ABORTED' ? (payload.reason as string) : null,
        })
      }
    }
  },

  applyPlayerStatusChanged: (guestId, status) => {
    set((state) => {
      const exists = state.players.some((p) => p.guestId === guestId)
      if (!exists) return state

      return {
        players: state.players.map((p) => (p.guestId === guestId ? { ...p, status } : p)),
      }
    })
  },

  clearInterruptedNotice: () => set({ interruptedSession: null }),

  reset: () => set(initialState),
}))
