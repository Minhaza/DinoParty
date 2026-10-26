import { beforeEach, describe, expect, it } from 'vitest'
import { useDinoImpostorStore } from './dinoImpostorStore'
import { REAL_DINO_IMPOSTOR_PAYLOADS } from './realPayloads.fixture'
import type {
  DinoImpostorSnapshot,
  DinoImpostorPhaseStartedPayload,
  ImpostorDrawingUpdatedPayload,
  ImpostorGuessOpenPayload,
  ImpostorRolePayload,
  ImpostorRoundEndedPayload,
  ImpostorVoteClosedPayload,
  ImpostorVoteProgressPayload,
} from '../common/types'

describe('Dino Impostor with Real Recorded Backend Payloads', () => {
  beforeEach(() => {
    useDinoImpostorStore.getState().reset()
  })

  describe('Real Snapshots from Spring Boot Backend', () => {
    const { snapshots } = REAL_DINO_IMPOSTOR_PAYLOADS

    it('processes real ROLE_REVEAL snapshot correctly', () => {
      const snap = snapshots.ROLE_REVEAL.game as unknown as DinoImpostorSnapshot
      useDinoImpostorStore.getState().applySnapshot(snap)

      const state = useDinoImpostorStore.getState()
      expect(state.round).toBe(snap.round)
      expect(state.seatOrder).toEqual(snap.seatOrder)
      expect(state.nicknames).toEqual(snap.nicknames)
      expect(state.colors).toEqual(snap.colors)
      expect(state.role).toEqual(snap.role)
    })

    it('processes real DRAWING snapshot correctly', () => {
      const snap = snapshots.DRAWING.game as unknown as DinoImpostorSnapshot
      useDinoImpostorStore.getState().applySnapshot(snap)

      const state = useDinoImpostorStore.getState()
      expect(state.turn).toBe(snap.turn)
      expect(state.drawerGuestId).toBe(snap.drawerGuestId)
      expect(state.strokes).toHaveLength(snap.strokes.length)
    })

    it('processes real VOTE snapshot correctly', () => {
      const snap = snapshots.VOTE.game as unknown as DinoImpostorSnapshot
      useDinoImpostorStore.getState().applySnapshot(snap)

      const state = useDinoImpostorStore.getState()
      expect(state.votedGuestIds).toEqual(snap.votedGuestIds)
      expect(state.myVote).toBe(snap.myVote)
    })

    it('processes real GUESS snapshot correctly', () => {
      const snap = snapshots.GUESS.game as unknown as DinoImpostorSnapshot
      useDinoImpostorStore.getState().applySnapshot(snap)

      const state = useDinoImpostorStore.getState()
      expect(state.guessOpen).toBeDefined()
    })

    it('processes real ROUND_END snapshot correctly', () => {
      const snap = snapshots.ROUND_END.game as unknown as DinoImpostorSnapshot
      useDinoImpostorStore.getState().applySnapshot(snap)

      const state = useDinoImpostorStore.getState()
      expect(state.roundEnd).toBeDefined()
      expect(state.roundEnd?.outcome).toBe(snap.roundEnd?.outcome)
    })
  })

  describe('Real STOMP Events from Spring Boot Backend', () => {
    const { uniquePayloads } = REAL_DINO_IMPOSTOR_PAYLOADS

    it('processes real PHASE_STARTED_ROLE_REVEAL event with nicknames (G8)', () => {
      const ev = uniquePayloads.PHASE_STARTED_ROLE_REVEAL
      const resync = useDinoImpostorStore
        .getState()
        .applyPhaseStarted(ev.payload as unknown as DinoImpostorPhaseStartedPayload)

      const state = useDinoImpostorStore.getState()
      expect(state.nicknames).toEqual(
        (ev.payload as unknown as { nicknames: Record<string, string> }).nicknames,
      )
      expect(resync).toBe(false)
    })

    it('processes real IMPOSTOR_ROLE private event', () => {
      const ev = uniquePayloads.IMPOSTOR_ROLE
      useDinoImpostorStore.getState().applyRole(ev.payload as unknown as ImpostorRolePayload)

      const state = useDinoImpostorStore.getState()
      expect(state.role).toEqual({
        role: ev.payload.role,
        category: ev.payload.category,
        word: ev.payload.word,
      })
    })

    it('processes real PHASE_STARTED_DRAWING event', () => {
      const snap = REAL_DINO_IMPOSTOR_PAYLOADS.snapshots.ROLE_REVEAL
        .game as unknown as DinoImpostorSnapshot
      useDinoImpostorStore.getState().applySnapshot(snap)

      const ev = uniquePayloads.PHASE_STARTED_DRAWING
      const resync = useDinoImpostorStore
        .getState()
        .applyPhaseStarted(ev.payload as unknown as DinoImpostorPhaseStartedPayload)

      const state = useDinoImpostorStore.getState()
      expect(resync).toBe(false)
      expect(state.drawerGuestId).toBe(ev.payload.drawerGuestId)
      expect(state.turn).toBe(ev.payload.turn)
    })

    it('processes real IMPOSTOR_DRAWING_UPDATED event', () => {
      const ev = uniquePayloads.IMPOSTOR_DRAWING_UPDATED
      useDinoImpostorStore
        .getState()
        .applyDrawingUpdated(ev.payload as unknown as ImpostorDrawingUpdatedPayload)

      const state = useDinoImpostorStore.getState()
      expect(state.strokes.length).toBeGreaterThan(0)
    })

    it('processes real PHASE_STARTED_VOTE event', () => {
      const ev = uniquePayloads.PHASE_STARTED_VOTE
      useDinoImpostorStore
        .getState()
        .applyPhaseStarted(ev.payload as unknown as DinoImpostorPhaseStartedPayload)

      const state = useDinoImpostorStore.getState()
      expect(state.votedGuestIds).toEqual([])
      expect(state.myVote).toBeNull()
    })

    it('processes real IMPOSTOR_VOTE_PROGRESS event', () => {
      const ev = uniquePayloads.IMPOSTOR_VOTE_PROGRESS
      useDinoImpostorStore
        .getState()
        .applyVoteProgress(ev.payload as unknown as ImpostorVoteProgressPayload)

      const state = useDinoImpostorStore.getState()
      expect(state.votedGuestIds).toEqual(ev.payload.votedGuestIds)
    })

    it('processes real IMPOSTOR_VOTE_CLOSED event', () => {
      const ev = uniquePayloads.IMPOSTOR_VOTE_CLOSED
      useDinoImpostorStore
        .getState()
        .applyVoteClosed(ev.payload as unknown as ImpostorVoteClosedPayload)

      const state = useDinoImpostorStore.getState()
      expect(state.voteClosed).toEqual(ev.payload)
    })

    it('processes real IMPOSTOR_GUESS_OPEN private event', () => {
      const ev = uniquePayloads.IMPOSTOR_GUESS_OPEN
      useDinoImpostorStore
        .getState()
        .applyGuessOpen(ev.payload as unknown as ImpostorGuessOpenPayload)

      const state = useDinoImpostorStore.getState()
      expect(state.guessOpen).toEqual(ev.payload)
    })

    it('processes real IMPOSTOR_ROUND_ENDED event', () => {
      const ev = uniquePayloads.IMPOSTOR_ROUND_ENDED
      useDinoImpostorStore
        .getState()
        .applyRoundEnded(ev.payload as unknown as ImpostorRoundEndedPayload)

      const state = useDinoImpostorStore.getState()
      expect(state.roundEnd).toEqual(ev.payload)
      expect(state.roundEnd?.outcome).toBe('GUESSED')
    })
  })

  describe('Regressions', () => {
    it('handles budget exhaustion GAME_ERROR with kind FULL', () => {
      const actionId = crypto.randomUUID()
      useDinoImpostorStore
        .getState()
        .trackCommand(actionId, { type: 'IMPOSTOR_DRAW_STROKE', strokeId: 'str-1' })

      const outcome = useDinoImpostorStore.getState().applyCommandError(
        {
          code: 'PAYLOAD_TOO_LARGE',
          message: 'Canvas budget exceeded',
          retryable: false,
        },
        actionId,
      )

      expect(outcome).toBe('resync')
      expect(useDinoImpostorStore.getState().canvasFull).toBe(true)
      expect(useDinoImpostorStore.getState().strokeRejection?.kind).toBe('FULL')
    })

    it('validates RFC-4122 UUID actionIds for rapid commands', () => {
      const actionIds = Array.from({ length: 10 }, () => crypto.randomUUID())
      const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

      for (const id of actionIds) {
        expect(id).toMatch(uuidRegex)
      }
      const unique = new Set(actionIds)
      expect(unique.size).toBe(10)
    })
  })
})
