import type { GameSnapshotHeader, PhaseStartedPayload } from '@/api/types'
import type { CanvasBudget, Stroke } from '@/features/drawing/types'

// Dino Telephone wire types (api-contracts §2.1 `TelephoneSnapshot`, `AlbumDto`, §3.10): the step
// phases, the host-driven reveal and the album endpoints.

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
  /** Only in REVEAL: the shared cursor and every entry revealed so far (within a byte budget). */
  reveal: TelephoneRevealView | null
}

// Events

export interface TelephonePhaseStartedPayload extends PhaseStartedPayload {
  step?: number
  totalSteps?: number
  kind?: TelephoneKind
  /** REVEAL only. */
  chains?: number
  cursor?: RevealCursor
  autoAdvanceAt?: string
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

// Reveal and albums (api-contracts §2.1, §3.10)

export type TelephoneFill = 'NONE' | 'TIMEOUT' | 'LEFT_PLAYER' | 'AUTO_PROMPT'

/** A position in reveal order: album (chain) and entry within it, both 0-based. */
export interface RevealCursor {
  chainIndex: number
  entryIndex: number
}

interface RevealEntryBase {
  entryIndex: number
  authorGuestId: string
  /** Roster snapshot, so a player who left is still attributed. */
  authorNickname: string | null
  /** Strokes withheld by the reconnect snapshot's size budget; available from the results. */
  omitted: boolean
  fill: TelephoneFill
  timedOut: boolean
  placeholder: boolean
}

/** One revealed entry as STOMP carries it (`ALBUM_REVEALED.entry`, snapshot albums). */
export type RevealEntryDto =
  | (RevealEntryBase & { kind: 'TEXT'; text: string })
  | (RevealEntryBase & { kind: 'DRAWING'; strokes: Stroke[] | null })

export interface RevealAlbumView {
  chainIndex: number
  ownerGuestId: string
  entryCount: number
  revealedCount: number
  entries: RevealEntryDto[]
}

/** Snapshot `reveal`: never contains an entry past `highWater`. */
export interface TelephoneRevealView {
  cursor: RevealCursor
  highWater: RevealCursor
  autoAdvanceAt: string | null
  chains: number
  albums: RevealAlbumView[]
}

/** Public `ALBUM_REVEALED`: `entry` only the first time an entry is shown. */
export interface AlbumRevealedPayload extends RevealCursor {
  newlyRevealed: boolean
  entry: RevealEntryDto | null
  autoAdvanced: boolean
  autoAdvanceAt: string | null
}

/** `GET /sessions/{id}/albums`: the list, without content. */
export interface AlbumSummaryDto {
  chainIndex: number
  ownerGuestId: string | null
  entryCount: number
  entriesPerChain: number
  complete: boolean
  hasPlaceholders: boolean
}

export interface AlbumsResponse {
  sessionId: string
  complete: boolean
  albums: AlbumSummaryDto[]
}

interface AlbumEntryBase {
  step: number
  authorGuestId: string
  authorNickname: string | null
  fill: TelephoneFill
  timedOut: boolean
  placeholder: boolean
}

export type AlbumEntryDto =
  | (AlbumEntryBase & { kind: 'TEXT'; text: string })
  | (AlbumEntryBase & { kind: 'DRAWING'; strokes: Stroke[] })

/** `GET /sessions/{id}/albums/{chainIndex}`: one album, every entry in step order. */
export interface AlbumDto {
  sessionId: string
  chainIndex: number
  ownerGuestId: string | null
  entriesPerChain: number
  complete: boolean
  entries: AlbumEntryDto[]
}

/**
 * The one shape every album view renders, built from either a reveal entry (live) or an album
 * entry (results), so both sources share the same components. `strokes` is null for text and for a
 * drawing the client does not hold (`omitted`).
 */
export interface AlbumEntryView {
  step: number
  kind: 'TEXT' | 'DRAWING'
  authorGuestId: string
  authorNickname: string | null
  text: string | null
  strokes: Stroke[] | null
  omitted: boolean
  fill: TelephoneFill
  timedOut: boolean
  placeholder: boolean
}

// Command types (api-contracts §3.10)

export const TELEPHONE_COMMANDS = {
  submitText: 'TELEPHONE_SUBMIT_TEXT',
  textDraft: 'TELEPHONE_TEXT_DRAFT',
  drawStroke: 'TELEPHONE_DRAW_STROKE',
  drawUndo: 'TELEPHONE_DRAW_UNDO',
  drawClear: 'TELEPHONE_DRAW_CLEAR',
  submitDrawing: 'TELEPHONE_SUBMIT_DRAWING',
  revealNext: 'REVEAL_NEXT',
  revealPrev: 'REVEAL_PREV',
} as const

export type TelephoneCommandType = (typeof TELEPHONE_COMMANDS)[keyof typeof TELEPHONE_COMMANDS]
