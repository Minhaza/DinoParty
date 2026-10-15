import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { GameSnapshot, PlayerDto } from '@/api/types'
import { useConnectionStore } from '@/features/realtime/connectionStore'
import type { RealtimeClient } from '@/features/realtime/realtimeClient'
import { RealtimeContext } from '@/features/realtime/realtimeContext'
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
} from './fixtures'
import { TelephoneStage } from './TelephoneStage'
import { useTelephoneStore } from './telephoneStore'
import type { TelephonePhaseStartedPayload, TelephoneSnapshot } from './types'

const players: PlayerDto[] = ['guest-1', 'guest-2', 'guest-3', 'guest-4'].map((guestId, i) => ({
  guestId,
  nickname: ['Rex', 'Trixie', 'Ptera', 'Steggy'][i],
  avatarKey: 'dino-rex',
  avatarColor: null,
  ready: true,
  connection: 'CONNECTED',
  isHost: i === 0,
  score: null,
}))

let actionCounter = 0
const sendCommand = vi.fn(() => `action-${++actionCounter}`)

function renderStage() {
  return render(
    <RealtimeContext.Provider value={{ sendCommand } as unknown as RealtimeClient}>
      <TelephoneStage players={players} currentGuestId="guest-1" />
    </RealtimeContext.Provider>,
  )
}

function startPhase(payload: TelephonePhaseStartedPayload) {
  act(() => {
    useGameSessionStore.getState().applyPhaseStarted(payload)
    useTelephoneStore.getState().applyPhaseStarted(payload)
  })
}

function hydrate(snapshot: TelephoneSnapshot) {
  act(() => {
    useGameSessionStore.getState().applySnapshot(snapshot as unknown as GameSnapshot)
    useTelephoneStore.getState().applySnapshot(snapshot)
  })
}

const sentTypes = () =>
  sendCommand.mock.calls.map((c) => (c as unknown as [{ type: string }])[0].type)

describe('TelephoneStage', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    sendCommand.mockClear()
    useGameSessionStore.getState().reset()
    useTelephoneStore.getState().reset()
    useConnectionStore.setState({ status: 'CONNECTED' })
    HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue(null)
    Element.prototype.getBoundingClientRect = vi.fn().mockReturnValue({
      left: 0,
      top: 0,
      width: 800,
      height: 500,
      right: 800,
      bottom: 500,
      x: 0,
      y: 0,
      toJSON: () => {},
    })
    Element.prototype.setPointerCapture = vi.fn()
    Element.prototype.releasePointerCapture = vi.fn()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('walks PROMPT → DRAW → DESCRIBE with the right screen for each step', () => {
    renderStage()
    expect(screen.getByText(/getting the next step ready/i)).toBeInTheDocument()

    startPhase(promptPhaseStarted)
    expect(screen.getByRole('heading', { name: 'Step 1 of 4 — Write a prompt' })).toBeVisible()
    expect(screen.getByRole('textbox', { name: /your prompt/i })).toBeInTheDocument()

    startPhase(drawPhaseStarted)
    act(() => useTelephoneStore.getState().applyTask(drawTask))
    expect(screen.getByRole('heading', { name: 'Step 2 of 4 — Draw it' })).toBeVisible()
    expect(screen.getByText(/stegosaurus juggling teacups/)).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /your drawing/i })).toBeInTheDocument()

    startPhase(describePhaseStarted)
    act(() => useTelephoneStore.getState().applyTask(describeTask))
    expect(screen.getByRole('heading', { name: /step 3 of 4 — describe/i })).toBeVisible()
    expect(screen.getByRole('img', { name: /drawing you need to describe/i })).toBeInTheDocument()
    // [TP-8]: the sentence two steps back is never shown.
    expect(screen.queryByText(/stegosaurus/)).not.toBeInTheDocument()
  })

  it('sends one submit per click burst and waits for the server before the waiting screen', () => {
    renderStage()
    startPhase(promptPhaseStarted)
    fireEvent.change(screen.getByRole('textbox'), {
      target: { value: '  A T-rex at the dentist ' },
    })
    const button = screen.getByRole('button', { name: /send prompt/i })
    fireEvent.click(button)
    fireEvent.click(button)

    const submits = sendCommand.mock.calls.filter(
      (c) => (c as unknown as [{ type: string }])[0].type === 'TELEPHONE_SUBMIT_TEXT',
    )
    expect(submits).toHaveLength(1)
    expect((submits[0] as unknown as [unknown])[0]).toEqual({
      type: 'TELEPHONE_SUBMIT_TEXT',
      phaseId: PHASE_PROMPT,
      payload: { text: 'A T-rex at the dentist' },
    })
    // Sent is not accepted: no waiting screen yet.
    expect(screen.queryByText(/on its way/i)).not.toBeInTheDocument()

    act(() => useTelephoneStore.getState().applyActionAck('action-1'))
    expect(screen.getByText(/your prompt is on its way/i)).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('TP9 syncs the text draft to the server after a pause in typing', () => {
    renderStage()
    startPhase(promptPhaseStarted)
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'half a tho' } })
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'half a thought' } })
    expect(sentTypes()).toEqual([])

    act(() => vi.advanceTimersByTime(800))
    expect(sendCommand).toHaveBeenCalledTimes(1)
    expect((sendCommand.mock.calls[0] as unknown as [unknown])[0]).toEqual({
      type: 'TELEPHONE_TEXT_DRAFT',
      phaseId: PHASE_PROMPT,
      payload: { text: 'half a thought' },
    })
  })

  it('maps canvas batches to TELEPHONE_DRAW_STROKE and commits with TELEPHONE_SUBMIT_DRAWING', () => {
    renderStage()
    startPhase(drawPhaseStarted)
    act(() => useTelephoneStore.getState().applyTask(drawTask))

    expect(screen.getByRole('button', { name: /submit a blank page/i })).toBeEnabled()

    const canvas = screen.getByRole('img', { name: /your drawing \(interactive/i })
    fireEvent.pointerDown(canvas, { clientX: 80, clientY: 50, pointerId: 1, button: 0 })
    fireEvent.pointerMove(canvas, { clientX: 160, clientY: 100, pointerId: 1 })
    fireEvent.pointerUp(canvas, { clientX: 160, clientY: 100, pointerId: 1 })

    expect(sentTypes().every((t) => t === 'TELEPHONE_DRAW_STROKE')).toBe(true)
    expect(sentTypes().length).toBeGreaterThan(0)
    const first = (
      sendCommand.mock.calls[0] as unknown as [{ phaseId: string; payload: object }]
    )[0]
    expect(first.phaseId).toBe(PHASE_DRAW)
    expect(first.payload).toMatchObject({ batchNo: 0, tool: 'PENCIL' })

    fireEvent.click(screen.getByRole('button', { name: /undo/i }))
    expect(sentTypes()).toContain('TELEPHONE_DRAW_UNDO')
    expect(useTelephoneStore.getState().strokes).toEqual([])

    fireEvent.click(screen.getByRole('button', { name: /submit a blank page/i }))
    expect(sentTypes().at(-1)).toBe('TELEPHONE_SUBMIT_DRAWING')
    expect((sendCommand.mock.calls.at(-1) as unknown as [{ payload: object }])[0].payload).toEqual(
      {},
    )
  })

  it('shows the waiting screen when public progress lists the viewer', () => {
    renderStage()
    startPhase(drawPhaseStarted)
    act(() =>
      useTelephoneStore.getState().applyProgress({
        ...drawProgress,
        submittedCount: 3,
        submittedGuestIds: ['guest-1', ...drawProgress.submittedGuestIds],
      }),
    )
    expect(screen.getByText(/masterpiece is on its way/i)).toBeInTheDocument()
    expect(screen.getByText('3 of 4 dinos done')).toBeInTheDocument()
  })

  describe('reconnection', () => {
    it('TP9 restores the drawing assignment and draft from the snapshot', () => {
      renderStage()
      hydrate(drawSnapshotWithDraft)
      expect(screen.getByText(/stegosaurus juggling teacups/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /send drawing/i })).toBeEnabled()
    })

    it('TP7 a snapshot with an accepted submission shows no editable draft', () => {
      renderStage()
      hydrate(describeSnapshotSubmitted)
      expect(screen.getByText(/description is on its way/i)).toBeInTheDocument()
      expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    })

    it('restores a text draft and locks input while the socket is down', () => {
      renderStage()
      hydrate(promptSnapshotWithDraft)
      expect(screen.getByRole('textbox')).toHaveValue('A raptor on roller skates')

      act(() => useConnectionStore.setState({ status: 'RECONNECTING' }))
      expect(screen.getByText(/reconnecting/i)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /send prompt/i })).toBeDisabled()
      expect(screen.getByRole('textbox')).toHaveValue('A raptor on roller skates')
    })

    it('drops stale input when the phase moved on while away', () => {
      renderStage()
      hydrate(promptSnapshotWithDraft)
      hydrate(describeSnapshotSubmitted)
      expect(useTelephoneStore.getState().textDraft).toBe('')
      expect(screen.queryByDisplayValue(/roller skates/)).not.toBeInTheDocument()
    })
  })

  it('shows a holding panel during the reveal phase', () => {
    renderStage()
    startPhase({ phase: 'REVEAL', phaseId: 'phase-reveal', deadlineAt: null })
    expect(screen.getByText(/albums are being revealed/i)).toBeInTheDocument()
  })
})
