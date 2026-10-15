import type { GameSnapshotHeader, PhaseStartedPayload } from '@/api/types'
import type { CanvasBudget, Stroke } from '@/features/drawing/types'

// Dino Telephone wire types (api-contracts §2.1 `TelephoneSnapshot`, §3.10). Step phases only;
// the reveal (`ALBUM_REVEALED`, `reveal` snapshot field) is typed when it is implemented.

export type TelephoneKind = 'PROMPT' | 'DRAW' | 'DESCRIBE'
export type TelephonePhase = TelephoneKind | 'REVEAL'

/** The previous entry of the held chain; never carries its author ([TP-8]). */
export type TelephoneSource =
  | { kind: 'TEXT'; text: string; placeholder: boolean }
  | { kind: 'DRAWING'; strokes: Stroke[]; placeholder: boolean }

/** Snapshot form of the viewer's task; `source` is null in PROMPT. */
export interface TelephoneTaskView {
  kind: TelephoneKind
  source: TelephoneSource | null
}

/** The viewer's own work in progress: `text` in PROMPT/DESCRIBE, `strokes` in DRAW. */
export interface TelephoneDraft {
  text?: string
  strokes?: Stroke[]
  canvasBudget?: CanvasBudget
}

export interface TelephoneSnapshot extends GameSnapshotHeader {
  /** 0-based; equals `entriesPerChain` once the steps are over. */
  step: number
  totalSteps: number
  entriesPerChain: number
  kind: TelephoneKind | null
  task: TelephoneTaskView | null
  draft: TelephoneDraft | null
  submitted: boolean
  submittedGuestIds: string[]
  waitingOn: number
  reveal: unknown
}

// Events

export interface TelephonePhaseStartedPayload extends PhaseStartedPayload {
  step?: number
  totalSteps?: number
  kind?: TelephoneKind
}

/** Private `TELEPHONE_TASK`: what the recipient must work on in this step. */
export interface TelephoneTaskPayload {
  phaseId: string
  step: number
  kind: TelephoneKind
  source: TelephoneSource | null
  deadlineAt: string
}

/** Public `TELEPHONE_PROGRESS`: names and counts only, never content. */
export interface TelephoneProgressPayload {
  phaseId: string
  submittedCount: number
  total: number
  submittedGuestIds: string[]
}

export type TelephoneStepEndReason = 'ALL_SUBMITTED' | 'TIME'

export interface TelephoneStepEndedPayload {
  step: number
  kind: TelephoneKind
  reason: TelephoneStepEndReason
  timedOutGuestIds: string[]
  leftGuestIds: string[]
}

// Command types (api-contracts §3.10)

export const TELEPHONE_COMMANDS = {
  submitText: 'TELEPHONE_SUBMIT_TEXT',
  textDraft: 'TELEPHONE_TEXT_DRAFT',
  drawStroke: 'TELEPHONE_DRAW_STROKE',
  drawUndo: 'TELEPHONE_DRAW_UNDO',
  drawClear: 'TELEPHONE_DRAW_CLEAR',
  submitDrawing: 'TELEPHONE_SUBMIT_DRAWING',
} as const

export type TelephoneCommandType = (typeof TELEPHONE_COMMANDS)[keyof typeof TELEPHONE_COMMANDS]
