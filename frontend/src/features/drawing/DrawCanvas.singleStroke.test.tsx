import { act, fireEvent, render, screen } from '@testing-library/react'
import * as React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { DrawCanvas, type DrawCanvasProps } from './DrawCanvas'
import type { DrawStrokePayload, Stroke, StrokeRejection } from './types'

// The optional one-stroke mode used by Dino Impostor ([DI-6]): one gesture is one server stroke,
// and the canvas stays locked until the turn changes. Default behaviour is covered by DrawCanvas.test.

type HarnessProps = Omit<DrawCanvasProps, 'strokes' | 'onLocalStrokeStart' | 'onLocalPoints'>

function LocalCanvas(props: HarnessProps) {
  const [strokes, setStrokes] = React.useState<Stroke[]>([])
  return (
    <DrawCanvas
      {...props}
      strokes={strokes}
      onLocalStrokeStart={(s) => setStrokes((prev) => [...prev, s])}
      onLocalPoints={(id, pts, isFinal) =>
        setStrokes((prev) =>
          prev.map((s) =>
            s.strokeId === id
              ? { ...s, points: [...s.points, ...pts], closed: isFinal || s.closed }
              : s,
          ),
        )
      }
    />
  )
}

const base = {
  isDrawer: true,
  isDrawingPhase: true,
  tool: 'PENCIL' as const,
  color: '#0072B2',
  size: 2,
  singleStroke: true,
}

const batches = (fn: ReturnType<typeof vi.fn>) =>
  fn.mock.calls.map((c) => c[0] as DrawStrokePayload)

const drawLine = (canvas: HTMLElement, pointerId: number, x0: number) => {
  fireEvent.pointerDown(canvas, { clientX: x0, clientY: 50, pointerId, button: 0 })
  fireEvent.pointerMove(canvas, { clientX: x0 + 80, clientY: 100, pointerId })
  vi.advanceTimersByTime(50)
  fireEvent.pointerUp(canvas, { pointerId })
}

describe('DrawCanvas singleStroke mode', () => {
  beforeEach(() => {
    vi.useFakeTimers()
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
    HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue(null)
    Element.prototype.setPointerCapture = vi.fn()
    Element.prototype.releasePointerCapture = vi.fn()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('sends exactly one stroke and ignores a second gesture', () => {
    const onSend = vi.fn()
    render(<LocalCanvas {...base} onSendStrokeBatch={onSend} />)
    const canvas = screen.getByRole('img')

    drawLine(canvas, 1, 80)
    const count = onSend.mock.calls.length
    drawLine(canvas, 2, 300)
    vi.advanceTimersByTime(200)

    expect(onSend).toHaveBeenCalledTimes(count)
    const sent = batches(onSend)
    expect(new Set(sent.map((b) => b.strokeId)).size).toBe(1)
    expect(sent[sent.length - 1].final).toBe(true)
    expect(canvas).toHaveClass('cursor-default')
  })

  it('re-arms when strokeLockKey changes', () => {
    const onSend = vi.fn()
    const { rerender } = render(
      <LocalCanvas {...base} strokeLockKey="turn-1" onSendStrokeBatch={onSend} />,
    )
    const canvas = screen.getByRole('img')
    drawLine(canvas, 1, 80)

    rerender(<LocalCanvas {...base} strokeLockKey="turn-2" onSendStrokeBatch={onSend} />)
    drawLine(canvas, 2, 300)

    expect(new Set(batches(onSend).map((b) => b.strokeId)).size).toBe(2)
  })

  it('closes the stroke at the 2000-point cap instead of starting another', () => {
    const onSend = vi.fn()
    render(<LocalCanvas {...base} onSendStrokeBatch={onSend} />)
    const canvas = screen.getByRole('img')
    fireEvent.pointerDown(canvas, { clientX: 0, clientY: 0, pointerId: 1, button: 0 })
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
    expect(new Set(sent.map((b) => b.strokeId)).size).toBe(1)
    expect(sent.reduce((n, b) => n + b.points.length, 0)).toBeLessThanOrEqual(2000)
    expect(sent.filter((b) => b.final)).toHaveLength(1)
  })

  it('does not restart in a new stroke after a send failure', () => {
    let connected = true
    const onSend = vi.fn(() => connected)
    render(<LocalCanvas {...base} onSendStrokeBatch={onSend} />)
    const canvas = screen.getByRole('img')
    fireEvent.pointerDown(canvas, { clientX: 80, clientY: 50, pointerId: 1, button: 0 })
    vi.advanceTimersByTime(50)
    connected = false
    fireEvent.pointerMove(canvas, { clientX: 160, clientY: 100, pointerId: 1 })
    vi.advanceTimersByTime(50)
    connected = true
    fireEvent.pointerMove(canvas, { clientX: 240, clientY: 150, pointerId: 1 })
    vi.advanceTimersByTime(50)
    fireEvent.pointerUp(canvas, { pointerId: 1 })

    expect(new Set(batches(onSend).map((b) => b.strokeId)).size).toBe(1)
  })

  it('does not restart in a new stroke after a rejection', () => {
    const onSend = vi.fn()
    const { rerender } = render(<LocalCanvas {...base} onSendStrokeBatch={onSend} />)
    const canvas = screen.getByRole('img')
    fireEvent.pointerDown(canvas, { clientX: 80, clientY: 50, pointerId: 1, button: 0 })
    vi.advanceTimersByTime(50)
    const first = batches(onSend)[0]

    const rejection: StrokeRejection = { strokeId: first.strokeId, kind: 'RESTART', nonce: 1 }
    act(() => {
      rerender(<LocalCanvas {...base} strokeRejection={rejection} onSendStrokeBatch={onSend} />)
    })
    fireEvent.pointerMove(canvas, { clientX: 160, clientY: 100, pointerId: 1 })
    vi.advanceTimersByTime(50)
    fireEvent.pointerUp(canvas, { pointerId: 1 })

    expect(batches(onSend).every((b) => b.strokeId === first.strokeId)).toBe(true)
  })
})
