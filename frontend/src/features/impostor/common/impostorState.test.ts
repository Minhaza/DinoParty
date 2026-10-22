import { describe, expect, it } from 'vitest'

import {
  canGuess,
  commonAck,
  commonError,
  commonForNewRound,
  commonFromSnapshot,
  initialImpostorCommon,
  MAX_CHAT_LINES,
  withChatMessage,
  withGuessOpen,
  withRole,
  withRoundEnded,
  withVoteClosed,
  withVoteProgress,
  withVoteSent,
  withVoteStarted,
} from './impostorState'
import type { ImpostorRoundEndedPayload, ImpostorVoteClosedPayload } from './types'

const category = { key: 'animals', label: 'Animals' }
const closed: ImpostorVoteClosedPayload = {
  round: 1,
  votes: { a: 'imp', b: 'imp', imp: 'a' },
  accusedGuestId: 'imp',
  caught: true,
  impostorGuestId: 'imp',
}
const ended: ImpostorRoundEndedPayload = {
  round: 1,
  outcome: 'CAUGHT',
  impostorGuestId: 'imp',
  category,
  word: 'Turtle',
  decoyWord: null,
  votes: closed.votes,
  guess: 'Frog',
  scoreDeltas: { a: 2, b: 2, imp: 0 },
  scores: { a: 2, b: 2, imp: 0 },
}
const err = (code: string) => ({ actionId: 'x', code, message: 'server says no', retryable: false })

describe('impostor shared state helpers', () => {
  it('builds the shared slice from a snapshot, replacing everything', () => {
    const dirty = {
      ...initialImpostorCommon,
      chatError: 'old',
      pendingVote: { target: 'b' },
      guessSubmitted: true,
    }
    const next = commonFromSnapshot({
      phaseId: 'p-vote',
      role: { role: 'ARTIST', category, word: 'Turtle' },
      votedGuestIds: ['a'],
      myVote: 'b',
      recentChat: [{ guestId: 'a', text: 'hm', ts: 't' }],
      voteClosed: null,
      roundEnd: null,
    })
    expect(dirty.chatError).toBe('old')
    expect(next).toMatchObject({
      role: { word: 'Turtle' },
      votedGuestIds: ['a'],
      myVote: 'b',
      pendingVote: null,
      chatError: null,
      guessSubmitted: false,
    })
    expect(next.chat).toHaveLength(1)
  })

  it('DI4_a new round clears the last one but keeps a role that already arrived for it', () => {
    const withOldRound = withRoundEnded(
      withRole(initialImpostorCommon, {
        phaseId: 'p-role-1',
        round: 1,
        role: 'ARTIST',
        category,
        word: 'Turtle',
      }),
      ended,
    )
    const fresh = commonForNewRound(withOldRound, 'p-role-2')
    expect(fresh.role).toBeNull()
    expect(fresh.roundEnd).toBeNull()

    const early = withRole(initialImpostorCommon, {
      phaseId: 'p-role-2',
      round: 2,
      role: 'IMPOSTOR',
      category,
      word: null,
    })
    expect(commonForNewRound(early, 'p-role-2').role?.role).toBe('IMPOSTOR')
  })

  it('DI8_keeps a bounded chat log', () => {
    let state = initialImpostorCommon
    for (let i = 0; i < MAX_CHAT_LINES + 5; i++) {
      state = withChatMessage(state, { guestId: 'a', text: `m${i}`, ts: `t${i}` })
    }
    expect(state.chat).toHaveLength(MAX_CHAT_LINES)
    expect(state.chat[state.chat.length - 1].text).toBe(`m${MAX_CHAT_LINES + 4}`)
  })

  it('DI9_tracks voted names, a pending vote and the closed result', () => {
    let state = withVoteStarted({ ...initialImpostorCommon, myVote: 'stale' })
    expect(state.myVote).toBeNull()
    state = withVoteProgress(state, { phaseId: 'p', votedGuestIds: ['a', 'b'] })
    expect(state.votedGuestIds).toEqual(['a', 'b'])
    state = withVoteSent(state, 'imp')
    expect(state.pendingVote).toEqual({ target: 'imp' })
    state = withVoteClosed(state, closed)
    expect(state.voteClosed?.accusedGuestId).toBe('imp')
    expect(state.pendingVote).toBeNull()
  })

  it('DI11_only the caught impostor may guess', () => {
    const state = withVoteClosed(initialImpostorCommon, closed)
    expect(canGuess(state, 'GUESS', 'imp')).toBe(true)
    expect(canGuess(state, 'GUESS', 'a')).toBe(false)
    expect(canGuess(state, 'VOTE', 'imp')).toBe(false)
    const escaped = withVoteClosed(initialImpostorCommon, {
      ...closed,
      caught: false,
      impostorGuestId: null,
      accusedGuestId: null,
    })
    expect(canGuess(escaped, 'GUESS', 'imp')).toBe(false)
    // The private guess notice alone is enough (Sketchy Saurus unaware impostor, §3.12).
    const opened = withGuessOpen(initialImpostorCommon, {
      phaseId: 'p-guess',
      round: 1,
      category,
      deadlineAt: 'd',
    })
    expect(canGuess(opened, 'GUESS', 'someone')).toBe(true)
  })

  it('applies acks: a vote becomes mine, a guess is locked in, chat errors clear', () => {
    const pending = withVoteSent(initialImpostorCommon, 'imp')
    expect(commonAck(pending, { type: 'IMPOSTOR_VOTE', target: 'imp' })).toMatchObject({
      myVote: 'imp',
      pendingVote: null,
    })
    expect(commonAck(pending, { type: 'IMPOSTOR_VOTE', target: null })).toMatchObject({
      myVote: null,
    })
    expect(commonAck(initialImpostorCommon, { type: 'IMPOSTOR_GUESS' })).toMatchObject({
      guessSubmitted: true,
    })
    expect(
      commonAck({ ...initialImpostorCommon, chatError: 'x' }, { type: 'IMPOSTOR_CHAT' }),
    ).toMatchObject({ chatError: null })
    expect(commonAck(initialImpostorCommon, { type: 'IMPOSTOR_DRAW_STROKE' })).toBeNull()
  })

  it('maps errors of the shared commands to inline messages', () => {
    const limited = commonError(
      initialImpostorCommon,
      { type: 'IMPOSTOR_CHAT' },
      err('RATE_LIMITED'),
    )
    expect(limited?.outcome).toBe('handled')
    expect(limited?.patch.chatError).toMatch(/moment/i)

    const vote = commonError(
      withVoteSent(initialImpostorCommon, 'b'),
      { type: 'IMPOSTOR_VOTE', target: 'b' },
      err('VALIDATION_FAILED'),
    )
    expect(vote?.patch).toMatchObject({ pendingVote: null })
    expect(vote?.patch.voteError).toBeTruthy()

    const again = commonError(
      initialImpostorCommon,
      { type: 'IMPOSTOR_GUESS' },
      err('ALREADY_SUBMITTED'),
    )
    expect(again?.patch).toMatchObject({ guessSubmitted: true, guessError: null })

    const stale = commonError(initialImpostorCommon, { type: 'IMPOSTOR_VOTE' }, err('STALE_ACTION'))
    expect(stale?.outcome).toBe('handled')
    expect(stale?.patch.voteError ?? null).toBeNull()

    expect(
      commonError(initialImpostorCommon, { type: 'IMPOSTOR_DRAW_STROKE' }, err('X')),
    ).toBeNull()
  })
})
