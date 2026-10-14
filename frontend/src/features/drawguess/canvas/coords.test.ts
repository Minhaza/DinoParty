import { describe, expect, it } from 'vitest'

import {
  BRUSH_SIZES,
  CANVAS_ASPECT_RATIO,
  clientToVirtualCoord,
  DRAW_COLORS,
  getStrokePixelWidth,
  VIRTUAL_COORD_MAX,
  virtualToPixelCoord,
} from './coords'

describe('canvas coords and sizing', () => {
  it('converts client coords to virtual coords within 0..10000', () => {
    const rect = {
      left: 100,
      top: 50,
      width: 500,
      height: 312.5,
      right: 600,
      bottom: 362.5,
      x: 100,
      y: 50,
      toJSON: () => {},
    } as DOMRect

    // Top-left corner
    expect(clientToVirtualCoord(100, 50, rect)).toEqual([0, 0])

    // Center
    expect(clientToVirtualCoord(350, 206.25, rect)).toEqual([5000, 5000])

    // Bottom-right corner
    expect(clientToVirtualCoord(600, 362.5, rect)).toEqual([10000, 10000])

    // Out of bounds (clamped)
    expect(clientToVirtualCoord(50, 0, rect)).toEqual([0, 0])
    expect(clientToVirtualCoord(700, 500, rect)).toEqual([10000, 10000])
  })

  it('handles zero width/height gracefully', () => {
    const zeroRect = {
      left: 0,
      top: 0,
      width: 0,
      height: 0,
      right: 0,
      bottom: 0,
      x: 0,
      y: 0,
      toJSON: () => {},
    } as DOMRect

    expect(clientToVirtualCoord(50, 50, zeroRect)).toEqual([0, 0])
  })

  it('converts virtual coords to pixel coords correctly', () => {
    const width = 800
    const height = 500

    expect(virtualToPixelCoord([0, 0], width, height)).toEqual({ x: 0, y: 0 })
    expect(virtualToPixelCoord([5000, 5000], width, height)).toEqual({ x: 400, y: 250 })
    expect(virtualToPixelCoord([10000, 10000], width, height)).toEqual({ x: 800, y: 500 })
  })

  it('calculates stroke pixel width scaling with canvas width', () => {
    const width = 800
    const size1 = getStrokePixelWidth(1, width, false)
    const size2 = getStrokePixelWidth(2, width, false)
    const size3 = getStrokePixelWidth(3, width, false)
    const size4 = getStrokePixelWidth(4, width, false)

    expect(size1).toBeGreaterThanOrEqual(2)
    expect(size2).toBeGreaterThan(size1)
    expect(size3).toBeGreaterThan(size2)
    expect(size4).toBeGreaterThan(size3)

    // Eraser is 1.5x larger
    const eraserSize2 = getStrokePixelWidth(2, width, true)
    expect(eraserSize2).toBeGreaterThan(size2)
  })

  it('defines valid palette and brush sizes', () => {
    expect(DRAW_COLORS.length).toBeGreaterThanOrEqual(10)
    expect(BRUSH_SIZES.length).toBe(4)
    expect(CANVAS_ASPECT_RATIO).toBe(1.6)
    expect(VIRTUAL_COORD_MAX).toBe(10000)
  })
})
