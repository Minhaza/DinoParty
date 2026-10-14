import { beforeEach, describe, expect, it, vi } from 'vitest'

import { CANVAS_BG_COLOR } from './coords'
import { drawStrokeToContext, renderAllStrokes } from './render'
import type { Stroke } from '../types'

describe('canvas render utilities', () => {
  let mockContext: CanvasRenderingContext2D

  beforeEach(() => {
    mockContext = {
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
      lineCap: 'butt',
      lineJoin: 'miter',
      strokeStyle: '#000',
      fillStyle: '#000',
      lineWidth: 1,
    } as unknown as CanvasRenderingContext2D
  })

  it('renders single point as circle', () => {
    const stroke: Stroke = {
      strokeId: 's-1',
      tool: 'PENCIL',
      color: '#FF0000',
      size: 2,
      points: [[5000, 5000]],
    }

    drawStrokeToContext(mockContext, stroke, 1000, 625)

    expect(mockContext.beginPath).toHaveBeenCalled()
    expect(mockContext.arc).toHaveBeenCalledWith(500, 312.5, expect.any(Number), 0, Math.PI * 2)
    expect(mockContext.fill).toHaveBeenCalled()
    expect(mockContext.strokeStyle).toBe('#FF0000')
  })

  it('renders two points as line segment', () => {
    const stroke: Stroke = {
      strokeId: 's-2',
      tool: 'PENCIL',
      color: '#00FF00',
      size: 2,
      points: [
        [0, 0],
        [10000, 10000],
      ],
    }

    drawStrokeToContext(mockContext, stroke, 1000, 625)

    expect(mockContext.beginPath).toHaveBeenCalled()
    expect(mockContext.moveTo).toHaveBeenCalledWith(0, 0)
    expect(mockContext.lineTo).toHaveBeenCalledWith(1000, 625)
    expect(mockContext.stroke).toHaveBeenCalled()
  })

  it('renders 3+ points using quadratic curves', () => {
    const stroke: Stroke = {
      strokeId: 's-3',
      tool: 'PENCIL',
      color: '#0000FF',
      size: 2,
      points: [
        [1000, 1000],
        [2000, 3000],
        [4000, 4000],
      ],
    }

    drawStrokeToContext(mockContext, stroke, 1000, 625)

    expect(mockContext.beginPath).toHaveBeenCalled()
    expect(mockContext.moveTo).toHaveBeenCalled()
    expect(mockContext.quadraticCurveTo).toHaveBeenCalled()
    expect(mockContext.lineTo).toHaveBeenCalled()
    expect(mockContext.stroke).toHaveBeenCalled()
  })

  it('renders eraser strokes with background color', () => {
    const stroke: Stroke = {
      strokeId: 's-eraser',
      tool: 'ERASER',
      color: '#000000', // ignored
      size: 3,
      points: [
        [0, 0],
        [5000, 5000],
      ],
    }

    drawStrokeToContext(mockContext, stroke, 1000, 625, CANVAS_BG_COLOR)

    expect(mockContext.strokeStyle).toBe(CANVAS_BG_COLOR)
    expect(mockContext.fillStyle).toBe(CANVAS_BG_COLOR)
  })

  it('renderAllStrokes clears canvas with background color and renders list', () => {
    const strokes: Stroke[] = [
      {
        strokeId: 's-1',
        tool: 'PENCIL',
        color: '#FF0000',
        size: 1,
        points: [[1000, 1000]],
      },
      {
        strokeId: 's-2',
        tool: 'PENCIL',
        color: '#00FF00',
        size: 2,
        points: [
          [2000, 2000],
          [3000, 3000],
        ],
      },
    ]

    renderAllStrokes(mockContext, 800, 500, strokes)

    expect(mockContext.fillRect).toHaveBeenCalledWith(0, 0, 800, 500)
    expect(mockContext.stroke).toHaveBeenCalledTimes(1)
    expect(mockContext.fill).toHaveBeenCalledTimes(1)
  })
})
