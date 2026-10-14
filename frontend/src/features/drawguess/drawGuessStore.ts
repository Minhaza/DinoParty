import { create } from 'zustand'

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

export interface DrawGuessState {
  round: number
  totalRounds: number
  drawerGuestId: string | null
  hint: string | null
  word: string | null
  wordChoices: [string, string, string] | null
  strokes: Stroke[]
  guessedGuestIds: string[]
  chat: DrawGuessChatMessage[]
  roundEnd: RoundEndInfo | null
  feedback: GuessFeedbackResult | null
  localStrokeIds: string[]
  lastProcessedBatch: Record<string, number>

  // Actions
  applySnapshot: (snapshot: DrawGuessSnapshot | null) => void
  applyPhaseStarted: (payload: DrawGuessPhaseStartedPayload) => void
  applyDrawingUpdated: (payload: DrawingUpdatedPayload, currentGuestId?: string) => void
  applyHintUpdated: (payload: HintUpdatedPayload) => void
  applyGuessAccepted: (payload: GuessAcceptedPayload) => void
  applyGuessChat: (payload: GuessChatPayload) => void
  applyGuesserChat: (payload: GuesserChatPayload) => void
  applyRoundEnded: (payload: RoundEndedPayload) => void
  applyWordChoices: (payload: WordChoicesPayload) => void
  applyDrawerWord: (payload: DrawerWordPayload) => void
  applyGuessFeedback: (payload: GuessFeedbackPayload) => void
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
  strokes: [] as Stroke[],
  guessedGuestIds: [] as string[],
  chat: [] as DrawGuessChatMessage[],
  roundEnd: null,
  feedback: null,
  localStrokeIds: [] as string[],
  lastProcessedBatch: {} as Record<string, number>,
}

export const useDrawGuessStore = create<DrawGuessState>((set, get) => ({
  ...initialState,

  applySnapshot: (snapshot) => {
    if (!snapshot) {
      set(initialState)
      return
    }

    const processed: Record<string, number> = {}
    for (const stroke of snapshot.strokes || []) {
      processed[stroke.strokeId] = 999999
    }

    set({
      round: snapshot.round ?? 1,
      totalRounds: snapshot.totalRounds ?? 1,
      drawerGuestId: snapshot.drawerGuestId ?? null,
      hint: snapshot.hint ?? null,
      word: snapshot.word ?? null,
      wordChoices: snapshot.wordChoices ?? null,
      strokes: snapshot.strokes ? [...snapshot.strokes] : [],
      guessedGuestIds: snapshot.guessedGuestIds ? [...snapshot.guessedGuestIds] : [],
      chat: snapshot.recentChat ? [...snapshot.recentChat] : [],
      roundEnd: snapshot.roundEnd ?? null,
      feedback: null,
      localStrokeIds: [],
      lastProcessedBatch: processed,
    })
  },

  applyPhaseStarted: (payload) => {
    set((state) => {
      const isNewRound = payload.phase === 'WORD_CHOICE'
      return {
        round: payload.round ?? state.round,
        totalRounds: payload.totalRounds ?? state.totalRounds,
        drawerGuestId: payload.drawerGuestId ?? state.drawerGuestId,
        hint: payload.hint !== undefined ? payload.hint : (isNewRound ? null : state.hint),
        word: isNewRound ? null : state.word,
        wordChoices: isNewRound ? null : state.wordChoices,
        // Reset canvas and per-round state on new round (WORD_CHOICE)
        strokes: isNewRound ? [] : state.strokes,
        guessedGuestIds: isNewRound ? [] : state.guessedGuestIds,
        roundEnd: isNewRound ? null : state.roundEnd,
        feedback: null,
        localStrokeIds: isNewRound ? [] : state.localStrokeIds,
        lastProcessedBatch: isNewRound ? {} : state.lastProcessedBatch,
      }
    })
  },

  applyDrawingUpdated: (payload, currentGuestId) => {
    const state = get()

    if (payload.kind === 'CLEAR') {
      set({
        strokes: [],
        localStrokeIds: [],
        lastProcessedBatch: {},
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
      })
      return
    }

    if (payload.kind === 'STROKE') {
      const isDrawer = Boolean(currentGuestId && state.drawerGuestId === currentGuestId)
      const isLocal = state.localStrokeIds.includes(payload.strokeId)

      // Echo suppression: If drawer sent this stroke locally, don't duplicate points
      if (isDrawer && isLocal) {
        // Just update closed flag if final
        if (payload.final) {
          set({
            strokes: state.strokes.map((s) =>
              s.strokeId === payload.strokeId ? { ...s, closed: true } : s,
            ),
          })
        }
        return
      }

      // Deduplication: Check if batch was already processed
      const lastBatch = state.lastProcessedBatch[payload.strokeId]
      if (lastBatch !== undefined && payload.batchNo <= lastBatch) {
        return
      }

      const existingIndex = state.strokes.findIndex((s) => s.strokeId === payload.strokeId)
      let nextStrokes: Stroke[]

      if (existingIndex >= 0) {
        nextStrokes = state.strokes.map((s, idx) => {
          if (idx !== existingIndex) return s
          return {
            ...s,
            points: [...s.points, ...payload.points],
            closed: payload.final ? true : s.closed,
          }
        })
      } else {
        const newStroke: Stroke = {
          strokeId: payload.strokeId,
          tool: payload.tool,
          color: payload.color,
          size: payload.size,
          points: [...payload.points],
          closed: payload.final,
        }
        nextStrokes = [...state.strokes, newStroke]
      }

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

  applyGuessChat: (payload) => {
    set((state) => ({
      chat: [
        ...state.chat.slice(-49),
        {
          guestId: payload.guestId,
          text: payload.text,
          scope: 'PUBLIC',
          ts: new Date().toISOString(),
        },
      ],
    }))
  },

  applyGuesserChat: (payload) => {
    set((state) => ({
      chat: [
        ...state.chat.slice(-49),
        {
          guestId: payload.guestId,
          text: payload.text,
          scope: 'GUESSERS',
          ts: new Date().toISOString(),
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
    set({ wordChoices: payload.choices })
  },

  applyDrawerWord: (payload) => {
    set({ word: payload.word, wordChoices: null })
  },

  applyGuessFeedback: (payload) => {
    set({ feedback: payload.result })
  },

  addLocalStroke: (stroke) => {
    set((state) => ({
      strokes: [...state.strokes, stroke],
      localStrokeIds: [...state.localStrokeIds, stroke.strokeId],
      lastProcessedBatch: {
        ...state.lastProcessedBatch,
        [stroke.strokeId]: 0,
      },
    }))
  },

  appendLocalPoints: (strokeId, points, isFinal) => {
    set((state) => ({
      strokes: state.strokes.map((s) =>
        s.strokeId === strokeId
          ? {
              ...s,
              points: [...s.points, ...points],
              closed: isFinal ? true : s.closed,
            }
          : s,
      ),
    }))
  },

  clearFeedback: () => set({ feedback: null }),

  reset: () => set(initialState),
}))
