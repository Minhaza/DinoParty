import { beforeEach, describe, expect, it } from 'vitest'

import { useDinoImpostorStore } from './dinoImpostorStore'
import {
  ARTIST_ROLE,
  drawingFullCanvas,
  drawingMyTurnArtist,
  drawingOtherTurnArtist,
  drawingStarted,
  guessOpenEvent,
  PHASE_DI_ROLE,
  PHASE_DI_TURN,
  PHASE_DI_VOTE,
  roleRevealSpectator,
  roleRevealStarted,
  rolePayload,
  roundEndedPayload,
  strokeBatches,
  VIEWER_ARTIST,
  voteArtist,
  voteClosedCaught,
  voteStarted,
} from './fixtures'

const store = () => useDinoImpostorStore.getState()
const err = (code: string, actionId: string) => ({
  actionId,
  code,
  message: 'nope',
  retryable: false,
})

describe('dinoImpostorStore', () => {
  beforeEach(() => store().reset())

  it('replaces its whole state with each snapshot', () => {
    store().applySnapshot(voteArtist)
    expect(store()).toMatchObject({
      round: 2,
      laps: 2,
      myVote: 'di-g4',
      role: ARTIST_ROLE,
    })
    expect(store().strokes).toHaveLength(20)
    expect(store().chat).toHaveLength(3)

    store().applySnapshot(drawingOtherTurnArtist)
    expect(store().myVote).toBeNull()
    expect(store().chat).toEqual([])
    expect(store().strokes).toHaveLength(3)
    expect(store().drawerGuestId).toBe(drawingOtherTurnArtist.drawerGuestId)
    expect(store().strokes[0].authorGuestId).toBe(drawingOtherTurnArtist.seatOrder[0])

    store().applySnapshot(roleRevealSpectator)
    expect(store().role).toBeNull()

    store().applySnapshot(null)
    expect(store().strokes).toEqual([])
    expect(store().seatOrder).toEqual([])
  })

  it('marks the canvas full from the snapshot budget', () => {
    store().applySnapshot({
      ...drawingFullCanvas,
      canvasBudget: { points: 11900, maxPoints: 12000, strokes: 19, maxStrokes: 300 },
    })
    expect(store().canvasFull).toBe(true)
  })

  it('DI4_starts a round on ROLE_REVEAL and takes the private role around it', () => {
    store().applySnapshot(voteArtist)
    store().applyPhaseStarted(roleRevealStarted)
    expect(store().strokes).toEqual([])
    expect(store().role).toBeNull()
    expect(store().myVote).toBeNull()
    store().applyRole(rolePayload(ARTIST_ROLE))
    expect(store().role).toEqual(ARTIST_ROLE)

    // The role may be processed before the public PHASE_STARTED of its phase.
    store().reset()
    store().applyRole(rolePayload(ARTIST_ROLE))
    store().applyPhaseStarted(roleRevealStarted)
    expect(store().role).toEqual(ARTIST_ROLE)
    expect(store().round).toBe(2)
  })

  it('DI6_follows the turns and asks for a snapshot when the colours are unknown', () => {
    expect(store().applyPhaseStarted(drawingStarted(0))).toBe(true)
    store().applySnapshot(drawingOtherTurnArtist)
    expect(store().applyPhaseStarted(drawingStarted(4))).toBe(false)
    expect(store()).toMatchObject({ turn: 4, lap: 1, totalTurns: 20 })
    expect(store().drawerGuestId).toBe(drawingStarted(4).drawerGuestId)
  })

  it('G1_takes seatOrder and colors on ROLE_REVEAL phase started', () => {
    store().reset()
    expect(store().colors).toEqual({})
    store().applyPhaseStarted(roleRevealStarted)
    expect(Object.keys(store().colors).length).toBeGreaterThan(0)
    expect(store().seatOrder.length).toBeGreaterThan(0)
    // Now that colors are known, drawingStarted does not ask for a snapshot
    expect(store().applyPhaseStarted(drawingStarted(0))).toBe(false)
  })

  it('G2_retains snapshot nicknames and accumulates remembered nicknames', () => {
    store().applySnapshot(drawingOtherTurnArtist)
    expect(store().nicknames['di-g1']).toBe('Rexy')

    store().rememberNicknames([
      { guestId: 'di-g1', nickname: 'Rexy' },
      { guestId: 'new-guest', nickname: 'Newbie' },
    ])
    expect(store().nicknames['new-guest']).toBe('Newbie')
    expect(store().nicknames['di-g1']).toBe('Rexy')
  })

  it('G4_handles turnStroke on reconnect: open resumes, closed locks turn', () => {
    // Open stroke: drawer resumes it
    store().applySnapshot({
      ...drawingMyTurnArtist,
      strokes: [
        {
          strokeId: 'reconnect-stroke',
          tool: 'PENCIL',
          color: '#0072B2',
          size: 2,
          points: [
            [1, 1],
            [2, 2],
          ],
          closed: false,
          authorGuestId: VIEWER_ARTIST,
        },
      ],
      turnStroke: { strokeId: 'reconnect-stroke', nextBatchNo: 2, closed: false },
    })
    expect(store().resumeStroke).toEqual({
      strokeId: 'reconnect-stroke',
      nextBatchNo: 2,
      pointCount: 2,
    })
    expect(store().localStrokeIds).toContain('reconnect-stroke')

    // Closed stroke: turn is locked
    store().applySnapshot({
      ...drawingMyTurnArtist,
      turnStroke: { strokeId: 'finished-stroke', nextBatchNo: 3, closed: true },
    })
    expect(store().lockedTurnPhaseId).toBe(drawingMyTurnArtist.phaseId)
    expect(store().resumeStroke).toBeNull()
  })

  it('DI6_applies live batches with their author and dedupes them', () => {
    store().applySnapshot(drawingOtherTurnArtist)
    const [b0, b1, b2] = strokeBatches('di-g5')
    store().applyDrawingUpdated(b0)
    store().applyDrawingUpdated(b1)
    store().applyDrawingUpdated(b1)
    const live = store().strokes[store().strokes.length - 1]
    expect(live).toMatchObject({ authorGuestId: 'di-g5', closed: false })
    expect(live.points).toHaveLength(4)
    store().applyDrawingUpdated(b2)
    expect(store().strokes[store().strokes.length - 1].closed).toBe(true)
    expect(store().strokes).toHaveLength(4)
  })

  it('DI6_ignores the echo of its own stroke apart from closing it', () => {
    store().applySnapshot(drawingMyTurnArtist)
    store().addLocalStroke({
      strokeId: 'mine',
      tool: 'PENCIL',
      color: '#2F2F2F',
      size: 2,
      points: [
        [1, 1],
        [2, 2],
      ],
      closed: false,
      authorGuestId: VIEWER_ARTIST,
    })
    for (const batch of strokeBatches(VIEWER_ARTIST, 'mine')) store().applyDrawingUpdated(batch)
    const mine = store().strokes.find((s) => s.strokeId === 'mine')
    expect(mine?.points).toHaveLength(2)
    expect(mine?.closed).toBe(true)
  })

  it('DI9_tracks the vote, chat, result, guess and reveal', () => {
    store().applySnapshot(drawingOtherTurnArtist)
    store().applyPhaseStarted(voteStarted)
    store().applyChatMessage({ guestId: 'di-g2', text: 'hm', ts: 't' })
    store().applyVoteProgress({ phaseId: PHASE_DI_VOTE, votedGuestIds: ['di-g2'] })
    expect(store().chat).toHaveLength(1)
    expect(store().votedGuestIds).toEqual(['di-g2'])

    store().trackCommand('vote-1', { type: 'IMPOSTOR_VOTE', target: 'di-g4' })
    expect(store().pendingVote).toEqual({ target: 'di-g4' })
    store().applyActionAck('vote-1')
    expect(store().myVote).toBe('di-g4')
    expect(store().pendingVote).toBeNull()

    store().applyVoteClosed(voteClosedCaught)
    expect(store().voteClosed?.caught).toBe(true)
    store().applyGuessOpen(guessOpenEvent)
    expect(store().guessOpen).toEqual(guessOpenEvent)
    store().applyRoundEnded(roundEndedPayload('CAUGHT'))
    expect(store().roundEnd?.outcome).toBe('CAUGHT')
  })

  it('locks the turn and resyncs when the server refuses a stroke', () => {
    store().applySnapshot(drawingMyTurnArtist)
    const phaseId = PHASE_DI_TURN(9)
    store().trackCommand('s-1', { type: 'IMPOSTOR_DRAW_STROKE', strokeId: 'x', phaseId })
    expect(store().applyCommandError(err('ALREADY_SUBMITTED', 's-1'))).toBe('resync')
    expect(store().lockedTurnPhaseId).toBe(phaseId)

    store().trackCommand('s-2', { type: 'IMPOSTOR_DRAW_STROKE', strokeId: 'y', phaseId })
    expect(store().applyCommandError(err('VALIDATION_FAILED', 's-2'))).toBe('resync')
    expect(store().strokeRejection).toMatchObject({ strokeId: 'y', kind: 'RESTART' })

    store().trackCommand('s-3', { type: 'IMPOSTOR_DRAW_STROKE', strokeId: 'z', phaseId })
    expect(store().applyCommandError(err('STALE_ACTION', 's-3'))).toBe('handled')

    store().trackCommand('s-4', { type: 'IMPOSTOR_DRAW_STROKE', strokeId: 'w', phaseId })
    expect(store().applyCommandError(err('PAYLOAD_TOO_LARGE', 's-4'))).toBe('resync')
    expect(store().canvasFull).toBe(true)

    // A lock survives a resync snapshot of the same turn, not of the next one.
    store().applySnapshot(drawingMyTurnArtist)
    expect(store().lockedTurnPhaseId).toBe(phaseId)
    store().applySnapshot(drawingOtherTurnArtist)
    expect(store().lockedTurnPhaseId).toBeNull()
  })

  it('shows chat errors inline and leaves unknown actionIds to other stores', () => {
    store().trackCommand('c-1', { type: 'IMPOSTOR_CHAT' })
    expect(store().applyCommandError(err('RATE_LIMITED', 'c-1'))).toBe('handled')
    expect(store().chatError).toMatch(/moment/i)
    expect(store().applyCommandError(err('RATE_LIMITED', 'someone-else'))).toBe('unhandled')

    // The next send clears it, so a second RATE_LIMITED is a fresh error the chat rests on again.
    store().trackCommand('c-2', { type: 'IMPOSTOR_CHAT' })
    expect(store().chatError).toBeNull()
  })

  it('keeps the role phase id so a late PHASE_STARTED does not drop the role', () => {
    store().applyRole(rolePayload(ARTIST_ROLE))
    expect(store().rolePhaseId).toBe(PHASE_DI_ROLE)
  })
})
