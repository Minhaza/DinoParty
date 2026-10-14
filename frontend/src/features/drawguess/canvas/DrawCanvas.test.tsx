import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useDrawGuessStore } from '../drawGuessStore'
import type { DrawStrokePayload } from '../types'
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

  describe('stroke delivery (P5-04)', () => {
    const renderDrawer = (onSend: (b: DrawStrokePayload) => boolean | void, drawing = true) =>
      render(
        <DrawCanvas
          isDrawer={true}
          isDrawingPhase={drawing}
          tool="PENCIL"
          color="#2B2B2B"
          size={2}
          onSendStrokeBatch={onSend}
        />,
      )

    const batches = (fn: ReturnType<typeof vi.fn>) =>
      fn.mock.calls.map((c) => c[0] as DrawStrokePayload)

    it('drops points closer than the minimum distance before sending', () => {
      const onSend = vi.fn()
      renderDrawer(onSend)
      const canvas = screen.getByRole('img')
      fireEvent.pointerDown(canvas, { clientX: 100, clientY: 100, pointerId: 1, button: 0 })
      fireEvent.pointerMove(canvas, { clientX: 100.2, clientY: 100.2, pointerId: 1 })
      fireEvent.pointerMove(canvas, { clientX: 200, clientY: 200, pointerId: 1 })
      fireEvent.pointerUp(canvas, { pointerId: 1 })

      const points = batches(onSend).flatMap((b) => b.points)
      expect(points).toEqual([
        [1250, 2000],
        [2500, 4000],
      ])
    })

    it('continues in a new stroke from the last sent point when the socket was down', () => {
      let connected = true
      const onSend = vi.fn(() => connected)
      renderDrawer(onSend)
      const canvas = screen.getByRole('img')

      fireEvent.pointerDown(canvas, { clientX: 80, clientY: 50, pointerId: 1, button: 0 })
      vi.advanceTimersByTime(50)
      const first = batches(onSend)[0]
      expect(first.batchNo).toBe(0)

      connected = false
      fireEvent.pointerMove(canvas, { clientX: 160, clientY: 100, pointerId: 1 })
      vi.advanceTimersByTime(50)

      connected = true
      fireEvent.pointerMove(canvas, { clientX: 240, clientY: 150, pointerId: 1 })
      vi.advanceTimersByTime(50)
      fireEvent.pointerUp(canvas, { pointerId: 1 })

      const sent = batches(onSend).filter((_, i) => onSend.mock.results[i].value === true)
      const restarted = sent.find((b) => b.strokeId !== first.strokeId)
      expect(restarted).toBeDefined()
      expect(restarted?.batchNo).toBe(0)
      // Joined to the previous stroke, and the points buffered while offline were not lost.
      expect(restarted?.points).toEqual([
        [1000, 1000],
        [2000, 2000],
        [3000, 3000],
      ])
      expect(sent[sent.length - 1].final).toBe(true)
    })

    it('restarts the stroke after the server rejects one of its batches', () => {
      const onSend = vi.fn()
      renderDrawer(onSend)
      const canvas = screen.getByRole('img')
      fireEvent.pointerDown(canvas, { clientX: 80, clientY: 50, pointerId: 1, button: 0 })
      vi.advanceTimersByTime(50)
      const first = batches(onSend)[0]

      act(() => {
        useDrawGuessStore.setState({
          strokeRejection: { strokeId: first.strokeId, kind: 'RESTART', nonce: 1 },
        })
      })
      fireEvent.pointerMove(canvas, { clientX: 160, clientY: 100, pointerId: 1 })
      vi.advanceTimersByTime(50)

      const next = batches(onSend)[1]
      expect(next.strokeId).not.toBe(first.strokeId)
      expect(next.batchNo).toBe(0)
    })

    it('closes the stroke and stops drawing when the canvas is full', () => {
      const onSend = vi.fn()
      renderDrawer(onSend)
      const canvas = screen.getByRole('img')
      fireEvent.pointerDown(canvas, { clientX: 80, clientY: 50, pointerId: 1, button: 0 })
      vi.advanceTimersByTime(50)
      const first = batches(onSend)[0]

      act(() => {
        useDrawGuessStore.setState({
          canvasFull: true,
          strokeRejection: { strokeId: first.strokeId, kind: 'FULL', nonce: 2 },
        })
      })

      expect(batches(onSend)[1]).toMatchObject({
        strokeId: first.strokeId,
        batchNo: 1,
        points: [],
        final: true,
      })
      expect(screen.getByRole('status')).toHaveTextContent(/canvas full/i)

      fireEvent.pointerMove(canvas, { clientX: 160, clientY: 100, pointerId: 1 })
      fireEvent.pointerDown(canvas, { clientX: 10, clientY: 10, pointerId: 2, button: 0 })
      vi.advanceTimersByTime(200)
      expect(onSend).toHaveBeenCalledTimes(2)
    })

    it('stops sending when the drawing phase ends mid-stroke', () => {
      const onSend = vi.fn()
      const { rerender } = renderDrawer(onSend)
      const canvas = screen.getByRole('img')
      fireEvent.pointerDown(canvas, { clientX: 80, clientY: 50, pointerId: 1, button: 0 })
      vi.advanceTimersByTime(50)
      expect(onSend).toHaveBeenCalledTimes(1)

      rerender(
        <DrawCanvas
          isDrawer={true}
          isDrawingPhase={false}
          tool="PENCIL"
          color="#2B2B2B"
          size={2}
          onSendStrokeBatch={onSend}
        />,
      )
      fireEvent.pointerMove(canvas, { clientX: 160, clientY: 100, pointerId: 1 })
      fireEvent.pointerUp(canvas, { pointerId: 1 })
      vi.advanceTimersByTime(200)
      expect(onSend).toHaveBeenCalledTimes(1)
    })

    it('splits a long gesture so no stroke exceeds 2000 points or a batch 200', () => {
      // No-op 2D context: a recording mock would log millions of lineTo calls across redraws.
      HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue(null)
      const onSend = vi.fn()
      renderDrawer(onSend)
      const canvas = screen.getByRole('img')
      fireEvent.pointerDown(canvas, { clientX: 0, clientY: 0, pointerId: 1, button: 0 })
      // 2300 distinct points along a zig-zag, one flush interval at a time.
      for (let i = 1; i <= 2300; i++) {
        fireEvent.pointerMove(canvas, {
          clientX: (i % 400) * 2,
          clientY: Math.floor(i / 400) * 20 + (i % 2),
          pointerId: 1,
        })
        if (i % 30 === 0) vi.advanceTimersByTime(50)
      }
      fireEvent.pointerUp(canvas, { pointerId: 1 })

      const sent = batches(onSend)
      const perStroke = new Map<string, number>()
      const lastBatch = new Map<string, number>()
      for (const b of sent) {
        expect(b.points.length).toBeLessThanOrEqual(200)
        expect(b.batchNo).toBe((lastBatch.get(b.strokeId) ?? -1) + 1)
        lastBatch.set(b.strokeId, b.batchNo)
        perStroke.set(b.strokeId, (perStroke.get(b.strokeId) ?? 0) + b.points.length)
      }
      expect(perStroke.size).toBe(2)
      for (const count of perStroke.values()) expect(count).toBeLessThanOrEqual(2000)
      // Every stroke is closed by a final batch.
      for (const id of perStroke.keys()) {
        expect(sent.filter((b) => b.strokeId === id).some((b) => b.final)).toBe(true)
      }
    })
  })
})
