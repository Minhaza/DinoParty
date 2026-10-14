import type { GameSnapshotHeader, PhaseStartedPayload } from '@/api/types'

export type StrokeTool = 'PENCIL' | 'ERASER'

export interface Stroke {
  strokeId: string
  tool: StrokeTool
  color: string
  size: number
  points: [number, number][]
  closed?: boolean
}

export type ChatScope = 'PUBLIC' | 'GUESSERS'

export interface DrawGuessChatMessage {
  guestId: string
  text: string
  scope: ChatScope
  ts?: string
}

export type RoundEndReason = 'TIME' | 'ALL_GUESSED' | 'DRAWER_LEFT'

export interface RoundEndInfo {
  reason: RoundEndReason
  scoreDeltas: Record<string, number>
}

export interface DrawGuessSnapshot extends GameSnapshotHeader {
  round: number
  totalRounds: number
  drawerGuestId: string
  hint: string | null
  word: string | null
  wordChoices: [string, string, string] | null
  strokes: Stroke[]
  guessedGuestIds: string[]
  recentChat: DrawGuessChatMessage[]
  roundEnd: RoundEndInfo | null
}

// Event payloads

export interface DrawingStrokeUpdate {
  kind: 'STROKE'
  strokeId: string
  batchNo: number
  tool: StrokeTool
  color: string
  size: number
  points: [number, number][]
  final: boolean
}

export interface DrawingUndoUpdate {
  kind: 'UNDO'
  strokeId: string
}

export interface DrawingClearUpdate {
  kind: 'CLEAR'
}

export type DrawingUpdatedPayload =
  | DrawingStrokeUpdate
  | DrawingUndoUpdate
  | DrawingClearUpdate

export interface HintUpdatedPayload {
  hint: string
}

export interface GuessAcceptedPayload {
  guestId: string
  scoreDelta: number
  totalScore: number
  order: number
}

export interface GuessChatPayload {
  guestId: string
  text: string
}

export interface GuesserChatPayload {
  guestId: string
  text: string
}

export interface RoundEndedPayload {
  round: number
  drawerGuestId: string
  word: string | null
  reason: RoundEndReason
  scoreDeltas: Record<string, number>
  scores: Record<string, number>
}

export interface WordChoicesPayload {
  phaseId: string
  choices: [string, string, string]
  deadlineAt: string
}

export interface DrawerWordPayload {
  phaseId: string
  word: string
}

export type GuessFeedbackResult = 'CLOSE' | 'DUPLICATE'

export interface GuessFeedbackPayload {
  result: GuessFeedbackResult
}

export interface DrawGuessPhaseStartedPayload extends PhaseStartedPayload {
  round: number
  totalRounds: number
  drawerGuestId: string
  hint?: string | null
}

// Command payloads

export interface ChooseWordPayload {
  wordIndex: number
}

export interface DrawStrokePayload {
  strokeId: string
  batchNo: number
  tool: StrokeTool
  color: string
  size: number
  points: [number, number][]
  final: boolean
}

export type DrawUndoPayload = Record<string, never>
export type DrawClearPayload = Record<string, never>

export interface SubmitGuessPayload {
  text: string
}
