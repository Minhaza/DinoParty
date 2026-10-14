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
    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingStrokeUpdate, 'guest-3')
    let state = useDrawGuessStore.getState()
    expect(state.strokes).toHaveLength(1)
    expect(state.strokes[0].points).toHaveLength(2)
    expect(state.strokes[0].closed).toBe(false)

    // Final batch for same stroke
    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingStrokeFinalUpdate, 'guest-3')
    state = useDrawGuessStore.getState()
    expect(state.strokes).toHaveLength(1)
    expect(state.strokes[0].points).toHaveLength(3)
    expect(state.strokes[0].closed).toBe(true)
  })

  it('deduplicates duplicate batch arrivals', () => {
    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingStrokeUpdate, 'guest-3')
    expect(useDrawGuessStore.getState().strokes[0].points).toHaveLength(2)

    // Send duplicate batch 0
    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingStrokeUpdate, 'guest-3')
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
    useDrawGuessStore.getState().applyDrawingUpdated(
      {
        kind: 'STROKE',
        strokeId: 'stroke-local-1',
        batchNo: 0,
        tool: 'PENCIL',
        color: '#000000',
        size: 2,
        points: [[100, 100]],
        final: true,
      },
      'guest-1', // current guest is drawer
    )

    // Points should not be duplicated
    expect(useDrawGuessStore.getState().strokes[0].points).toHaveLength(1)
    // Closed flag should update
    expect(useDrawGuessStore.getState().strokes[0].closed).toBe(true)
  })

  it('applies undo and clear updates', () => {
    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingStrokeUpdate, 'guest-3')
    expect(useDrawGuessStore.getState().strokes).toHaveLength(1)

    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingUndoUpdate, 'guest-3')
    expect(useDrawGuessStore.getState().strokes).toHaveLength(0)

    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingStrokeUpdate, 'guest-3')
    expect(useDrawGuessStore.getState().strokes).toHaveLength(1)

    useDrawGuessStore.getState().applyDrawingUpdated(sampleDrawingClearUpdate, 'guest-3')
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
    expect(useDrawGuessStore.getState().wordChoices).toEqual([
      'VELOCIRAPTOR',
      'VOLCANO',
      'FOSSIL',
    ])

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
})
