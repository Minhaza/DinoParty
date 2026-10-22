// Pure reducers for the state every impostor game shares (role, vote, chat, guess, reveal; [DI-4],
// [DI-8]…[DI-14]). Each game's store embeds `ImpostorCommonState` and calls these, so a snapshot
// still replaces one store in one step and no value lives in two stores.

import type { GameErrorPayload } from '@/api/types'

import type {
  ImpostorChatLine,
  ImpostorChatMessagePayload,
  ImpostorGuessOpenPayload,
  ImpostorRoleInfo,
  ImpostorRolePayload,
  ImpostorRoundEndedPayload,
  ImpostorVoteClosedPayload,
  ImpostorVoteProgressPayload,
} from './types'

/** Server keeps ≤ 30 chat lines in snapshots; the live feed keeps a few more. */
export const MAX_CHAT_LINES = 50

/** What the caller must do after a command error: nothing more, show it, or resync. */
export type CommandErrorOutcome = 'unhandled' | 'handled' | 'resync'

export interface ImpostorCommonState {
  role: ImpostorRoleInfo | null
  /** `phaseId` of the `ROLE_REVEAL` the role belongs to. */
  rolePhaseId: string | null
  votedGuestIds: string[]
  myVote: string | null
  /** A vote sent and not yet acknowledged; `target: null` is a withdrawal. */
  pendingVote: { target: string | null } | null
  voteError: string | null
  chat: ImpostorChatLine[]
  chatError: string | null
  voteClosed: ImpostorVoteClosedPayload | null
  guessOpen: ImpostorGuessOpenPayload | null
  guessSubmitted: boolean
  guessError: string | null
  roundEnd: ImpostorRoundEndedPayload | null
}

export const initialImpostorCommon: ImpostorCommonState = {
  role: null,
  rolePhaseId: null,
  votedGuestIds: [],
  myVote: null,
  pendingVote: null,
  voteError: null,
  chat: [],
  chatError: null,
  voteClosed: null,
  guessOpen: null,
  guessSubmitted: false,
  guessError: null,
  roundEnd: null,
}

/** The snapshot fields the shared slice is built from (identical in both games' snapshots). */
export interface ImpostorCommonSnapshotFields {
  phaseId: string
  role: ImpostorRoleInfo | null
  votedGuestIds: string[]
  myVote: string | null
  recentChat: ImpostorChatLine[]
  voteClosed: ImpostorVoteClosedPayload | null
  roundEnd: ImpostorRoundEndedPayload | null
}

export function commonFromSnapshot(snapshot: ImpostorCommonSnapshotFields): ImpostorCommonState {
  return {
    ...initialImpostorCommon,
    role: snapshot.role ?? null,
    rolePhaseId: null,
    votedGuestIds: [...(snapshot.votedGuestIds ?? [])],
    myVote: snapshot.myVote ?? null,
    chat: [...(snapshot.recentChat ?? [])],
    voteClosed: snapshot.voteClosed ?? null,
    roundEnd: snapshot.roundEnd ?? null,
  }
}

/**
 * `ROLE_REVEAL` started: everything of the last round goes. The private `IMPOSTOR_ROLE` follows the
 * public `PHASE_STARTED` (§3.7), but a role already received for this very phase is kept.
 */
export function commonForNewRound(
  state: ImpostorCommonState,
  rolePhaseId: string,
): ImpostorCommonState {
  const keepRole = state.rolePhaseId === rolePhaseId
  return {
    ...initialImpostorCommon,
    role: keepRole ? state.role : null,
    rolePhaseId: keepRole ? state.rolePhaseId : null,
  }
}

export function withRole(
  state: ImpostorCommonState,
  payload: ImpostorRolePayload,
): ImpostorCommonState {
  return {
    ...state,
    role: { role: payload.role, category: payload.category, word: payload.word },
    rolePhaseId: payload.phaseId,
  }
}

export function withChatMessage(
  state: ImpostorCommonState,
  payload: ImpostorChatMessagePayload,
): ImpostorCommonState {
  return {
    ...state,
    chat: [...state.chat.slice(-(MAX_CHAT_LINES - 1)), { ...payload }],
  }
}

/** The voting phase started: nobody has voted yet. */
export function withVoteStarted(state: ImpostorCommonState): ImpostorCommonState {
  return {
    ...state,
    votedGuestIds: [],
    myVote: null,
    pendingVote: null,
    voteError: null,
    chatError: null,
  }
}

export function withVoteProgress(
  state: ImpostorCommonState,
  payload: ImpostorVoteProgressPayload,
): ImpostorCommonState {
  return { ...state, votedGuestIds: [...payload.votedGuestIds] }
}

export function withVoteSent(
  state: ImpostorCommonState,
  target: string | null,
): ImpostorCommonState {
  return { ...state, pendingVote: { target }, voteError: null }
}

export function withVoteClosed(
  state: ImpostorCommonState,
  payload: ImpostorVoteClosedPayload,
): ImpostorCommonState {
  return { ...state, voteClosed: payload, pendingVote: null }
}

export function withGuessOpen(
  state: ImpostorCommonState,
  payload: ImpostorGuessOpenPayload,
): ImpostorCommonState {
  return { ...state, guessOpen: payload, guessSubmitted: false, guessError: null }
}

export function withRoundEnded(
  state: ImpostorCommonState,
  payload: ImpostorRoundEndedPayload,
): ImpostorCommonState {
  return { ...state, roundEnd: payload, pendingVote: null }
}

/** Whether the viewer is the caught impostor who may guess now ([DI-11]). */
export function canGuess(
  state: ImpostorCommonState,
  phase: string | null,
  viewerGuestId: string | null | undefined,
): boolean {
  if (phase !== 'GUESS') return false
  if (state.guessOpen) return true
  return Boolean(
    viewerGuestId && state.voteClosed?.caught && state.voteClosed.impostorGuestId === viewerGuestId,
  )
}

// Command bookkeeping

export interface TrackedImpostorCommand {
  type: string
  /** Phase the command was sent in. */
  phaseId?: string
  strokeId?: string
  /** `IMPOSTOR_VOTE` target (`null` = withdraw). */
  target?: string | null
}

/** State changes after an `ACTION_ACK` of a shared command; `null` for other commands. */
export function commonAck(
  state: ImpostorCommonState,
  command: TrackedImpostorCommand,
): Partial<ImpostorCommonState> | null {
  switch (command.type) {
    case 'IMPOSTOR_VOTE': {
      const target = command.target ?? null
      // A later vote may already be in flight; only that one's ack clears the pending marker.
      const stillPending = state.pendingVote !== null && state.pendingVote.target !== target
      return { myVote: target, pendingVote: stillPending ? state.pendingVote : null }
    }
    case 'IMPOSTOR_GUESS':
      return { guessSubmitted: true, guessError: null }
    case 'IMPOSTOR_CHAT':
      return { chatError: null }
    default:
      return null
  }
}

export const IMPOSTOR_ERROR_COPY = {
  chatRateLimited: 'Easy there — wait a moment before sending another message.',
  chatRejected: 'That message could not be sent.',
  voteRejected: 'That vote did not count. Pick someone still in the game.',
  guessRejected: 'That guess could not be sent. Try again.',
} as const

const RACE_CODES = new Set(['STALE_ACTION', 'INVALID_PHASE', 'DUPLICATE_ACTION'])

/** Inline messages for errors of shared commands; `null` for other commands. */
export function commonError(
  state: ImpostorCommonState,
  command: TrackedImpostorCommand,
  payload: GameErrorPayload,
): { patch: Partial<ImpostorCommonState>; outcome: CommandErrorOutcome } | null {
  const raced = RACE_CODES.has(payload.code)
  switch (command.type) {
    case 'IMPOSTOR_CHAT':
      // A chat line that lost the race with the end of voting has nowhere to go any more.
      if (raced) return { patch: {}, outcome: 'handled' }
      return {
        patch: {
          chatError:
            payload.code === 'RATE_LIMITED'
              ? IMPOSTOR_ERROR_COPY.chatRateLimited
              : IMPOSTOR_ERROR_COPY.chatRejected,
        },
        outcome: 'handled',
      }
    case 'IMPOSTOR_VOTE': {
      const ours = state.pendingVote?.target === (command.target ?? null)
      return {
        patch: {
          pendingVote: ours ? null : state.pendingVote,
          voteError: raced ? null : IMPOSTOR_ERROR_COPY.voteRejected,
        },
        outcome: 'handled',
      }
    }
    case 'IMPOSTOR_GUESS':
      if (payload.code === 'ALREADY_SUBMITTED') {
        return { patch: { guessSubmitted: true, guessError: null }, outcome: 'handled' }
      }
      if (raced) return { patch: {}, outcome: 'handled' }
      return { patch: { guessError: IMPOSTOR_ERROR_COPY.guessRejected }, outcome: 'handled' }
    default:
      return null
  }
}
