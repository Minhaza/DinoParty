import { beforeEach, describe, expect, it } from 'vitest'

import type { GameSnapshot } from '@/api/types'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import {
  describePhaseStarted,
  describeSnapshotSubmitted,
  describeTask,
  drawPhaseStarted,
  drawProgress,
  drawSnapshotWithDraft,
  drawTask,
  PHASE_DRAW,
  PHASE_PROMPT,
  promptPhaseStarted,
  promptSnapshotWithDraft,
  sampleTelephoneStroke,
} from './fixtures'
import { useTelephoneStore } from './telephoneStore'
import type { TelephonePhaseStartedPayload, TelephoneSnapshot } from './types'

const store = () => useTelephoneStore.getState()

/** Applies a PHASE_STARTED the way roomStore does: session header first, then the game store. */
function startPhase(payload: TelephonePhaseStartedPayload) {
  useGameSessionStore.getState().applyPhaseStarted(payload)
  store().applyPhaseStarted(payload)
}

function hydrate(snapshot: TelephoneSnapshot) {
  useGameSessionStore.getState().applySnapshot(snapshot as unknown as GameSnapshot)
  store().applySnapshot(snapshot)
}

const error = (code: string, actionId: string, retryable = false) => ({
  actionId,
  code,
  message: 'nope',
  retryable,
})

describe('telephoneStore', () => {
  beforeEach(() => {
    useGameSessionStore.getState().reset()
    store().reset()
  })

  describe('snapshot hydration (reconnect)', () => {
    it('TP9 restores the task, the server-held drawing draft and the budget', () => {
      hydrate(drawSnapshotWithDraft)
      const s = store()
      expect(s.kind).toBe('DRAW')
      expect(s.step).toBe(1)
      expect(s.totalSteps).toBe(4)
      expect(s.task?.source).toEqual(drawTask.source)
      expect(s.strokes).toEqual([sampleTelephoneStroke])
      expect(s.canvasFull).toBe(false)
      expect(s.submission).toBe('editing')
      expect(s.progress).toEqual({
        phaseId: PHASE_DRAW,
        submittedCount: 1,
        total: 4,
        submittedGuestIds: ['guest-2'],
      })
    })

    it('TP7 a submitted viewer gets the waiting state and no editable draft', () => {
      store().setTextDraft('stale text from before the refresh')
      hydrate(describeSnapshotSubmitted)
      expect(store().submission).toBe('submitted')
      expect(store().textDraft).toBe('')
      expect(store().strokes).toEqual([])
    })

    it('TP9 restores a text draft, but keeps newer local text of the same step', () => {
      hydrate(promptSnapshotWithDraft)
      expect(store().textDraft).toBe('A raptor on roller skates')

      store().setTextDraft('A raptor on roller skates, typed offline')
      hydrate(promptSnapshotWithDraft)
      expect(store().textDraft).toBe('A raptor on roller skates, typed offline')
    })

    it('marks the canvas full when the server budget is exhausted', () => {
      hydrate({
        ...drawSnapshotWithDraft,
        draft: {
          strokes: [sampleTelephoneStroke],
          canvasBudget: { points: 11950, maxPoints: 12000, strokes: 40, maxStrokes: 300 },
        },
      })
      expect(store().canvasFull).toBe(true)
    })

    it('a null game snapshot resets everything', () => {
      hydrate(drawSnapshotWithDraft)
      store().applySnapshot(null)
      expect(store().strokes).toEqual([])
      expect(store().task).toBeNull()
      expect(store().workPhaseId).toBeNull()
    })
  })

  describe('phase transitions', () => {
    it('PROMPT → DRAW → DESCRIBE starts every step with fresh local work', () => {
      startPhase(promptPhaseStarted)
      store().setTextDraft('my prompt')
      expect(store().kind).toBe('PROMPT')

      startPhase(drawPhaseStarted)
      expect(store().kind).toBe('DRAW')
      expect(store().step).toBe(1)
      expect(store().textDraft).toBe('')
      store().addLocalStroke(sampleTelephoneStroke)

      startPhase(describePhaseStarted)
      expect(store().kind).toBe('DESCRIBE')
      expect(store().strokes).toEqual([])
      expect(store().submission).toBe('editing')
    })

    it('TP8 keeps only the current task, never an earlier entry of the chain', () => {
      startPhase(drawPhaseStarted)
      store().applyTask(drawTask)
      startPhase(describePhaseStarted)
      store().applyTask(describeTask)
      expect(store().task).toEqual({ kind: 'DESCRIBE', source: describeTask.source })
      expect(JSON.stringify(store())).not.toContain('stegosaurus')
    })

    it('holds a private task that arrives before its public PHASE_STARTED', () => {
      startPhase(promptPhaseStarted)
      store().applyTask(drawTask)
      expect(store().task).toBeNull()
      expect(store().pendingTask).toEqual(drawTask)

      startPhase(drawPhaseStarted)
      expect(store().task).toEqual({ kind: 'DRAW', source: drawTask.source })
      expect(store().pendingTask).toBeNull()
    })

    it('ignores progress of another phase', () => {
      startPhase(promptPhaseStarted)
      store().applyProgress(drawProgress)
      expect(store().progress).toBeNull()

      startPhase(drawPhaseStarted)
      store().applyProgress(drawProgress)
      expect(store().progress).toEqual(drawProgress)
    })
  })

  describe('submission lifecycle', () => {
    it('sending moves to submitting; only the server ACK of the current phase confirms', () => {
      startPhase(promptPhaseStarted)
      store().trackCommand('a-1', 'TELEPHONE_SUBMIT_TEXT', PHASE_PROMPT)
      expect(store().submission).toBe('submitting')

      store().applyActionAck('a-1')
      expect(store().submission).toBe('submitted')
    })

    it('an ACK from an earlier phase does not mark the new step as submitted', () => {
      startPhase(promptPhaseStarted)
      store().trackCommand('a-old', 'TELEPHONE_SUBMIT_TEXT', PHASE_PROMPT)
      startPhase(drawPhaseStarted)

      store().applyActionAck('a-old')
      expect(store().submission).toBe('editing')
    })

    it('an error from an earlier phase is swallowed without touching the new step', () => {
      startPhase(promptPhaseStarted)
      store().trackCommand('a-old', 'TELEPHONE_SUBMIT_TEXT', PHASE_PROMPT)
      startPhase(drawPhaseStarted)

      expect(store().applyCommandError(error('VALIDATION_FAILED', 'a-old'))).toBe('handled')
      expect(store().submission).toBe('editing')
      expect(store().submitError).toBeNull()
    })

    it('VALIDATION_FAILED on submit shows an error and allows editing again', () => {
      startPhase(promptPhaseStarted)
      store().trackCommand('a-1', 'TELEPHONE_SUBMIT_TEXT', PHASE_PROMPT)
      expect(store().applyCommandError(error('VALIDATION_FAILED', 'a-1'))).toBe('handled')
      expect(store().submission).toBe('error')
      expect(store().submitError).toMatch(/not accepted/i)
    })

    it('TP7 ALREADY_SUBMITTED means the server holds a final entry', () => {
      startPhase(promptPhaseStarted)
      store().trackCommand('a-1', 'TELEPHONE_SUBMIT_TEXT', PHASE_PROMPT)
      expect(store().applyCommandError(error('ALREADY_SUBMITTED', 'a-1'))).toBe('resync')
      expect(store().submission).toBe('submitted')
    })

    it('TP12 STALE_ACTION locks the step and resyncs instead of showing an error', () => {
      startPhase(promptPhaseStarted)
      store().trackCommand('a-1', 'TELEPHONE_SUBMIT_TEXT', PHASE_PROMPT)
      expect(store().applyCommandError(error('STALE_ACTION', 'a-1'))).toBe('resync')
      expect(store().syncing).toBe(true)
      expect(store().submission).toBe('editing')

      startPhase(drawPhaseStarted)
      expect(store().syncing).toBe(false)
    })

    it('a reconnect snapshot without the submission ends the pending state', () => {
      startPhase(promptPhaseStarted)
      store().trackCommand('a-1', 'TELEPHONE_SUBMIT_TEXT', PHASE_PROMPT)
      hydrate(promptSnapshotWithDraft)
      expect(store().submission).toBe('error')
      expect(store().submitError).toMatch(/not received/i)

      // The command may still have been processed: its late ACK is authoritative.
      store().applyActionAck('a-1')
      expect(store().submission).toBe('submitted')
    })

    it('a submission without any answer stops waiting after the watchdog', () => {
      startPhase(promptPhaseStarted)
      store().trackCommand('a-1', 'TELEPHONE_SUBMIT_TEXT', PHASE_PROMPT)
      expect(store().expireSubmission()).toBe(true)
      expect(store().submission).toBe('error')
      expect(store().submitError).toMatch(/no answer/i)

      // Nothing is pending any more, so a second expiry changes nothing.
      expect(store().expireSubmission()).toBe(false)
    })

    it.each([
      ['NOT_IN_ROOM', false, /no longer in this game/i],
      ['RATE_LIMITED', true, /too fast/i],
      ['PAYLOAD_TOO_LARGE', false, /too large/i],
      ['SERVER_ERROR', false, /could not be sent/i],
    ])('%s on submit shows player-facing copy, never the server message', (code, retry, copy) => {
      startPhase(promptPhaseStarted)
      store().trackCommand('a-1', 'TELEPHONE_SUBMIT_TEXT', PHASE_PROMPT)
      expect(store().applyCommandError(error(code, 'a-1', retry))).toBe('handled')
      expect(store().submission).toBe('error')
      expect(store().submitError).toMatch(copy)
      expect(store().submitError).not.toMatch(/nope/)
    })

    it('leaves errors of commands it did not send to other stores', () => {
      startPhase(promptPhaseStarted)
      expect(store().applyCommandError(error('NOT_DRAWER', 'someone-else'))).toBe('unhandled')
    })
  })

  describe('drawing draft', () => {
    it('TP9 a rejected batch over budget fills the canvas and resyncs', () => {
      startPhase(drawPhaseStarted)
      store().trackCommand('s-1', 'TELEPHONE_DRAW_STROKE', PHASE_DRAW, 'stroke-1')
      expect(store().applyCommandError(error('PAYLOAD_TOO_LARGE', 's-1'))).toBe('resync')
      expect(store().canvasFull).toBe(true)
      expect(store().strokeRejection).toMatchObject({ strokeId: 'stroke-1', kind: 'FULL' })
    })

    it('an invalid batch restarts the stroke', () => {
      startPhase(drawPhaseStarted)
      store().trackCommand('s-1', 'TELEPHONE_DRAW_STROKE', PHASE_DRAW, 'stroke-1')
      expect(store().applyCommandError(error('INVALID_ACTION', 's-1'))).toBe('resync')
      expect(store().strokeRejection).toMatchObject({ strokeId: 'stroke-1', kind: 'RESTART' })
    })

    it('undo and clear apply locally because strokes are not echoed', () => {
      startPhase(drawPhaseStarted)
      store().addLocalStroke({ ...sampleTelephoneStroke, strokeId: 'one' })
      store().addLocalStroke({ ...sampleTelephoneStroke, strokeId: 'two' })
      store().undoLocalStroke()
      expect(store().strokes.map((s) => s.strokeId)).toEqual(['one'])
      store().clearLocalStrokes()
      expect(store().strokes).toEqual([])
    })
  })

  it('TP13 reset (rematch, new room) clears drafts and pending commands', () => {
    hydrate(drawSnapshotWithDraft)
    store().trackCommand('a-1', 'TELEPHONE_SUBMIT_DRAWING', PHASE_DRAW)
    store().reset()
    expect(store().strokes).toEqual([])
    expect(store().task).toBeNull()
    expect(store().submission).toBe('editing')
    // The tracked command is gone: a late ACK cannot mark anything.
    useGameSessionStore.getState().applyPhaseStarted(drawPhaseStarted)
    store().applyPhaseStarted(drawPhaseStarted)
    store().applyActionAck('a-1')
    expect(store().submission).toBe('editing')
  })
})
