import { beforeEach, describe, expect, it } from 'vitest'

import {
  sampleDrawerWordPayload,
  sampleDrawingClearUpdate,
  sampleDrawingSnapshotDrawer,
  sampleDrawingStrokeFinalUpdate,
  sampleDrawingStrokeUpdate,
  sampleDrawingUndoUpdate,
  sampleGuessAcceptedPayload,
  sampleGuessChatPayload,
  sampleGuesserChatPayload,
  sampleGuessFeedbackClose,
  sampleHintUpdatedPayload,
  samplePhaseStartedDrawing,
  samplePhaseStartedWordChoice,
  sampleRoundEndedPayload,
  sampleWordChoicesPayload,
} from './fixtures'
import { useDrawGuessStore } from './drawGuessStore'

describe('useDrawGuessStore', () => {
  beforeEach(() => {
    useDrawGuessStore.getState().reset()
  })

  it('hydrates state from snapshot', () => {
    useDrawGuessStore.getState().applySnapshot(sampleDrawingSnapshotDrawer)

    const state = useDrawGuessStore.getState()
    expect(state.round).toBe(1)
    expect(state.totalRounds).toBe(6)
    expect(state.drawerGuestId).toBe('guest-1')
    expect(state.word).toBe('VOLCANO')
    expect(state.strokes).toHaveLength(1)
    expect(state.strokes[0].strokeId).toBe(sampleDrawingSnapshotDrawer.strokes[0].strokeId)
    expect(state.guessedGuestIds).toContain('guest-2')
    expect(state.chat).toHaveLength(2)
  })

  it('resets per-round state on WORD_CHOICE phase start', () => {
    useDrawGuessStore.getState().applySnapshot(sampleDrawingSnapshotDrawer)
    expect(useDrawGuessStore.getState().strokes).toHaveLength(1)

    useDrawGuessStore.getState().applyPhaseStarted(samplePhaseStartedWordChoice)

    const state = useDrawGuessStore.getState()
    expect(state.strokes).toHaveLength(0)
    expect(state.word).toBeNull()
    expect(state.guessedGuestIds).toHaveLength(0)
    expect(state.round).toBe(1)
  })

  it('updates hint on DRAWING phase start and HINT_UPDATED', () => {
    useDrawGuessStore.getState().applyPhaseStarted(samplePhaseStartedDrawing)
    expect(useDrawGuessStore.getState().hint).toBe('_______')

    useDrawGuessStore.getState().applyHintUpdated(sampleHintUpdatedPayload)
    expect(useDrawGuessStore.getState().hint).toBe('V___A_O')
  })

  it('applies stroke updates and handles multiple batches', () => {
    // Initial batch
    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingStrokeUpdate)
    let state = useDrawGuessStore.getState()
    expect(state.strokes).toHaveLength(1)
    expect(state.strokes[0].points).toHaveLength(2)
    expect(state.strokes[0].closed).toBe(false)

    // Final batch for same stroke
    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingStrokeFinalUpdate)
    state = useDrawGuessStore.getState()
    expect(state.strokes).toHaveLength(1)
    expect(state.strokes[0].points).toHaveLength(3)
    expect(state.strokes[0].closed).toBe(true)
  })

  it('deduplicates duplicate batch arrivals', () => {
    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingStrokeUpdate)
    expect(useDrawGuessStore.getState().strokes[0].points).toHaveLength(2)

    // Send duplicate batch 0
    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingStrokeUpdate)
    expect(useDrawGuessStore.getState().strokes[0].points).toHaveLength(2)
  })

  it('suppresses echo for drawer local strokes', () => {
    const store = useDrawGuessStore.getState()
    store.applyPhaseStarted(samplePhaseStartedDrawing) // drawer is guest-1

    // Drawer creates local stroke
    store.addLocalStroke({
      strokeId: 'stroke-local-1',
      tool: 'PENCIL',
      color: '#000000',
      size: 2,
      points: [[100, 100]],
      closed: false,
    })

    expect(useDrawGuessStore.getState().strokes[0].points).toHaveLength(1)

    // Server echoes back the drawer's own stroke
    useDrawGuessStore.getState().applyDrawingUpdated({
      kind: 'STROKE',
      strokeId: 'stroke-local-1',
      batchNo: 0,
      tool: 'PENCIL',
      color: '#000000',
      size: 2,
      points: [[100, 100]],
      final: true,
    })

    // Points should not be duplicated
    expect(useDrawGuessStore.getState().strokes[0].points).toHaveLength(1)
    // Closed flag should update
    expect(useDrawGuessStore.getState().strokes[0].closed).toBe(true)
  })

  it('applies undo and clear updates', () => {
    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingStrokeUpdate)
    expect(useDrawGuessStore.getState().strokes).toHaveLength(1)

    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingUndoUpdate)
    expect(useDrawGuessStore.getState().strokes).toHaveLength(0)

    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingStrokeUpdate)
    expect(useDrawGuessStore.getState().strokes).toHaveLength(1)

    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingClearUpdate)
    expect(useDrawGuessStore.getState().strokes).toHaveLength(0)
  })

  it('handles guesses, solver chat, and guess feedback', () => {
    useDrawGuessStore.getState().applyGuessAccepted(sampleGuessAcceptedPayload)
    expect(useDrawGuessStore.getState().guessedGuestIds).toContain('guest-2')

    useDrawGuessStore.getState().applyGuessChat(sampleGuessChatPayload)
    expect(useDrawGuessStore.getState().chat).toHaveLength(1)
    expect(useDrawGuessStore.getState().chat[0].scope).toBe('PUBLIC')

    useDrawGuessStore.getState().applyGuesserChat(sampleGuesserChatPayload)
    expect(useDrawGuessStore.getState().chat).toHaveLength(2)
    expect(useDrawGuessStore.getState().chat[1].scope).toBe('GUESSERS')

    useDrawGuessStore.getState().applyGuessFeedback(sampleGuessFeedbackClose)
    expect(useDrawGuessStore.getState().feedback).toBe('CLOSE')
    useDrawGuessStore.getState().clearFeedback()
    expect(useDrawGuessStore.getState().feedback).toBeNull()
  })

  it('records word choices and drawer word', () => {
    useDrawGuessStore.getState().applyWordChoices(sampleWordChoicesPayload)
    expect(useDrawGuessStore.getState().wordChoices).toEqual(['VELOCIRAPTOR', 'VOLCANO', 'FOSSIL'])

    useDrawGuessStore.getState().applyDrawerWord(sampleDrawerWordPayload)
    expect(useDrawGuessStore.getState().word).toBe('VOLCANO')
    expect(useDrawGuessStore.getState().wordChoices).toBeNull()
  })

  it('applies round ended payload with word and score deltas', () => {
    useDrawGuessStore.getState().applyRoundEnded(sampleRoundEndedPayload)
    const state = useDrawGuessStore.getState()
    expect(state.round).toBe(1)
    expect(state.word).toBe('VOLCANO')
    expect(state.roundEnd?.reason).toBe('ALL_GUESSED')
    expect(state.roundEnd?.scoreDeltas['guest-1']).toBe(500)
  })

  describe('multiplayer integration (P5-04)', () => {
    const openStroke = {
      ...sampleDrawingStrokeUpdate,
      strokeId: 'open-stroke',
    }

    it('keeps applying batches of a stroke that was still open in the snapshot', () => {
      useDrawGuessStore.getState().applySnapshot({
        ...sampleDrawingSnapshotDrawer,
        word: null,
        strokes: [{ ...openStroke, points: [[1, 1]], closed: false }],
      })

      // Batch 3 of the open stroke arrives with a seq after the snapshot's.
      useDrawGuessStore
        .getState()
        .applyDrawingUpdated({ ...openStroke, batchNo: 3, points: [[2, 2]], final: true })

      const [stroke] = useDrawGuessStore.getState().strokes
      expect(stroke.points).toEqual([
        [1, 1],
        [2, 2],
      ])
      expect(stroke.closed).toBe(true)
    })

    it('ignores late batches for a stroke that is already closed', () => {
      useDrawGuessStore.getState().applySnapshot(sampleDrawingSnapshotDrawer)
      const closedId = sampleDrawingSnapshotDrawer.strokes[0].strokeId
      useDrawGuessStore
        .getState()
        .applyDrawingUpdated({ ...openStroke, strokeId: closedId, batchNo: 5, points: [[9, 9]] })
      expect(useDrawGuessStore.getState().strokes[0].points).toHaveLength(3)
    })

    it('suppresses echoes of every batch of a local stroke, not only the first', () => {
      const store = useDrawGuessStore.getState()
      store.addLocalStroke({ ...openStroke, points: [[10, 10]], closed: false })
      store.appendLocalPoints('open-stroke', [[20, 20]], false)

      store.applyDrawingUpdated({ ...openStroke, batchNo: 0, points: [[10, 10]] })
      store.applyDrawingUpdated({ ...openStroke, batchNo: 1, points: [[20, 20]], final: true })

      const [stroke] = useDrawGuessStore.getState().strokes
      expect(stroke.points).toHaveLength(2)
      expect(stroke.closed).toBe(true)
    })

    it('rebuilds a local stroke from echoes when a snapshot replaced the canvas', () => {
      const store = useDrawGuessStore.getState()
      store.addLocalStroke({ ...openStroke, points: [[10, 10]], closed: false })
      // Snapshot built before batch 0 was accepted: the stroke is not in it.
      store.applySnapshot({ ...sampleDrawingSnapshotDrawer, strokes: [] })
      expect(useDrawGuessStore.getState().strokes).toHaveLength(0)

      store.applyDrawingUpdated({ ...openStroke, batchNo: 0, points: [[10, 10]] })
      expect(useDrawGuessStore.getState().strokes).toHaveLength(1)

      // Later echoes of our own stroke are suppressed again: the preview has those points.
      store.appendLocalPoints('open-stroke', [[30, 30]], false)
      store.applyDrawingUpdated({ ...openStroke, batchNo: 1, points: [[30, 30]] })
      expect(useDrawGuessStore.getState().strokes[0].points).toHaveLength(2)
    })

    it('keeps word choices that arrive before their PHASE_STARTED, drops stale ones', () => {
      const store = useDrawGuessStore.getState()
      store.applyWordChoices(sampleWordChoicesPayload)
      store.applyPhaseStarted(samplePhaseStartedWordChoice) // same phaseId
      expect(useDrawGuessStore.getState().wordChoices).toEqual(sampleWordChoicesPayload.choices)

      store.applyPhaseStarted({ ...samplePhaseStartedWordChoice, phaseId: 'next-round' })
      expect(useDrawGuessStore.getState().wordChoices).toBeNull()
    })

    it('clears chat when a new round starts (the server clears it too)', () => {
      const store = useDrawGuessStore.getState()
      store.applyGuessChat(sampleGuessChatPayload, '2026-10-14T10:00:30.000Z')
      expect(useDrawGuessStore.getState().chat[0].ts).toBe('2026-10-14T10:00:30.000Z')
      store.applyPhaseStarted(samplePhaseStartedWordChoice)
      expect(useDrawGuessStore.getState().chat).toHaveLength(0)
    })

    it('maps stroke rejections to restart, canvas full and silent phase races', () => {
      const store = useDrawGuessStore.getState()
      store.trackCommand('a1', 'DRAW_STROKE', 's1')
      store.trackCommand('a2', 'DRAW_STROKE', 's2')
      store.trackCommand('a3', 'DRAW_STROKE', 's3')
      store.trackCommand('a4', 'SUBMIT_GUESS')

      const err = (code: string, actionId: string) => ({
        actionId,
        code,
        message: code,
        retryable: false,
      })

      expect(store.applyCommandError(err('INVALID_ACTION', 'a1'))).toBe('resync')
      expect(useDrawGuessStore.getState().strokeRejection).toMatchObject({
        strokeId: 's1',
        kind: 'RESTART',
      })

      expect(store.applyCommandError(err('PAYLOAD_TOO_LARGE', 'a2'))).toBe('resync')
      expect(useDrawGuessStore.getState().canvasFull).toBe(true)
      expect(useDrawGuessStore.getState().strokeRejection?.kind).toBe('FULL')

      expect(store.applyCommandError(err('STALE_ACTION', 'a3'))).toBe('handled')
      // A late guess is worth telling the player about.
      expect(store.applyCommandError(err('STALE_ACTION', 'a4'))).toBe('unhandled')
      // Unknown or already-acknowledged actions are left to the generic error banner.
      store.trackCommand('a5', 'DRAW_STROKE', 's5')
      store.applyActionAck('a5')
      expect(store.applyCommandError(err('INVALID_ACTION', 'a5'))).toBe('unhandled')
    })

    it('frees the canvas-full state on undo, clear and a snapshot with room', () => {
      const store = useDrawGuessStore.getState()
      useDrawGuessStore.setState({ canvasFull: true })
      store.applySnapshot({
        ...sampleDrawingSnapshotDrawer,
        canvasBudget: { points: 11950, maxPoints: 12000, strokes: 40, maxStrokes: 300 },
      })
      expect(useDrawGuessStore.getState().canvasFull).toBe(true)

      store.applyDrawingUpdated(sampleDrawingUndoUpdate)
      expect(useDrawGuessStore.getState().canvasFull).toBe(false)

      useDrawGuessStore.setState({ canvasFull: true })
      store.applyDrawingUpdated(sampleDrawingClearUpdate)
      expect(useDrawGuessStore.getState().canvasFull).toBe(false)

      useDrawGuessStore.setState({ canvasFull: true })
      store.applySnapshot(sampleDrawingSnapshotDrawer) // 3 of 12000 points used
      expect(useDrawGuessStore.getState().canvasFull).toBe(false)
    })
  })
})
