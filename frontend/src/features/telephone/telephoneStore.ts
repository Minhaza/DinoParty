import { create } from 'zustand'

import type { GameErrorPayload } from '@/api/types'
import { isBudgetExhausted, type Stroke, type StrokeRejection } from '@/features/drawing/types'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import {
  TELEPHONE_COMMANDS,
  type TelephoneCommandType,
  type TelephoneKind,
  type TelephonePhaseStartedPayload,
  type TelephoneProgressPayload,
  type TelephoneSnapshot,
  type TelephoneStepEndedPayload,
  type TelephoneTaskPayload,
  type TelephoneTaskView,
} from './types'

/** Bound for the in-flight command table (the server remembers 256 actionIds per guest). */
const MAX_TRACKED_COMMANDS = 256

const TELEPHONE_KINDS: readonly string[] = ['PROMPT', 'DRAW', 'DESCRIBE']

/**
 * Where the viewer's own submission for the current step stands. `submitted` is only ever set by
 * the server (ACK of the current phase, or a snapshot); sending a command moves to `submitting`.
 */
export type SubmissionStatus = 'editing' | 'submitting' | 'submitted' | 'error'

/** What the caller must do after a command error: nothing more, show it, or resync from the server. */
export type CommandErrorOutcome = 'unhandled' | 'handled' | 'resync'

interface TrackedCommand {
  type: TelephoneCommandType
  phaseId: string
  strokeId?: string
}

// Not React state: only consulted when an ACK/error comes back, never rendered.
const trackedCommands = new Map<string, TrackedCommand>()

const isSubmit = (type: string) =>
  type === TELEPHONE_COMMANDS.submitText || type === TELEPHONE_COMMANDS.submitDrawing

/** The phase the server says is current; responses to any other phase are stale. */
const currentPhaseId = () => useGameSessionStore.getState().phaseId

export interface TelephoneState {
  step: number
  totalSteps: number
  kind: TelephoneKind | null
  /** The phase the task, draft and submission below belong to. */
  workPhaseId: string | null
  /** The viewer's own task for `workPhaseId`; earlier entries of the chain are never kept. */
  task: TelephoneTaskView | null
  /** A private task that arrived before the public `PHASE_STARTED` of its phase. */
  pendingTask: TelephoneTaskPayload | null
  progress: TelephoneProgressPayload | null
  stepEnded: TelephoneStepEndedPayload | null
  textDraft: string
  strokes: Stroke[]
  canvasFull: boolean
  strokeRejection: StrokeRejection | null
  submission: SubmissionStatus
  submitError: string | null
  /** A command lost a race with the phase (deadline passed): waiting for the server's next phase. */
  syncing: boolean

  applySnapshot: (snapshot: TelephoneSnapshot | null) => void
  applyPhaseStarted: (payload: TelephonePhaseStartedPayload) => void
  applyTask: (payload: TelephoneTaskPayload) => void
  applyProgress: (payload: TelephoneProgressPayload) => void
  applyStepEnded: (payload: TelephoneStepEndedPayload) => void
  trackCommand: (
    actionId: string,
    type: TelephoneCommandType,
    phaseId: string,
    strokeId?: string,
  ) => void
  applyActionAck: (actionId: string | null | undefined) => void
  applyCommandError: (payload: GameErrorPayload, actionId?: string | null) => CommandErrorOutcome
  setTextDraft: (text: string) => void
  addLocalStroke: (stroke: Stroke) => void
  appendLocalPoints: (strokeId: string, points: [number, number][], isFinal: boolean) => void
  undoLocalStroke: () => void
  clearLocalStrokes: () => void
  reset: () => void
}

const initialState = {
  step: 0,
  totalSteps: 0,
  kind: null,
  workPhaseId: null,
  task: null,
  pendingTask: null,
  progress: null,
  stepEnded: null,
  textDraft: '',
  strokes: [] as Stroke[],
  canvasFull: false,
  strokeRejection: null,
  submission: 'editing' as SubmissionStatus,
  submitError: null,
  syncing: false,
}

/** Fresh per-step work: nothing of the previous step's draft or submission survives. */
const freshWork = {
  task: null,
  textDraft: '',
  strokes: [] as Stroke[],
  canvasFull: false,
  strokeRejection: null,
  submission: 'editing' as SubmissionStatus,
  submitError: null,
  syncing: false,
}

let rejectionNonce = 0

function asKind(value: unknown): TelephoneKind | null {
  return typeof value === 'string' && TELEPHONE_KINDS.includes(value)
    ? (value as TelephoneKind)
    : null
}

export const useTelephoneStore = create<TelephoneState>((set, get) => ({
  ...initialState,

  applySnapshot: (snapshot) => {
    if (!snapshot) {
      trackedCommands.clear()
      set(initialState)
      return
    }

    const state = get()
    const sameWork = state.workPhaseId !== null && state.workPhaseId === snapshot.phaseId
    const submitted = snapshot.submitted === true
    const draft = snapshot.draft
    const budget = draft?.canvasBudget
    const submittedGuestIds = snapshot.submittedGuestIds ?? []

    // Text typed while the socket was down is newer than the server draft of the same step.
    const keepLocalText = sameWork && !submitted && state.textDraft !== ''

    set({
      step: snapshot.step ?? 0,
      totalSteps: snapshot.totalSteps ?? 0,
      kind: asKind(snapshot.kind),
      workPhaseId: snapshot.phaseId,
      task: snapshot.task ?? null,
      pendingTask: null,
      progress: {
        phaseId: snapshot.phaseId,
        submittedCount: submittedGuestIds.length,
        total: submittedGuestIds.length + (snapshot.waitingOn ?? 0),
        submittedGuestIds: [...submittedGuestIds],
      },
      stepEnded: sameWork ? state.stepEnded : null,
      // An accepted submission is final ([TP-7]): no editable draft is shown afterwards.
      textDraft: submitted ? '' : keepLocalText ? state.textDraft : (draft?.text ?? ''),
      strokes: submitted ? [] : draft?.strokes ? [...draft.strokes] : [],
      canvasFull: !submitted && budget !== undefined && isBudgetExhausted(budget),
      strokeRejection: null,
      submission: submitted
        ? 'submitted'
        : sameWork && state.submission === 'submitting'
          ? 'submitting'
          : 'editing',
      submitError: !submitted && sameWork ? state.submitError : null,
      syncing: false,
    })
  },

  applyPhaseStarted: (payload) => {
    const kind = asKind(payload.phase)
    set((state) => {
      const pending = state.pendingTask?.phaseId === payload.phaseId ? state.pendingTask : null
      return {
        ...freshWork,
        step: payload.step ?? (kind ? state.step : state.totalSteps),
        totalSteps: payload.totalSteps ?? state.totalSteps,
        kind,
        workPhaseId: payload.phaseId,
        task: kind && pending ? { kind: pending.kind, source: pending.source } : null,
        pendingTask: null,
        progress: state.progress?.phaseId === payload.phaseId ? state.progress : null,
      }
    })
  },

  applyTask: (payload) => {
    // The private stream is ordered independently of the public one, so the task may be processed
    // before its PHASE_STARTED; it is held until that phase begins.
    if (payload.phaseId !== get().workPhaseId) {
      set({ pendingTask: payload })
      return
    }
    set({ task: { kind: payload.kind, source: payload.source }, pendingTask: null })
  },

  applyProgress: (payload) => {
    if (payload.phaseId !== get().workPhaseId) return
    set({ progress: { ...payload, submittedGuestIds: [...payload.submittedGuestIds] } })
  },

  applyStepEnded: (payload) => {
    set({ stepEnded: payload })
  },

  trackCommand: (actionId, type, phaseId, strokeId) => {
    if (trackedCommands.size >= MAX_TRACKED_COMMANDS) {
      const oldest = trackedCommands.keys().next().value
      if (oldest !== undefined) trackedCommands.delete(oldest)
    }
    trackedCommands.set(actionId, { type, phaseId, strokeId })
    if (isSubmit(type)) set({ submission: 'submitting', submitError: null })
  },

  applyActionAck: (actionId) => {
    if (!actionId) return
    const command = trackedCommands.get(actionId)
    if (!command) return
    trackedCommands.delete(actionId)
    // An ACK from an earlier phase must not mark the current step as submitted.
    if (!isSubmit(command.type)) return
    if (command.phaseId !== currentPhaseId() || command.phaseId !== get().workPhaseId) return
    set({ submission: 'submitted', submitError: null, textDraft: '', strokes: [] })
  },

  applyCommandError: (payload, envelopeActionId) => {
    const actionId = payload.actionId ?? envelopeActionId
    if (!actionId) return 'unhandled'
    const command = trackedCommands.get(actionId)
    if (!command) return 'unhandled'
    trackedCommands.delete(actionId)

    // The answer to a command of a phase that is already over changes nothing now.
    if (command.phaseId !== currentPhaseId() || command.phaseId !== get().workPhaseId) {
      return 'handled'
    }

    switch (payload.code) {
      case 'ALREADY_SUBMITTED':
        // The server holds a final entry for this step ([TP-7]); the snapshot confirms it.
        set({ submission: 'submitted', submitError: null, textDraft: '', strokes: [] })
        return 'resync'
      case 'STALE_ACTION':
      case 'INVALID_PHASE':
        // The deadline beat the command; the draft already became the entry ([TP-5]).
        set((state) => ({
          syncing: true,
          submission: state.submission === 'submitting' ? 'editing' : state.submission,
        }))
        return 'resync'
      case 'DUPLICATE_ACTION':
        return 'handled'
    }

    if (isSubmit(command.type)) {
      set({
        submission: 'error',
        submitError:
          payload.code === 'VALIDATION_FAILED'
            ? 'That entry was not accepted. Check it and try again.'
            : payload.retryable
              ? 'Too fast! Wait a moment and try again.'
              : payload.message || 'Your entry could not be sent.',
      })
      return 'handled'
    }

    if (command.type === TELEPHONE_COMMANDS.drawStroke && command.strokeId) {
      rejectionNonce += 1
      if (payload.code === 'PAYLOAD_TOO_LARGE') {
        // The batch was not stored; the resync drops it from the local preview too.
        set({
          canvasFull: true,
          strokeRejection: { strokeId: command.strokeId, kind: 'FULL', nonce: rejectionNonce },
        })
        return 'resync'
      }
      set({
        strokeRejection: { strokeId: command.strokeId, kind: 'RESTART', nonce: rejectionNonce },
      })
      return 'resync'
    }

    if (
      command.type === TELEPHONE_COMMANDS.drawUndo ||
      command.type === TELEPHONE_COMMANDS.drawClear
    ) {
      // Undo/clear were applied locally; the server draft is the truth.
      return 'resync'
    }

    // Text drafts are best effort: the next keystroke or the submission sends the text again.
    return 'handled'
  },

  setTextDraft: (text) => set({ textDraft: text }),

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

  // Strokes are not echoed back in Telephone (api-contracts §3.10), so undo/clear are applied to
  // the local copy as they are sent, exactly as the server applies them to the draft.
  undoLocalStroke: () => {
    set((state) => ({ strokes: state.strokes.slice(0, -1), canvasFull: false }))
  },

  clearLocalStrokes: () => {
    set({ strokes: [], canvasFull: false })
  },

  reset: () => {
    trackedCommands.clear()
    set(initialState)
  },
}))
