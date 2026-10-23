import { act, fireEvent, render, screen } from '@testing-library/react'
import * as React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { DrawCanvas, type DrawCanvasProps } from './DrawCanvas'
import type { DrawStrokePayload, Stroke } from './types'

type HarnessProps = Omit<DrawCanvasProps, 'strokes' | 'onLocalStrokeStart' | 'onLocalPoints'> & {
  initialStrokes?: Stroke[]
}

function LocalResumeCanvas({ initialStrokes = [], ...props }: HarnessProps) {
  const [strokes, setStrokes] = React.useState<Stroke[]>(initialStrokes)
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

describe('DrawCanvas resumeStroke mode', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    Element.prototype.getBoundingClientRect = vi.fn().mockReturnValue({
      left: 0,
      top: 0,
      width: 800,
      height: 500,
      right: 800,
      bottom: 500,
    })
    HTMLCanvasElement.prototype.setPointerCapture = vi.fn()
    HTMLCanvasElement.prototype.releasePointerCapture = vi.fn()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('resumes an existing stroke with its strokeId and nextBatchNo', () => {
    const onSend = vi.fn().mockReturnValue(true)
    const initialStroke: Stroke = {
      strokeId: 'resume-stroke-1',
      tool: 'PENCIL',
      color: '#0072B2',
      size: 2,
      points: [
        [100, 100],
        [200, 200],
      ],
      closed: false,
    }

    render(
      <LocalResumeCanvas
        {...base}
        initialStrokes={[initialStroke]}
        onSendStrokeBatch={onSend}
        resumeStroke={{
          strokeId: 'resume-stroke-1',
          nextBatchNo: 3,
          pointCount: 150,
        }}
        strokeLockKey="turn-1"
      />,
    )

    const canvas = screen.getByRole('img', { name: /drawing canvas/i })

    act(() => {
      fireEvent.pointerDown(canvas, { clientX: 50, clientY: 50, pointerId: 1, button: 0 })
      fireEvent.pointerMove(canvas, { clientX: 100, clientY: 80, pointerId: 1 })
      vi.advanceTimersByTime(50)
      fireEvent.pointerUp(canvas, { pointerId: 1 })
    })

    const sent = batches(onSend)
    expect(sent.length).toBeGreaterThanOrEqual(1)
    expect(sent[0].strokeId).toBe('resume-stroke-1')
    expect(sent[0].batchNo).toBe(3)
    expect(sent[sent.length - 1].final).toBe(true)
  })

  it('respects pointCount when checking the 2000-point cap', () => {
    const onSend = vi.fn().mockReturnValue(true)
    render(
      <LocalResumeCanvas
        {...base}
        initialStrokes={[
          {
            strokeId: 'cap-stroke',
            tool: 'PENCIL',
            color: '#0072B2',
            size: 2,
            points: [],
            closed: false,
          },
        ]}
        onSendStrokeBatch={onSend}
        resumeStroke={{
          strokeId: 'cap-stroke',
          nextBatchNo: 10,
          pointCount: 1995,
        }}
        strokeLockKey="turn-1"
      />,
    )

    const canvas = screen.getByRole('img', { name: /drawing canvas/i })

    act(() => {
      fireEvent.pointerDown(canvas, { clientX: 10, clientY: 10, pointerId: 1, button: 0 })
      // Draw several distinct points to exceed remaining 5 points
      for (let i = 1; i <= 10; i++) {
        fireEvent.pointerMove(canvas, { clientX: 10 + i * 20, clientY: 10 + i * 20, pointerId: 1 })
      }
      vi.advanceTimersByTime(50)
    })

    const sent = batches(onSend)
    expect(sent[0].strokeId).toBe('cap-stroke')
    expect(sent[0].batchNo).toBe(10)
    // Stroke must be marked final because 1995 + points >= 2000
    expect(sent[0].final).toBe(true)
  })
})
