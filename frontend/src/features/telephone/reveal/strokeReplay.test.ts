import { describe, expect, it } from 'vitest'

import type { Stroke } from '@/features/drawing/types'

import { replayDurationMs, strokePrefix, totalPoints } from './strokeReplay'

const stroke = (id: string, n: number, tool: Stroke['tool'] = 'PENCIL'): Stroke => ({
  strokeId: id,
  tool,
  color: '#2B2B2B',
  size: 2,
  points: Array.from({ length: n }, (_, i) => [i * 100, i * 100] as [number, number]),
})

describe('stroke replay', () => {
  const strokes = [stroke('a', 3), stroke('b', 2, 'ERASER'), stroke('c', 4)]

  it('counts the points of a drawing', () => {
    expect(totalPoints(strokes)).toBe(9)
    expect(totalPoints([])).toBe(0)
  })

  it('cuts the drawing after n points in drawing order, keeping each stroke style', () => {
    const prefix = strokePrefix(strokes, 4)
    expect(prefix.map((s) => [s.strokeId, s.points.length, s.tool])).toEqual([
      ['a', 3, 'PENCIL'],
      ['b', 1, 'ERASER'],
    ])
    expect(strokePrefix(strokes, 0)).toEqual([])
    expect(strokePrefix(strokes, 99)).toEqual(strokes)
  })

  it('never edits the stored strokes', () => {
    strokePrefix(strokes, 4)
    expect(strokes[1].points).toHaveLength(2)
  })

  it('takes about 3 s for a large drawing and stays short for a small one', () => {
    expect(replayDurationMs(0)).toBe(0)
    expect(replayDurationMs(10)).toBe(600)
    expect(replayDurationMs(400)).toBe(1600)
    expect(replayDurationMs(12_000)).toBe(3000)
  })
})
