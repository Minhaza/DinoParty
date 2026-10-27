// Wire types of the impostor game family (api-contracts §3.11). The `IMPOSTOR_*` commands and events
// are shared by Dino Impostor and Sketchy Saurus (§3.12, P9-06); the Dino-only parts are marked.

import type { GameSnapshotHeader, PhaseStartedPayload } from '@/api/types'
import type { CanvasBudget, DrawStrokePayload, Stroke, StrokeTool } from '@/features/drawing/types'

export type ImpostorRole = 'ARTIST' | 'IMPOSTOR'

export interface ImpostorCategory {
  key: string
  label: string
}

/** The viewer's own role (`IMPOSTOR_ROLE` minus its envelope fields, snapshot `role`). */
export interface ImpostorRoleInfo {
  role: ImpostorRole
  category: ImpostorCategory
  word: string | null
}

export type ImpostorOutcome = 'ESCAPED' | 'GUESSED' | 'CAUGHT' | 'IMPOSTOR_LEFT'

export interface ImpostorChatLine {
  guestId: string
  text: string
  ts: string
}

// Commands (client → server). `phaseId` and `actionId` live on the command envelope (§3.2).

export const IMPOSTOR_COMMANDS = {
  drawStroke: 'IMPOSTOR_DRAW_STROKE',
  chat: 'IMPOSTOR_CHAT',
  vote: 'IMPOSTOR_VOTE',
  guess: 'IMPOSTOR_GUESS',
} as const

export type ImpostorCommandType = (typeof IMPOSTOR_COMMANDS)[keyof typeof IMPOSTOR_COMMANDS]

/** Dino Impostor only: `DRAW_STROKE`'s payload with `tool` fixed to `PENCIL` and the sender's colour. */
export interface ImpostorDrawStrokePayload extends DrawStrokePayload {
  tool: 'PENCIL'
}

export interface ImpostorChatPayload {
  text: string
}

export interface ImpostorVotePayload {
  /** `null` withdraws the vote. */
  targetGuestId: string | null
}

export interface ImpostorGuessPayload {
  text: string
}

export type ImpostorCommand =
  | { type: 'IMPOSTOR_DRAW_STROKE'; payload: ImpostorDrawStrokePayload }
  | { type: 'IMPOSTOR_CHAT'; payload: ImpostorChatPayload }
  | { type: 'IMPOSTOR_VOTE'; payload: ImpostorVotePayload }
  | { type: 'IMPOSTOR_GUESS'; payload: ImpostorGuessPayload }

/** `IMPOSTOR_CHAT` text: 1–120 code points after stripping `Cc`/`Cf` and trimming ([DI-8]). */
export const IMPOSTOR_CHAT_MAX = 120
/** `IMPOSTOR_GUESS` text: 1–80 code points after trim ([DI-11], as `SUBMIT_GUESS`). */
export const IMPOSTOR_GUESS_MAX = 80

// Events (server → client)

export interface ImpostorRolePayload extends ImpostorRoleInfo {
  phaseId: string
  round: number
}

/** Dino Impostor only: one accepted batch of the shared canvas plus its author. */
export interface ImpostorDrawingUpdatedPayload {
  kind: 'STROKE'
  authorGuestId: string
  strokeId: string
  batchNo: number
  tool: StrokeTool
  color: string
  size: number
  points: [number, number][]
  final: boolean
}

export interface ImpostorChatMessagePayload {
  guestId: string
  text: string
  ts: string
}

export interface ImpostorVoteProgressPayload {
  phaseId: string
  votedGuestIds: string[]
}

export interface ImpostorVoteClosedPayload {
  round: number
  /** voter → target; abstainers are absent. */
  votes: Record<string, string>
  /** Strict top of the vote, else `null`. */
  accusedGuestId: string | null
  caught: boolean
  /** Only when caught. */
  impostorGuestId: string | null
}

export interface ImpostorGuessOpenPayload {
  phaseId: string
  round: number
  category: ImpostorCategory
  deadlineAt: string
}

export interface ImpostorRoundEndedPayload {
  round: number
  outcome: ImpostorOutcome
  impostorGuestId: string
  category: ImpostorCategory
  word: string
  /** Sketchy Saurus decoy; always `null` in Dino Impostor. */
  decoyWord: string | null
  votes: Record<string, string>
  guess: string | null
  scoreDeltas: Record<string, number>
  scores: Record<string, number>
}

export type ImpostorEvent =
  | { type: 'IMPOSTOR_ROLE'; payload: ImpostorRolePayload }
  | { type: 'IMPOSTOR_DRAWING_UPDATED'; payload: ImpostorDrawingUpdatedPayload }
  | { type: 'IMPOSTOR_CHAT_MESSAGE'; payload: ImpostorChatMessagePayload }
  | { type: 'IMPOSTOR_VOTE_PROGRESS'; payload: ImpostorVoteProgressPayload }
  | { type: 'IMPOSTOR_VOTE_CLOSED'; payload: ImpostorVoteClosedPayload }
  | { type: 'IMPOSTOR_GUESS_OPEN'; payload: ImpostorGuessOpenPayload }
  | { type: 'IMPOSTOR_ROUND_ENDED'; payload: ImpostorRoundEndedPayload }

export type ImpostorEventType = ImpostorEvent['type']

export const IMPOSTOR_EVENT_TYPES: readonly ImpostorEventType[] = [
  'IMPOSTOR_ROLE',
  'IMPOSTOR_DRAWING_UPDATED',
  'IMPOSTOR_CHAT_MESSAGE',
  'IMPOSTOR_VOTE_PROGRESS',
  'IMPOSTOR_VOTE_CLOSED',
  'IMPOSTOR_GUESS_OPEN',
  'IMPOSTOR_ROUND_ENDED',
]

export function isImpostorEventType(type: string): type is ImpostorEventType {
  return (IMPOSTOR_EVENT_TYPES as readonly string[]).includes(type)
}

// Dino Impostor phases and snapshot

export type DinoImpostorPhase = 'ROLE_REVEAL' | 'DRAWING' | 'VOTE' | 'GUESS' | 'ROUND_END'

interface DinoImpostorPhaseCommon extends PhaseStartedPayload {
  round: number
  totalRounds: number
}

export interface DinoImpostorRoleRevealPhaseStarted extends DinoImpostorPhaseCommon {
  phase: 'ROLE_REVEAL'
  seatOrder: string[]
  colors: Record<string, string>
  nicknames: Record<string, string>
}

export interface DinoImpostorDrawingPhaseStarted extends DinoImpostorPhaseCommon {
  phase: 'DRAWING'
  /** 1-based (1..laps). */
  lap: number
  laps: number
  /** 0-based. */
  turn: number
  totalTurns: number
  drawerGuestId: string
  seatOrder: string[]
}

export interface DinoImpostorGuessPhaseStarted extends DinoImpostorPhaseCommon {
  phase: 'GUESS'
  impostorGuestId: string
}

export interface DinoImpostorOtherPhaseStarted extends DinoImpostorPhaseCommon {
  phase: 'VOTE' | 'ROUND_END'
}

export type DinoImpostorPhaseStartedPayload =
  | DinoImpostorRoleRevealPhaseStarted
  | DinoImpostorDrawingPhaseStarted
  | DinoImpostorGuessPhaseStarted
  | DinoImpostorOtherPhaseStarted

/** A stroke of the shared canvas with its author (`ImpostorStroke`). */
export interface ImpostorStroke extends Stroke {
  closed: boolean
  authorGuestId: string
}

export interface DinoImpostorSnapshot extends GameSnapshotHeader {
  round: number
  totalRounds: number
  laps: number
  seatOrder: string[]
  colors: Record<string, string>
  nicknames: Record<string, string>
  /** 1-based (1..laps); DRAWING only. */
  lap: number | null
  turn: number | null
  totalTurns: number
  /** DRAWING only. */
  drawerGuestId: string | null
  /** DRAWING only: the drawer's one stroke of this turn if it was started (G4); public. */
  turnStroke: { strokeId: string; nextBatchNo: number; closed: boolean } | null
  /** Viewer only; `null` for spectators. */
  role: ImpostorRoleInfo | null
  strokes: ImpostorStroke[]
  canvasBudget: CanvasBudget
  votedGuestIds: string[]
  /** VOTE: viewer only. */
  myVote: string | null
  /** ≤ 30, oldest first; empty outside VOTE…ROUND_END. */
  recentChat: ImpostorChatLine[]
  /** GUESS, ROUND_END. */
  voteClosed: ImpostorVoteClosedPayload | null
  /** ROUND_END. */
  roundEnd: ImpostorRoundEndedPayload | null
}
