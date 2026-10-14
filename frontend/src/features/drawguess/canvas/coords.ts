export const VIRTUAL_COORD_MAX = 10000
export const CANVAS_ASPECT_RATIO = 16 / 10
export const CANVAS_BG_COLOR = '#FFFDF8'

export const DRAW_COLORS = [
  { id: 'charcoal', hex: '#2B2B2B', label: 'Charcoal' },
  { id: 'white', hex: '#FFFFFF', label: 'White' },
  { id: 'red', hex: '#EF4444', label: 'Red' },
  { id: 'orange', hex: '#F97316', label: 'Orange' },
  { id: 'yellow', hex: '#EAB308', label: 'Yellow' },
  { id: 'green', hex: '#16A34A', label: 'Green' },
  { id: 'teal', hex: '#0D9488', label: 'Teal' },
  { id: 'blue', hex: '#2563EB', label: 'Blue' },
  { id: 'purple', hex: '#9333EA', label: 'Purple' },
  { id: 'pink', hex: '#EC4899', label: 'Pink' },
  { id: 'brown', hex: '#78350F', label: 'Brown' },
  { id: 'gray', hex: '#64748B', label: 'Gray' },
] as const

export const BRUSH_SIZES = [
  { size: 1, label: 'Thin', scale: 0.0035, minPx: 2 },
  { size: 2, label: 'Medium', scale: 0.008, minPx: 4 },
  { size: 3, label: 'Thick', scale: 0.018, minPx: 8 },
  { size: 4, label: 'Heavy', scale: 0.032, minPx: 14 },
] as const

export interface PixelPoint {
  x: number
  y: number
}

/**
 * Converts client/pointer coordinates to normalized integer coordinates (0..10000).
 */
export function clientToVirtualCoord(
  clientX: number,
  clientY: number,
  canvasRect: DOMRect,
): [number, number] {
  if (canvasRect.width <= 0 || canvasRect.height <= 0) {
    return [0, 0]
  }

  const relX = clientX - canvasRect.left
  const relY = clientY - canvasRect.top

  const clampedX = Math.min(canvasRect.width, Math.max(0, relX))
  const clampedY = Math.min(canvasRect.height, Math.max(0, relY))

  const vx = Math.round((clampedX / canvasRect.width) * VIRTUAL_COORD_MAX)
  const vy = Math.round((clampedY / canvasRect.height) * VIRTUAL_COORD_MAX)

  return [vx, vy]
}

/**
 * Converts a normalized virtual point [0..10000] to canvas pixel coordinates.
 */
export function virtualToPixelCoord(
  point: [number, number],
  width: number,
  height: number,
): PixelPoint {
  return {
    x: (point[0] / VIRTUAL_COORD_MAX) * width,
    y: (point[1] / VIRTUAL_COORD_MAX) * height,
  }
}

/**
 * Calculates responsive line width in pixels based on canvas width and size index (1..4).
 */
export function getStrokePixelWidth(
  sizeIndex: number,
  canvasWidth: number,
  isEraser = false,
): number {
  const config = BRUSH_SIZES.find((s) => s.size === sizeIndex) ?? BRUSH_SIZES[1] // default medium

  const calculated = canvasWidth * config.scale * (isEraser ? 1.5 : 1)
  return Math.max(config.minPx, calculated)
}
