import type { GameSnapshotHeader } from '@/api/types'
import type { CanvasBudget, DrawStrokePayload, Stroke } from '@/features/drawing/types'

import type {
  ImpostorChatLine,
  ImpostorRoleInfo,
  ImpostorRoundEndedPayload,
  ImpostorVoteClosedPayload,
} from '../common/types'

/** The phases of Sketchy Saurus (api-contracts §3.12). */
export type SketchySaurusPhase = 'ROLE_REVEAL' | 'SKETCH' | 'GALLERY' | 'GUESS' | 'ROUND_END'

export interface SketchyDrawing {
  guestId: string
  strokes: Stroke[]
  empty: boolean
}

export interface SketchyRoleRevealPhaseStarted {
  phase: 'ROLE_REVEAL'
  phaseId: string
  round: number
  totalRounds: number
  deadlineAt: string
  nicknames: Record<string, string>
}

export interface SketchySketchPhaseStarted {
  phase: 'SKETCH'
  phaseId: string
  round: number
  totalRounds: number
  deadlineAt: string
}

export interface SketchyGalleryPhaseStarted {
  phase: 'GALLERY'
  phaseId: string
  round: number
  totalRounds: number
  deadlineAt: string
}

export interface SketchyGuessPhaseStarted {
  phase: 'GUESS'
  phaseId: string
  round: number
  totalRounds: number
  deadlineAt: string
  impostorGuestId: string
}

export interface SketchyRoundEndPhaseStarted {
  phase: 'ROUND_END'
  phaseId: string
  round: number
  totalRounds: number
  deadlineAt: string
}

export type SketchySaurusPhaseStartedPayload =
  | SketchyRoleRevealPhaseStarted
  | SketchySketchPhaseStarted
  | SketchyGalleryPhaseStarted
  | SketchyGuessPhaseStarted
  | SketchyRoundEndPhaseStarted

export interface SketchyProgressPayload {
  phaseId: string
  submittedGuestIds: string[]
}

export interface SketchyGalleryOpenedPayload {
  round: number
  drawings: SketchyDrawing[]
}

export interface SketchySaurusSnapshot extends GameSnapshotHeader {
  round: number
  totalRounds: number
  nicknames: Record<string, string>
  role: ImpostorRoleInfo | null
  draft: {
    strokes: Stroke[]
    canvasBudget: CanvasBudget
  } | null
  submitted: boolean
  submittedGuestIds: string[]
  gallery: SketchyDrawing[] | null
  votedGuestIds: string[]
  myVote: string | null
  recentChat: ImpostorChatLine[]
  voteClosed: ImpostorVoteClosedPayload | null
  roundEnd: ImpostorRoundEndedPayload | null
}

export const SKETCHY_COMMANDS = {
  drawStroke: 'SKETCHY_DRAW_STROKE',
  undo: 'SKETCHY_DRAW_UNDO',
  clear: 'SKETCHY_DRAW_CLEAR',
  submit: 'SKETCHY_SUBMIT',
} as const

export const SKETCHY_EVENTS = {
  progress: 'SKETCHY_PROGRESS',
  galleryOpened: 'SKETCHY_GALLERY_OPENED',
} as const

export type SketchyCommandType = (typeof SKETCHY_COMMANDS)[keyof typeof SKETCHY_COMMANDS]

export interface SketchyDrawStrokeCommand {
  type: typeof SKETCHY_COMMANDS.drawStroke
  payload: DrawStrokePayload
}

export interface SketchyDrawUndoCommand {
  type: typeof SKETCHY_COMMANDS.undo
  payload: Record<string, never>
}

export interface SketchyDrawClearCommand {
  type: typeof SKETCHY_COMMANDS.clear
  payload: Record<string, never>
}

export interface SketchySubmitCommand {
  type: typeof SKETCHY_COMMANDS.submit
  payload: Record<string, never>
}

export type SketchyCommand =
  SketchyDrawStrokeCommand | SketchyDrawUndoCommand | SketchyDrawClearCommand | SketchySubmitCommand
