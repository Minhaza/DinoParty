import { beforeEach, describe, expect, it } from 'vitest'

import {
  ARTIST_ROLE,
  galleryStarted,
  guessOpenFixture,
  guessStarted,
  rolePayload,
  roleRevealStarted,
  roundEndedCaughtWrongGuess,
  roundEndStarted,
  sampleStroke,
  sketchStarted,
  sketchyGalleryOpened,
  sketchyProgress,
  sketchySnapshot,
  UNAWARE_IMPOSTOR_ROLE,
  VIEWER_ARTIST,
  voteClosedImpostorCaught,
} from './fixtures'
import { useSketchySaurusStore } from './sketchySaurusStore'

describe('sketchySaurusStore', () => {
  const store = () => useSketchySaurusStore.getState()

  beforeEach(() => {
    store().reset()
  })

  it('replaces whole state with each snapshot', () => {
    const snap = sketchySnapshot('SKETCH', ARTIST_ROLE)
    store().applySnapshot(snap)

    expect(store().strokes).toHaveLength(1)
    expect(store().round).toBe(1)
    expect(store().role).toEqual(ARTIST_ROLE)

    store().applySnapshot(null)
    expect(store().strokes).toEqual([])
    expect(store().role).toBeNull()
  })

  it('SS1_starts a round on ROLE_REVEAL and takes the private role', () => {
    store().applyPhaseStarted(roleRevealStarted)
    expect(store().strokes).toEqual([])
    expect(store().submitted).toBe(false)
    expect(store().role).toBeNull()

    store().applyRole(rolePayload(ARTIST_ROLE))
    expect(store().role).toEqual(ARTIST_ROLE)
  })

  it('SS3_unaware impostor receives artist role shape with decoy word', () => {
    store().applyPhaseStarted(roleRevealStarted)
    store().applyRole(rolePayload(UNAWARE_IMPOSTOR_ROLE))
    expect(store().role).toEqual({
      role: 'ARTIST',
      category: { key: 'fossils', label: 'Fossils' },
      word: 'Trilobite',
    })
  })

  it('SS2_manages private sketch draft during SKETCH: add, append, undo, clear', () => {
    store().applyPhaseStarted(sketchStarted)
    expect(store().submitted).toBe(false)

    const s1 = sampleStroke(VIEWER_ARTIST, 's1')
    store().addLocalStroke(s1)
    expect(store().strokes).toHaveLength(1)

    store().appendLocalPoints('s1', [[4000, 4000]], false)
    expect(store().strokes[0].points).toHaveLength(4)

    const s2 = sampleStroke(VIEWER_ARTIST, 's2')
    store().addLocalStroke(s2)
    expect(store().strokes).toHaveLength(2)

    store().undoLocalStroke()
    expect(store().strokes).toHaveLength(1)
    expect(store().strokes[0].strokeId).toBe('s1')

    store().clearLocalStrokes()
    expect(store().strokes).toHaveLength(0)
  })

  it('SS2_tracks submitted state and progress', () => {
    store().applyPhaseStarted(sketchStarted)
    expect(store().submittedGuestIds).toEqual([])

    store().applyProgress(sketchyProgress)
    expect(store().submittedGuestIds).toEqual(['ss-g1', 'ss-g3'])

    // Tracking a submit command
    store().trackCommand('cmd-sub-1', { type: 'SKETCHY_SUBMIT' })
    store().applyActionAck('cmd-sub-1')
    expect(store().submitted).toBe(true)
  })

  it('SS2_marks submitted on ALREADY_SUBMITTED error', () => {
    store().applyPhaseStarted(sketchStarted)
    store().trackCommand('cmd-sub-2', { type: 'SKETCHY_SUBMIT' })
    store().applyCommandError(
      { code: 'ALREADY_SUBMITTED', message: 'Already submitted', retryable: false },
      'cmd-sub-2',
    )
    expect(store().submitted).toBe(true)
  })

  it('SS2_handles budget exhaustion and stroke rejection on PAYLOAD_TOO_LARGE', () => {
    store().applyPhaseStarted(sketchStarted)
    store().trackCommand('cmd-stroke-1', { type: 'SKETCHY_DRAW_STROKE', strokeId: 's1' })
    const outcome = store().applyCommandError(
      { code: 'PAYLOAD_TOO_LARGE', message: 'Max points exceeded', retryable: false },
      'cmd-stroke-1',
    )
    expect(outcome).toBe('resync')
    expect(store().canvasFull).toBe(true)
    expect(store().strokeRejection?.kind).toBe('FULL')
  })

  it('SS3_opens GALLERY and populates drawings on SKETCHY_GALLERY_OPENED', () => {
    store().applyPhaseStarted(galleryStarted)
    store().applyGalleryOpened(sketchyGalleryOpened)

    expect(store().gallery).toHaveLength(5)
    expect(store().gallery?.[3].empty).toBe(true)
    expect(store().gallery?.[0].guestId).toBe('ss-g1')
  })

  it('handles voting, chat, vote closed, guess open, and round ended', () => {
    store().applyPhaseStarted(galleryStarted)

    // Chat
    store().applyChatMessage({
      guestId: 'ss-g1',
      text: 'Suspicious drawing!',
      ts: '2099-01-01T00:00:00Z',
    })
    expect(store().chat).toHaveLength(1)

    // Vote
    store().trackCommand('cmd-vote-1', { type: 'IMPOSTOR_VOTE', target: 'ss-g2' })
    expect(store().pendingVote).toEqual({ target: 'ss-g2' })
    store().applyActionAck('cmd-vote-1')
    expect(store().myVote).toBe('ss-g2')
    expect(store().pendingVote).toBeNull()

    // Vote Closed
    store().applyVoteClosed(voteClosedImpostorCaught)
    expect(store().voteClosed?.caught).toBe(true)

    // Guess
    store().applyPhaseStarted(guessStarted)
    store().applyGuessOpen(guessOpenFixture)
    expect(store().guessOpen?.category.label).toBe('Fossils')

    // Round End
    store().applyPhaseStarted(roundEndStarted)
    store().applyRoundEnded(roundEndedCaughtWrongGuess)
    expect(store().roundEnd?.outcome).toBe('CAUGHT')
    expect(store().roundEnd?.decoyWord).toBe('Trilobite')
  })

  it('retains snapshot nicknames and updates with rememberNicknames', () => {
    store().applySnapshot(sketchySnapshot('GALLERY'))
    expect(store().nicknames['ss-g1']).toBe('Rexy')

    store().rememberNicknames([
      { guestId: 'ss-g1', nickname: 'Rexy' },
      { guestId: 'ss-extra', nickname: 'ExtraDino' },
    ])
    expect(store().nicknames['ss-extra']).toBe('ExtraDino')
  })
})
