import { create } from 'zustand'

import type { GameErrorPayload } from '@/api/types'
import type { StrokeRejection } from '@/features/drawing/types'

import type {
  DrawGuessChatMessage,
  DrawGuessPhaseStartedPayload,
  DrawGuessSnapshot,
  DrawingUpdatedPayload,
  DrawerWordPayload,
  GuessAcceptedPayload,
  GuessChatPayload,
  GuesserChatPayload,
  GuessFeedbackPayload,
  GuessFeedbackResult,
  HintUpdatedPayload,
  RoundEndedPayload,
  RoundEndInfo,
  Stroke,
  WordChoicesPayload,
} from './types'

/** Largest `DRAW_STROKE` batch (api-contracts §3.9); a snapshot this close to the budget stays "full". */
const MAX_POINTS_PER_BATCH = 200
/** Server keeps ≤ 30 chat lines in snapshots; the live feed keeps a few more. */
const MAX_CHAT_LINES = 50
/** Bound for the in-flight command table (the server remembers 256 actionIds per guest). */
const MAX_TRACKED_COMMANDS = 256

export type { StrokeRejection }

/** What the caller must do after a command error: nothing more, show it, or resync the canvas. */
export type CommandErrorOutcome = 'unhandled' | 'handled' | 'resync'

interface TrackedCommand {
  type: string
  strokeId?: string
}

// Not React state: only consulted when an ACK/error comes back, never rendered.
const trackedCommands = new Map<string, TrackedCommand>()

export interface DrawGuessState {
  round: number
  totalRounds: number
  drawerGuestId: string | null
  hint: string | null
  word: string | null
  wordChoices: [string, string, string] | null
  wordChoicesPhaseId: string | null
  strokes: Stroke[]
  guessedGuestIds: string[]
  chat: DrawGuessChatMessage[]
  roundEnd: RoundEndInfo | null
  feedback: GuessFeedbackResult | null
  /** Strokes this client drew; their public `DRAWING_UPDATED` echoes are not re-applied. */
  localStrokeIds: string[]
  lastProcessedBatch: Record<string, number>
  canvasFull: boolean
  strokeRejection: StrokeRejection | null

  // Actions
  applySnapshot: (snapshot: DrawGuessSnapshot | null) => void
  applyPhaseStarted: (payload: DrawGuessPhaseStartedPayload) => void
  applyDrawingUpdated: (payload: DrawingUpdatedPayload) => void
  applyHintUpdated: (payload: HintUpdatedPayload) => void
  applyGuessAccepted: (payload: GuessAcceptedPayload) => void
  applyGuessChat: (payload: GuessChatPayload, ts?: string) => void
  applyGuesserChat: (payload: GuesserChatPayload, ts?: string) => void
  applyRoundEnded: (payload: RoundEndedPayload) => void
  applyWordChoices: (payload: WordChoicesPayload) => void
  applyDrawerWord: (payload: DrawerWordPayload) => void
  applyGuessFeedback: (payload: GuessFeedbackPayload) => void
  trackCommand: (actionId: string, type: string, strokeId?: string) => void
  applyActionAck: (actionId: string | null | undefined) => void
  applyCommandError: (payload: GameErrorPayload, actionId?: string | null) => CommandErrorOutcome
  addLocalStroke: (stroke: Stroke) => void
  appendLocalPoints: (strokeId: string, points: [number, number][], isFinal: boolean) => void
  clearFeedback: () => void
  reset: () => void
}

const initialState = {
  round: 1,
  totalRounds: 1,
  drawerGuestId: null,
  hint: null,
  word: null,
  wordChoices: null,
  wordChoicesPhaseId: null,
  strokes: [] as Stroke[],
  guessedGuestIds: [] as string[],
  chat: [] as DrawGuessChatMessage[],
  roundEnd: null,
  feedback: null,
  localStrokeIds: [] as string[],
  lastProcessedBatch: {} as Record<string, number>,
  canvasFull: false,
  strokeRejection: null,
}

let rejectionNonce = 0

export const useDrawGuessStore = create<DrawGuessState>((set, get) => ({
  ...initialState,

  applySnapshot: (snapshot) => {
    if (!snapshot) {
      trackedCommands.clear()
      set(initialState)
      return
    }

    const state = get()
    const strokes = snapshot.strokes ? [...snapshot.strokes] : []
    const budget = snapshot.canvasBudget
    // A resync after "canvas full" must not re-enable drawing while the server still has no room.
    const stillFull =
      state.canvasFull &&
      budget !== undefined &&
      (budget.points > budget.maxPoints - MAX_POINTS_PER_BATCH ||
        budget.strokes >= budget.maxStrokes)

    set({
      round: snapshot.round ?? 1,
      totalRounds: snapshot.totalRounds ?? 1,
      drawerGuestId: snapshot.drawerGuestId ?? null,
      hint: snapshot.hint ?? null,
      word: snapshot.word ?? null,
      wordChoices: snapshot.wordChoices ?? null,
      wordChoicesPhaseId: snapshot.wordChoices ? snapshot.phaseId : null,
      strokes,
      guessedGuestIds: snapshot.guessedGuestIds ? [...snapshot.guessedGuestIds] : [],
      chat: snapshot.recentChat ? [...snapshot.recentChat] : [],
      roundEnd: snapshot.roundEnd ?? null,
      feedback: null,
      // Keep our own stroke ids: the drawer may still be drawing one of them, and its later
      // echoes must stay suppressed. Batch numbers of snapshot strokes are unknown; the stream
      // `seq` already guarantees no batch is applied twice after a snapshot.
      localStrokeIds: state.localStrokeIds,
      lastProcessedBatch: {},
      canvasFull: stillFull,
    })
  },

  applyPhaseStarted: (payload) => {
    set((state) => {
      const isNewRound = payload.phase === 'WORD_CHOICE'
      // WORD_CHOICES is private and may be processed before the public PHASE_STARTED of its phase.
      const keepChoices = state.wordChoicesPhaseId === payload.phaseId
      return {
        round: payload.round ?? state.round,
        totalRounds: payload.totalRounds ?? state.totalRounds,
        drawerGuestId: payload.drawerGuestId ?? state.drawerGuestId,
        hint: payload.hint !== undefined ? payload.hint : isNewRound ? null : state.hint,
        word: isNewRound ? null : state.word,
        wordChoices: keepChoices ? state.wordChoices : null,
        wordChoicesPhaseId: keepChoices ? state.wordChoicesPhaseId : null,
        // Reset canvas and per-round state on new round (WORD_CHOICE)
        strokes: isNewRound ? [] : state.strokes,
        guessedGuestIds: isNewRound ? [] : state.guessedGuestIds,
        chat: isNewRound ? [] : state.chat,
        roundEnd: isNewRound ? null : state.roundEnd,
        feedback: null,
        localStrokeIds: isNewRound ? [] : state.localStrokeIds,
        lastProcessedBatch: isNewRound ? {} : state.lastProcessedBatch,
        canvasFull: isNewRound ? false : state.canvasFull,
      }
    })
  },

  applyDrawingUpdated: (payload) => {
    const state = get()

    if (payload.kind === 'CLEAR') {
      set({
        strokes: [],
        localStrokeIds: [],
        lastProcessedBatch: {},
        canvasFull: false,
      })
      return
    }

    if (payload.kind === 'UNDO') {
      const remaining = state.strokes.filter((s) => s.strokeId !== payload.strokeId)
      const nextProcessed = { ...state.lastProcessedBatch }
      delete nextProcessed[payload.strokeId]
      set({
        strokes: remaining,
        localStrokeIds: state.localStrokeIds.filter((id) => id !== payload.strokeId),
        lastProcessedBatch: nextProcessed,
        canvasFull: false,
      })
      return
    }

    if (payload.kind === 'STROKE') {
      const existingIndex = state.strokes.findIndex((s) => s.strokeId === payload.strokeId)
      const existing = existingIndex >= 0 ? state.strokes[existingIndex] : null

      // Echo of our own stroke (contract §3.9: recognized by strokeId): the points are already
      // on screen from the local preview, only the closed flag is news. If a snapshot replaced
      // the canvas after we started the stroke, it is missing and is rebuilt from the echoes.
      if (existing && state.localStrokeIds.includes(payload.strokeId)) {
        if (payload.final && !existing.closed) {
          set({
            strokes: state.strokes.map((s, idx) =>
              idx === existingIndex ? { ...s, closed: true } : s,
            ),
          })
        }
        return
      }

      // Defensive deduplication (the room `seq` already drops repeated envelopes).
      if (existing?.closed) return
      const lastBatch = state.lastProcessedBatch[payload.strokeId]
      if (lastBatch !== undefined && payload.batchNo <= lastBatch) return

      const nextStrokes: Stroke[] = existing
        ? state.strokes.map((s, idx) =>
            idx === existingIndex
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
            },
          ]

      set({
        strokes: nextStrokes,
        lastProcessedBatch: {
          ...state.lastProcessedBatch,
          [payload.strokeId]: payload.batchNo,
        },
      })
    }
  },

  applyHintUpdated: (payload) => {
    set({ hint: payload.hint })
  },

  applyGuessAccepted: (payload) => {
    set((state) => {
      const alreadyGuessed = state.guessedGuestIds.includes(payload.guestId)
      const nextGuessed = alreadyGuessed
        ? state.guessedGuestIds
        : [...state.guessedGuestIds, payload.guestId]

      return {
        guessedGuestIds: nextGuessed,
      }
    })
  },

  applyGuessChat: (payload, ts) => {
    set((state) => ({
      chat: [
        ...state.chat.slice(-(MAX_CHAT_LINES - 1)),
        {
          guestId: payload.guestId,
          text: payload.text,
          scope: 'PUBLIC',
          ts: ts ?? new Date().toISOString(),
        },
      ],
    }))
  },

  applyGuesserChat: (payload, ts) => {
    set((state) => ({
      chat: [
        ...state.chat.slice(-(MAX_CHAT_LINES - 1)),
        {
          guestId: payload.guestId,
          text: payload.text,
          scope: 'GUESSERS',
          ts: ts ?? new Date().toISOString(),
        },
      ],
    }))
  },

  applyRoundEnded: (payload) => {
    set({
      round: payload.round,
      drawerGuestId: payload.drawerGuestId,
      word: payload.word,
      roundEnd: {
        reason: payload.reason,
        scoreDeltas: payload.scoreDeltas,
      },
    })
  },

  applyWordChoices: (payload) => {
    set({ wordChoices: payload.choices, wordChoicesPhaseId: payload.phaseId })
  },

  applyDrawerWord: (payload) => {
    set({ word: payload.word, wordChoices: null, wordChoicesPhaseId: null })
  },

  applyGuessFeedback: (payload) => {
    set({ feedback: payload.result })
  },

  trackCommand: (actionId, type, strokeId) => {
    if (trackedCommands.size >= MAX_TRACKED_COMMANDS) {
      const oldest = trackedCommands.keys().next().value
      if (oldest !== undefined) trackedCommands.delete(oldest)
    }
    trackedCommands.set(actionId, { type, strokeId })
  },

  applyActionAck: (actionId) => {
    if (actionId) trackedCommands.delete(actionId)
  },

  applyCommandError: (payload, envelopeActionId) => {
    const actionId = payload.actionId ?? envelopeActionId
    if (!actionId) return 'unhandled'
    const command = trackedCommands.get(actionId)
    if (!command) return 'unhandled'
    trackedCommands.delete(actionId)

    // Commands that lost a race with a phase change (deadline, round end): the events that moved
    // the phase on already tell the player what happened, so an error banner would only confuse.
    if (
      command.type !== 'SUBMIT_GUESS' &&
      (payload.code === 'STALE_ACTION' ||
        payload.code === 'INVALID_PHASE' ||
        payload.code === 'DUPLICATE_ACTION')
    ) {
      return 'handled'
    }

    if (command.type === 'DRAW_STROKE' && command.strokeId) {
      const strokeId = command.strokeId
      rejectionNonce += 1
      if (payload.code === 'PAYLOAD_TOO_LARGE') {
        // The batch was not stored or shown to anyone; resync drops it from our preview too.
        set({
          canvasFull: true,
          strokeRejection: { strokeId, kind: 'FULL', nonce: rejectionNonce },
        })
        return 'resync'
      }
      if (
        payload.code === 'INVALID_ACTION' ||
        payload.code === 'VALIDATION_FAILED' ||
        payload.code === 'RATE_LIMITED'
      ) {
        set({ strokeRejection: { strokeId, kind: 'RESTART', nonce: rejectionNonce } })
        return 'resync'
      }
    }

    return 'unhandled'
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

  clearFeedback: () => set({ feedback: null }),

  reset: () => {
    trackedCommands.clear()
    set(initialState)
  },
}))
