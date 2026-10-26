import { beforeEach, describe, expect, it } from 'vitest'
import { useSketchySaurusStore } from './sketchySaurusStore'
import { REAL_SKETCHY_SAURUS_PAYLOADS } from './realPayloads.fixture'
import type {
  SketchySaurusSnapshot,
  SketchySaurusPhaseStartedPayload,
  SketchyProgressPayload,
  SketchyGalleryOpenedPayload,
} from './types'
import type {
  ImpostorGuessOpenPayload,
  ImpostorRolePayload,
  ImpostorRoundEndedPayload,
  ImpostorVoteClosedPayload,
  ImpostorVoteProgressPayload,
} from '../common/types'

describe('Sketchy Saurus with Real Recorded Backend Payloads', () => {
  beforeEach(() => {
    useSketchySaurusStore.getState().reset()
  })

  describe('Real Snapshots from Spring Boot Backend', () => {
    const { snapshots } = REAL_SKETCHY_SAURUS_PAYLOADS

    it('processes real ROLE_REVEAL snapshot correctly', () => {
      const snap = snapshots.ROLE_REVEAL.game as unknown as SketchySaurusSnapshot
      useSketchySaurusStore.getState().applySnapshot(snap)

      const state = useSketchySaurusStore.getState()
      expect(state.round).toBe(snap.round)
      expect(state.nicknames).toEqual(snap.nicknames)
      expect(state.role).toEqual(snap.role)
    })

    it('processes real SKETCH snapshot correctly', () => {
      const snap = snapshots.SKETCH.game as unknown as SketchySaurusSnapshot
      useSketchySaurusStore.getState().applySnapshot(snap)

      const state = useSketchySaurusStore.getState()
      expect(state.submitted).toBe(snap.submitted)
      expect(state.submittedGuestIds).toEqual(snap.submittedGuestIds)
      expect(state.strokes).toHaveLength(snap.draft?.strokes.length ?? 0)
    })

    it('processes real GALLERY snapshot correctly', () => {
      const snap = snapshots.GALLERY.game as unknown as SketchySaurusSnapshot
      useSketchySaurusStore.getState().applySnapshot(snap)

      const state = useSketchySaurusStore.getState()
      expect(state.gallery).toBeDefined()
      expect(state.votedGuestIds).toEqual(snap.votedGuestIds)
    })

    it('processes real GUESS snapshot correctly', () => {
      const snap = snapshots.GUESS.game as unknown as SketchySaurusSnapshot
      useSketchySaurusStore.getState().applySnapshot(snap)

      const state = useSketchySaurusStore.getState()
      expect(state.guessOpen).toBeDefined()
    })

    it('processes real ROUND_END snapshot correctly', () => {
      const snap = snapshots.ROUND_END.game as unknown as SketchySaurusSnapshot
      useSketchySaurusStore.getState().applySnapshot(snap)

      const state = useSketchySaurusStore.getState()
      expect(state.roundEnd).toBeDefined()
      expect(state.roundEnd?.outcome).toBe(snap.roundEnd?.outcome)
    })
  })

  describe('Real STOMP Events from Spring Boot Backend', () => {
    const { uniquePayloads } = REAL_SKETCHY_SAURUS_PAYLOADS

    it('processes real PHASE_STARTED_ROLE_REVEAL event with nicknames (G8)', () => {
      const ev = uniquePayloads.PHASE_STARTED_ROLE_REVEAL
      const resync = useSketchySaurusStore
        .getState()
        .applyPhaseStarted(ev.payload as unknown as SketchySaurusPhaseStartedPayload)

      const state = useSketchySaurusStore.getState()
      expect(state.nicknames).toEqual(
        (ev.payload as unknown as { nicknames: Record<string, string> }).nicknames,
      )
      expect(resync).toBe(false)
    })

    it('processes real IMPOSTOR_ROLE private event', () => {
      const ev = uniquePayloads.IMPOSTOR_ROLE
      useSketchySaurusStore.getState().applyRole(ev.payload as unknown as ImpostorRolePayload)

      const state = useSketchySaurusStore.getState()
      expect(state.role).toEqual({
        role: ev.payload.role,
        category: ev.payload.category,
        word: ev.payload.word,
      })
    })

    it('processes real PHASE_STARTED_SKETCH event', () => {
      const ev = uniquePayloads.PHASE_STARTED_SKETCH
      useSketchySaurusStore
        .getState()
        .applyPhaseStarted(ev.payload as unknown as SketchySaurusPhaseStartedPayload)

      const state = useSketchySaurusStore.getState()
      expect(state.submitted).toBe(false)
      expect(state.submittedGuestIds).toEqual([])
    })

    it('processes real SKETCHY_PROGRESS event', () => {
      const ev = uniquePayloads.SKETCHY_PROGRESS
      useSketchySaurusStore
        .getState()
        .applyProgress(ev.payload as unknown as SketchyProgressPayload)

      const state = useSketchySaurusStore.getState()
      expect(state.submittedGuestIds).toEqual(ev.payload.submittedGuestIds)
    })

    it('processes real PHASE_STARTED_GALLERY event', () => {
      const ev = uniquePayloads.PHASE_STARTED_GALLERY
      useSketchySaurusStore
        .getState()
        .applyPhaseStarted(ev.payload as unknown as SketchySaurusPhaseStartedPayload)

      const state = useSketchySaurusStore.getState()
      expect(state.votedGuestIds).toEqual([])
    })

    it('processes real SKETCHY_GALLERY_OPENED event', () => {
      const ev = uniquePayloads.SKETCHY_GALLERY_OPENED
      useSketchySaurusStore
        .getState()
        .applyGalleryOpened(ev.payload as unknown as SketchyGalleryOpenedPayload)

      const state = useSketchySaurusStore.getState()
      expect(state.gallery).toHaveLength(ev.payload.drawings.length)
    })

    it('processes real IMPOSTOR_VOTE_PROGRESS event', () => {
      const ev = uniquePayloads.IMPOSTOR_VOTE_PROGRESS
      useSketchySaurusStore
        .getState()
        .applyVoteProgress(ev.payload as unknown as ImpostorVoteProgressPayload)

      const state = useSketchySaurusStore.getState()
      expect(state.votedGuestIds).toEqual(ev.payload.votedGuestIds)
    })

    it('processes real IMPOSTOR_VOTE_CLOSED event', () => {
      const ev = uniquePayloads.IMPOSTOR_VOTE_CLOSED
      useSketchySaurusStore
        .getState()
        .applyVoteClosed(ev.payload as unknown as ImpostorVoteClosedPayload)

      const state = useSketchySaurusStore.getState()
      expect(state.voteClosed).toEqual(ev.payload)
    })

    it('processes real IMPOSTOR_GUESS_OPEN private event', () => {
      const ev = uniquePayloads.IMPOSTOR_GUESS_OPEN
      useSketchySaurusStore
        .getState()
        .applyGuessOpen(ev.payload as unknown as ImpostorGuessOpenPayload)

      const state = useSketchySaurusStore.getState()
      expect(state.guessOpen).toEqual(ev.payload)
    })

    it('processes real IMPOSTOR_ROUND_ENDED event', () => {
      const ev = uniquePayloads.IMPOSTOR_ROUND_ENDED
      useSketchySaurusStore
        .getState()
        .applyRoundEnded(ev.payload as unknown as ImpostorRoundEndedPayload)

      const state = useSketchySaurusStore.getState()
      expect(state.roundEnd).toEqual(ev.payload)
      expect(state.roundEnd?.outcome).toBe('GUESSED')
    })
  })

  describe('Regressions', () => {
    it('handles budget rejection PAYLOAD_TOO_LARGE and blocks further drawing', () => {
      const actionId = crypto.randomUUID()
      useSketchySaurusStore
        .getState()
        .trackCommand(actionId, { type: 'SKETCHY_DRAW_STROKE', strokeId: 'str-ss-1' })

      const outcome = useSketchySaurusStore.getState().applyCommandError(
        {
          code: 'PAYLOAD_TOO_LARGE',
          message: 'Drawing budget exhausted: max 1000 points, 60 strokes',
          retryable: false,
        },
        actionId,
      )

      expect(outcome).toBe('resync')
      expect(useSketchySaurusStore.getState().canvasFull).toBe(true)
      expect(useSketchySaurusStore.getState().strokeRejection?.kind).toBe('FULL')
    })
  })
})
