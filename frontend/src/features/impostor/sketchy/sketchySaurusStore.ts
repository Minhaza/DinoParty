import { create } from 'zustand'

import type { GameErrorPayload } from '@/api/types'
import { isBudgetExhausted, type Stroke, type StrokeRejection } from '@/features/drawing/types'

import { createCommandTracker } from '../common/commandTracker'
import type {
  CommandErrorOutcome,
  ImpostorCommonState,
  TrackedImpostorCommand,
} from '../common/impostorState'
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
} from '../common/impostorState'
import type {
  ImpostorChatMessagePayload,
  ImpostorGuessOpenPayload,
  ImpostorRolePayload,
  ImpostorRoundEndedPayload,
  ImpostorVoteClosedPayload,
  ImpostorVoteProgressPayload,
} from '../common/types'
import type {
  SketchyDrawing,
  SketchyGalleryOpenedPayload,
  SketchyProgressPayload,
  SketchySaurusPhaseStartedPayload,
  SketchySaurusSnapshot,
} from './types'

export type { CommandErrorOutcome }

const commandTracker = createCommandTracker()

interface SketchySaurusData extends ImpostorCommonState {
  round: number
  totalRounds: number
  nicknames: Record<string, string>
  /** The player's own private draft in SKETCH */
  strokes: Stroke[]
  canvasFull: boolean
  strokeRejection: StrokeRejection | null
  submitted: boolean
  submittedGuestIds: string[]
  gallery: SketchyDrawing[] | null
  submitError: string | null
}

export interface SketchySaurusState extends SketchySaurusData {
  applySnapshot: (snapshot: SketchySaurusSnapshot | null) => void
  applyPhaseStarted: (payload: SketchySaurusPhaseStartedPayload) => boolean
  applyRole: (payload: ImpostorRolePayload) => void
  applyProgress: (payload: SketchyProgressPayload) => void
  applyGalleryOpened: (payload: SketchyGalleryOpenedPayload) => void
  applyChatMessage: (payload: ImpostorChatMessagePayload) => void
  applyVoteProgress: (payload: ImpostorVoteProgressPayload) => void
  applyVoteClosed: (payload: ImpostorVoteClosedPayload) => void
  applyGuessOpen: (payload: ImpostorGuessOpenPayload) => void
  applyRoundEnded: (payload: ImpostorRoundEndedPayload) => void
  trackCommand: (actionId: string, command: TrackedImpostorCommand) => void
  applyActionAck: (actionId: string | null | undefined) => void
  applyCommandError: (payload: GameErrorPayload, actionId?: string | null) => CommandErrorOutcome
  addLocalStroke: (stroke: Stroke) => void
  appendLocalPoints: (strokeId: string, points: [number, number][], isFinal: boolean) => void
  undoLocalStroke: () => void
  clearLocalStrokes: () => void
  reset: () => void
}

const initialData: SketchySaurusData = {
  ...initialImpostorCommon,
  round: 1,
  totalRounds: 1,
  nicknames: {},
  strokes: [],
  canvasFull: false,
  strokeRejection: null,
  submitted: false,
  submittedGuestIds: [],
  gallery: null,
  submitError: null,
}

let rejectionNonce = 0

export const useSketchySaurusStore = create<SketchySaurusState>((set, get) => ({
  ...initialData,

  applySnapshot: (snapshot) => {
    if (!snapshot) {
      commandTracker.clear()
      set(initialData)
      return
    }
    const state = get()
    set({
      ...initialData,
      ...commonFromSnapshot(snapshot),
      round: snapshot.round,
      totalRounds: snapshot.totalRounds,
      nicknames: { ...state.nicknames, ...(snapshot.nicknames ?? {}) },
      strokes: (snapshot.draft?.strokes ?? []).map((s) => ({ ...s, points: [...s.points] })),
      canvasFull: snapshot.draft?.canvasBudget
        ? isBudgetExhausted(snapshot.draft.canvasBudget)
        : false,
      submitted: snapshot.submitted ?? false,
      submittedGuestIds: [...(snapshot.submittedGuestIds ?? [])],
      gallery: snapshot.gallery
        ? snapshot.gallery.map((d) => ({ ...d, strokes: [...d.strokes] }))
        : null,
      strokeRejection: null,
      submitError: null,
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
          nicknames:
            payload.nicknames && Object.keys(payload.nicknames).length > 0
              ? { ...state.nicknames, ...payload.nicknames }
              : state.nicknames,
          strokes: [],
          canvasFull: false,
          strokeRejection: null,
          submitted: false,
          submittedGuestIds: [],
          gallery: null,
          submitError: null,
        })
        return false
      case 'SKETCH':
        set({
          ...common,
          submitted: false,
          submittedGuestIds: [],
          gallery: null,
          submitError: null,
          strokeRejection: null,
        })
        return false
      case 'GALLERY':
        set({
          ...withVoteStarted(state),
          ...common,
          submitError: null,
        })
        return false
      case 'GUESS':
        set({
          ...common,
          voteError: null,
        })
        return false
      case 'ROUND_END':
        set({
          ...common,
          voteError: null,
        })
        return false
    }
  },

  applyRole: (payload) => set((s) => withRole(s, payload)),

  applyProgress: (payload) => set({ submittedGuestIds: [...payload.submittedGuestIds] }),

  applyGalleryOpened: (payload) =>
    set({
      round: payload.round,
      gallery: payload.drawings.map((d) => ({ ...d, strokes: [...d.strokes] })),
    }),

  applyChatMessage: (payload) => set(withChatMessage(get(), payload)),
  applyVoteProgress: (payload) => set(withVoteProgress(get(), payload)),
  applyVoteClosed: (payload) => set(withVoteClosed(get(), payload)),
  applyGuessOpen: (payload) => set(withGuessOpen(get(), payload)),
  applyRoundEnded: (payload) => set(withRoundEnded(get(), payload)),

  trackCommand: (actionId, command) => {
    commandTracker.track(actionId, command)
    if (command.type === 'IMPOSTOR_VOTE') set(withVoteSent(get(), command.target ?? null))
    else if (command.type === 'IMPOSTOR_GUESS') set({ guessError: null })
    else if (command.type === 'IMPOSTOR_CHAT') set({ chatError: null })
    else if (command.type === 'SKETCHY_SUBMIT') set({ submitError: null })
  },

  applyActionAck: (actionId) => {
    if (!actionId) return
    const command = commandTracker.take(actionId)
    if (!command) return
    if (command.type === 'SKETCHY_SUBMIT') {
      set({ submitted: true, submitError: null })
      return
    }
    const patch = commonAck(get(), command)
    if (patch) set(patch)
  },

  applyCommandError: (payload, envelopeActionId) => {
    const actionId = payload.actionId ?? envelopeActionId
    if (!actionId) return 'unhandled'
    const command = commandTracker.take(actionId)
    if (!command) return 'unhandled'

    if (command.type === 'SKETCHY_DRAW_STROKE') {
      switch (payload.code) {
        case 'STALE_ACTION':
        case 'INVALID_PHASE':
        case 'DUPLICATE_ACTION':
          return 'handled'
        case 'ALREADY_SUBMITTED':
          set({ submitted: true })
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

    if (command.type === 'SKETCHY_SUBMIT') {
      switch (payload.code) {
        case 'ALREADY_SUBMITTED':
          set({ submitted: true })
          return 'handled'
        case 'STALE_ACTION':
        case 'INVALID_PHASE':
          return 'handled'
        default:
          set({ submitError: payload.message })
          return 'handled'
      }
    }

    if (command.type === 'SKETCHY_DRAW_UNDO' || command.type === 'SKETCHY_DRAW_CLEAR') {
      switch (payload.code) {
        case 'ALREADY_SUBMITTED':
          set({ submitted: true })
          return 'handled'
        case 'STALE_ACTION':
        case 'INVALID_PHASE':
          return 'handled'
        default:
          return 'resync'
      }
    }

    const handled = commonError(get(), command, payload)
    if (!handled) return 'unhandled'
    set(handled.patch)
    return handled.outcome
  },

  addLocalStroke: (stroke) => {
    set((state) => ({ strokes: [...state.strokes, stroke] }))
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

  undoLocalStroke: () => {
    // Undo frees budget, so a full canvas becomes drawable again (as Telephone's draft).
    set((state) => ({ strokes: state.strokes.slice(0, -1), canvasFull: false }))
  },

  clearLocalStrokes: () => {
    set({ strokes: [], canvasFull: false })
  },

  reset: () => {
    commandTracker.clear()
    set(initialData)
  },
}))
