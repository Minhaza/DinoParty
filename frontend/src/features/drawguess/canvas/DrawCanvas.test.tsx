import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useDrawGuessStore } from '../drawGuessStore'
import { DrawCanvas } from './DrawCanvas'

describe('DrawCanvas', () => {
  beforeEach(() => {
    useDrawGuessStore.getState().reset()
    vi.useFakeTimers()

    // Mock getBoundingClientRect on canvas
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

    // Mock HTMLCanvasElement.getContext
    HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue({
      save: vi.fn(),
      restore: vi.fn(),
      beginPath: vi.fn(),
      arc: vi.fn(),
      moveTo: vi.fn(),
      lineTo: vi.fn(),
      quadraticCurveTo: vi.fn(),
      fill: vi.fn(),
      stroke: vi.fn(),
      fillRect: vi.fn(),
      scale: vi.fn(),
      lineCap: 'butt',
      lineJoin: 'miter',
      strokeStyle: '#000',
      fillStyle: '#000',
      lineWidth: 1,
    })

    // Mock setPointerCapture / releasePointerCapture
    Element.prototype.setPointerCapture = vi.fn()
    Element.prototype.releasePointerCapture = vi.fn()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('renders interactive canvas for authorized drawer during drawing phase', () => {
    render(
      <DrawCanvas
        isDrawer={true}
        isDrawingPhase={true}
        tool="PENCIL"
        color="#2B2B2B"
        size={2}
        onSendStrokeBatch={vi.fn()}
      />,
    )

    const canvas = screen.getByRole('img', { name: /interactive/i })
    expect(canvas).toBeInTheDocument()
    expect(canvas).toHaveClass('cursor-crosshair')
  })

  it('renders read-only canvas for non-drawers or outside drawing phase', () => {
    const { rerender } = render(
      <DrawCanvas
        isDrawer={false}
        isDrawingPhase={true}
        tool="PENCIL"
        color="#2B2B2B"
        size={2}
        onSendStrokeBatch={vi.fn()}
      />,
    )

    let canvas = screen.getByRole('img', { name: /draw & guess canvas view/i })
    expect(canvas).toBeInTheDocument()
    expect(canvas).toHaveClass('cursor-default')

    rerender(
      <DrawCanvas
        isDrawer={true}
        isDrawingPhase={false}
        tool="PENCIL"
        color="#2B2B2B"
        size={2}
        onSendStrokeBatch={vi.fn()}
      />,
    )

    canvas = screen.getByRole('img', { name: /draw & guess canvas view/i })
    expect(canvas).toHaveClass('cursor-default')
  })

  it('does not send strokes when non-drawer touches canvas', () => {
    const onSend = vi.fn()
    render(
      <DrawCanvas
        isDrawer={false}
        isDrawingPhase={true}
        tool="PENCIL"
        color="#2B2B2B"
        size={2}
        onSendStrokeBatch={onSend}
      />,
    )

    const canvas = screen.getByRole('img')
    fireEvent.pointerDown(canvas, { clientX: 200, clientY: 100, pointerId: 1 })
    fireEvent.pointerMove(canvas, { clientX: 300, clientY: 150, pointerId: 1 })
    fireEvent.pointerUp(canvas, { clientX: 300, clientY: 150, pointerId: 1 })

    expect(onSend).not.toHaveBeenCalled()
  })

  it('captures gestures and sends stroke batches when drawer draws', () => {
    const onSend = vi.fn()
    render(
      <DrawCanvas
        isDrawer={true}
        isDrawingPhase={true}
        tool="PENCIL"
        color="#2B2B2B"
        size={2}
        onSendStrokeBatch={onSend}
      />,
    )

    const canvas = screen.getByRole('img')

    // Start drawing
    fireEvent.pointerDown(canvas, { clientX: 80, clientY: 50, pointerId: 1, button: 0 })

    // Move pointer
    fireEvent.pointerMove(canvas, { clientX: 160, clientY: 100, pointerId: 1 })

    // Advance 50ms interval
    vi.advanceTimersByTime(50)

    expect(onSend).toHaveBeenCalledWith(
      expect.objectContaining({
        batchNo: 0,
        tool: 'PENCIL',
        color: '#2B2B2B',
        final: false,
        points: expect.any(Array),
      }),
    )

    // Complete stroke
    fireEvent.pointerUp(canvas, { clientX: 240, clientY: 150, pointerId: 1 })

    expect(onSend).toHaveBeenLastCalledWith(
      expect.objectContaining({
        final: true,
      }),
    )

    // Local store should have recorded the stroke
    const storeStrokes = useDrawGuessStore.getState().strokes
    expect(storeStrokes.length).toBeGreaterThan(0)
    expect(storeStrokes[0].closed).toBe(true)
  })

  it('handles pointer cancel gracefully', () => {
    const onSend = vi.fn()
    render(
      <DrawCanvas
        isDrawer={true}
        isDrawingPhase={true}
        tool="PENCIL"
        color="#2B2B2B"
        size={2}
        onSendStrokeBatch={onSend}
      />,
    )

    const canvas = screen.getByRole('img')
    fireEvent.pointerDown(canvas, { clientX: 100, clientY: 100, pointerId: 1, button: 0 })
    fireEvent.pointerCancel(canvas, { pointerId: 1 })

    expect(onSend).toHaveBeenCalledWith(
      expect.objectContaining({
        final: true,
      }),
    )
  })
})
