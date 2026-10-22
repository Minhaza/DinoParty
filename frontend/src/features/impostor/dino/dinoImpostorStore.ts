import { create } from 'zustand'

import type { GameErrorPayload } from '@/api/types'
import { isBudgetExhausted, type StrokeRejection } from '@/features/drawing/types'

import {
  commonAck,
  commonError,
  commonForNewRound,
  commonFromSnapshot,
  initialImpostorCommon,
  withChatMessage,
  withGuessOpen,
  withRole,
  withRoundEnded,
  withVoteClosed,
  withVoteProgress,
  withVoteSent,
  withVoteStarted,
  type CommandErrorOutcome,
  type ImpostorCommonState,
  type TrackedImpostorCommand,
} from '../common/impostorState'
import type {
  DinoImpostorPhaseStartedPayload,
  DinoImpostorSnapshot,
  ImpostorChatMessagePayload,
  ImpostorDrawingUpdatedPayload,
  ImpostorGuessOpenPayload,
  ImpostorRolePayload,
  ImpostorRoundEndedPayload,
  ImpostorStroke,
  ImpostorVoteClosedPayload,
  ImpostorVoteProgressPayload,
} from '../common/types'

export type { CommandErrorOutcome }

/** Bound for the in-flight command table (the server remembers 256 actionIds per guest). */
const MAX_TRACKED_COMMANDS = 256

// Not React state: only consulted when an ACK/error comes back, never rendered.
const trackedCommands = new Map<string, TrackedImpostorCommand>()

interface DinoImpostorData extends ImpostorCommonState {
  round: number
  totalRounds: number
  laps: number
  lap: number | null
  turn: number | null
  totalTurns: number
  seatOrder: string[]
  colors: Record<string, string>
  drawerGuestId: string | null
  /** The shared canvas in drawing order; points live here, never in React state. */
  strokes: ImpostorStroke[]
  canvasFull: boolean
  /** Strokes this client drew; their public echoes are not re-applied. */
  localStrokeIds: string[]
  lastProcessedBatch: Record<string, number>
  /** Turn in which the server refused another stroke of ours (`ALREADY_SUBMITTED`/`NOT_DRAWER`). */
  lockedTurnPhaseId: string | null
  strokeRejection: StrokeRejection | null
}

export interface DinoImpostorState extends DinoImpostorData {
  applySnapshot: (snapshot: DinoImpostorSnapshot | null) => void
  /** Returns `true` when the state cannot be trusted without a snapshot (colours unknown, G1). */
  applyPhaseStarted: (payload: DinoImpostorPhaseStartedPayload) => boolean
  applyRole: (payload: ImpostorRolePayload) => void
  applyDrawingUpdated: (payload: ImpostorDrawingUpdatedPayload) => void
  applyChatMessage: (payload: ImpostorChatMessagePayload) => void
  applyVoteProgress: (payload: ImpostorVoteProgressPayload) => void
  applyVoteClosed: (payload: ImpostorVoteClosedPayload) => void
  applyGuessOpen: (payload: ImpostorGuessOpenPayload) => void
  applyRoundEnded: (payload: ImpostorRoundEndedPayload) => void
  trackCommand: (actionId: string, command: TrackedImpostorCommand) => void
  applyActionAck: (actionId: string | null | undefined) => void
  applyCommandError: (payload: GameErrorPayload, actionId?: string | null) => CommandErrorOutcome
  addLocalStroke: (stroke: ImpostorStroke) => void
  appendLocalPoints: (strokeId: string, points: [number, number][], isFinal: boolean) => void
  reset: () => void
}

const initialData: DinoImpostorData = {
  ...initialImpostorCommon,
  round: 1,
  totalRounds: 1,
  laps: 1,
  lap: null,
  turn: null,
  totalTurns: 0,
  seatOrder: [],
  colors: {},
  drawerGuestId: null,
  strokes: [],
  canvasFull: false,
  localStrokeIds: [],
  lastProcessedBatch: {},
  lockedTurnPhaseId: null,
  strokeRejection: null,
}

let rejectionNonce = 0

export const useDinoImpostorStore = create<DinoImpostorState>((set, get) => ({
  ...initialData,

  applySnapshot: (snapshot) => {
    if (!snapshot) {
      trackedCommands.clear()
      set(initialData)
      return
    }
    const state = get()
    set({
      ...initialData,
      ...commonFromSnapshot(snapshot),
      round: snapshot.round,
      totalRounds: snapshot.totalRounds,
      laps: snapshot.laps,
      lap: snapshot.lap ?? null,
      turn: snapshot.turn ?? null,
      totalTurns: snapshot.totalTurns,
      seatOrder: [...(snapshot.seatOrder ?? [])],
      colors: { ...(snapshot.colors ?? {}) },
      drawerGuestId: snapshot.drawerGuestId ?? null,
      strokes: (snapshot.strokes ?? []).map((s) => ({ ...s, points: [...s.points] })),
      canvasFull: snapshot.canvasBudget ? isBudgetExhausted(snapshot.canvasBudget) : false,
      // Keep our own stroke ids: later echoes of a stroke still being drawn stay suppressed.
      localStrokeIds: state.localStrokeIds,
      lockedTurnPhaseId:
        state.lockedTurnPhaseId === snapshot.phaseId ? state.lockedTurnPhaseId : null,
    })
  },

  applyPhaseStarted: (payload) => {
    const state = get()
    const common = { round: payload.round, totalRounds: payload.totalRounds }
    switch (payload.phase) {
      case 'ROLE_REVEAL':
        set({
          ...commonForNewRound(state, payload.phaseId),
          ...common,
          lap: null,
          turn: null,
          drawerGuestId: null,
          strokes: [],
          canvasFull: false,
          localStrokeIds: [],
          lastProcessedBatch: {},
          lockedTurnPhaseId: null,
          strokeRejection: null,
        })
        return false
      case 'DRAWING':
        set({
          ...common,
          lap: payload.lap,
          laps: payload.laps,
          turn: payload.turn,
          totalTurns: payload.totalTurns,
          drawerGuestId: payload.drawerGuestId,
          seatOrder: [...payload.seatOrder],
        })
        // Colours only travel in snapshots (contract gap G1): without them nobody can draw.
        return Object.keys(state.colors).length === 0
      case 'VOTE':
        set({ ...withVoteStarted(state), ...common, lap: null, turn: null, drawerGuestId: null })
        return false
      case 'GUESS':
      case 'ROUND_END':
        set({ ...common, lap: null, turn: null, drawerGuestId: null })
        return false
    }
  },

  applyRole: (payload) => set(withRole(get(), payload)),

  applyDrawingUpdated: (payload) => {
    const state = get()
    const index = state.strokes.findIndex((s) => s.strokeId === payload.strokeId)
    const existing = index >= 0 ? state.strokes[index] : null

    // Our own echo: the points are on screen from the local preview, only `final` is news.
    if (existing && state.localStrokeIds.includes(payload.strokeId)) {
      if (payload.final && !existing.closed) {
        set({
          strokes: state.strokes.map((s, i) => (i === index ? { ...s, closed: true } : s)),
        })
      }
      return
    }

    // Defensive deduplication (the room `seq` already drops repeated envelopes).
    if (existing?.closed) return
    const lastBatch = state.lastProcessedBatch[payload.strokeId]
    if (lastBatch !== undefined && payload.batchNo <= lastBatch) return

    const strokes: ImpostorStroke[] = existing
      ? state.strokes.map((s, i) =>
          i === index
            ? { ...s, points: [...s.points, ...payload.points], closed: payload.final }
            : s,
        )
      : [
          ...state.strokes,
          {
            strokeId: payload.strokeId,
            tool: payload.tool,
            color: payload.color,
            size: payload.size,
            points: [...payload.points],
            closed: payload.final,
            authorGuestId: payload.authorGuestId,
          },
        ]
    set({
      strokes,
      lastProcessedBatch: { ...state.lastProcessedBatch, [payload.strokeId]: payload.batchNo },
    })
  },

  applyChatMessage: (payload) => set(withChatMessage(get(), payload)),
  applyVoteProgress: (payload) => set(withVoteProgress(get(), payload)),
  applyVoteClosed: (payload) => set(withVoteClosed(get(), payload)),
  applyGuessOpen: (payload) => set(withGuessOpen(get(), payload)),
  applyRoundEnded: (payload) => set(withRoundEnded(get(), payload)),

  trackCommand: (actionId, command) => {
    if (trackedCommands.size >= MAX_TRACKED_COMMANDS) {
      const oldest = trackedCommands.keys().next().value
      if (oldest !== undefined) trackedCommands.delete(oldest)
    }
    trackedCommands.set(actionId, command)
    if (command.type === 'IMPOSTOR_VOTE') set(withVoteSent(get(), command.target ?? null))
    else if (command.type === 'IMPOSTOR_GUESS') set({ guessError: null })
  },

  applyActionAck: (actionId) => {
    if (!actionId) return
    const command = trackedCommands.get(actionId)
    if (!command) return
    trackedCommands.delete(actionId)
    const patch = commonAck(get(), command)
    if (patch) set(patch)
  },

  applyCommandError: (payload, envelopeActionId) => {
    const actionId = payload.actionId ?? envelopeActionId
    if (!actionId) return 'unhandled'
    const command = trackedCommands.get(actionId)
    if (!command) return 'unhandled'
    trackedCommands.delete(actionId)

    if (command.type === 'IMPOSTOR_DRAW_STROKE') {
      switch (payload.code) {
        // Lost a race with the end of the turn: the next PHASE_STARTED already says what happened.
        case 'STALE_ACTION':
        case 'INVALID_PHASE':
        case 'DUPLICATE_ACTION':
          return 'handled'
        case 'NOT_DRAWER':
        case 'ALREADY_SUBMITTED':
          set({ lockedTurnPhaseId: command.phaseId ?? null })
          return 'resync'
        case 'PAYLOAD_TOO_LARGE':
          rejectionNonce += 1
          set({
            canvasFull: true,
            strokeRejection: command.strokeId
              ? { strokeId: command.strokeId, kind: 'FULL', nonce: rejectionNonce }
              : get().strokeRejection,
          })
          return 'resync'
        default:
          // VALIDATION_FAILED, INVALID_ACTION, RATE_LIMITED: the batch was dropped; resync the canvas.
          if (command.strokeId) {
            rejectionNonce += 1
            set({
              strokeRejection: {
                strokeId: command.strokeId,
                kind: 'RESTART',
                nonce: rejectionNonce,
              },
            })
          }
          return 'resync'
      }
    }

    const handled = commonError(get(), command, payload)
    if (!handled) return 'unhandled'
    set(handled.patch)
    return handled.outcome
  },

  addLocalStroke: (stroke) => {
    set((state) => ({
      strokes: [...state.strokes, stroke],
      localStrokeIds: [...state.localStrokeIds, stroke.strokeId],
    }))
  },

  appendLocalPoints: (strokeId, points, isFinal) => {
    set((state) => ({
      strokes: state.strokes.map((s) =>
        s.strokeId === strokeId
          ? {
              ...s,
              points: points.length > 0 ? [...s.points, ...points] : s.points,
              closed: isFinal ? true : s.closed,
            }
          : s,
      ),
    }))
  },

  reset: () => {
    trackedCommands.clear()
    set(initialData)
  },
}))
