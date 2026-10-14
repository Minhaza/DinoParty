import type { Stroke } from '../types'
import { CANVAS_BG_COLOR, getStrokePixelWidth, virtualToPixelCoord } from './coords'

/**
 * Draws a single stroke onto a 2D rendering context.
 */
export function drawStrokeToContext(
  ctx: CanvasRenderingContext2D,
  stroke: Stroke,
  width: number,
  height: number,
  bgColor = CANVAS_BG_COLOR,
): void {
  if (!stroke.points || stroke.points.length === 0) return

  const isEraser = stroke.tool === 'ERASER'
  const strokeColor = isEraser ? bgColor : stroke.color
  const lineWidth = getStrokePixelWidth(stroke.size, width, isEraser)

  ctx.save()
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  ctx.strokeStyle = strokeColor
  ctx.fillStyle = strokeColor
  ctx.lineWidth = lineWidth

  const pixelPoints = stroke.points.map((p) => virtualToPixelCoord(p, width, height))

  if (pixelPoints.length === 1) {
    // Single tap/point: render dot
    const pt = pixelPoints[0]
    ctx.beginPath()
    ctx.arc(pt.x, pt.y, lineWidth / 2, 0, Math.PI * 2)
    ctx.fill()
    ctx.restore()
    return
  }

  if (pixelPoints.length === 2) {
    // Simple line between 2 points
    ctx.beginPath()
    ctx.moveTo(pixelPoints[0].x, pixelPoints[0].y)
    ctx.lineTo(pixelPoints[1].x, pixelPoints[1].y)
    ctx.stroke()
    ctx.restore()
    return
  }

  // Smooth quadratic curves through midpoints for 3+ points
  ctx.beginPath()
  ctx.moveTo(pixelPoints[0].x, pixelPoints[0].y)

  for (let i = 1; i < pixelPoints.length - 1; i++) {
    const xc = (pixelPoints[i].x + pixelPoints[i + 1].x) / 2
    const yc = (pixelPoints[i].y + pixelPoints[i + 1].y) / 2
    ctx.quadraticCurveTo(pixelPoints[i].x, pixelPoints[i].y, xc, yc)
  }

  // Final segment
  const lastIndex = pixelPoints.length - 1
  ctx.lineTo(pixelPoints[lastIndex].x, pixelPoints[lastIndex].y)
  ctx.stroke()

  ctx.restore()
}

/**
 * Clears and redraws all strokes in chronological order.
 */
export function renderAllStrokes(
  ctx: CanvasRenderingContext2D,
  width: number,
  height: number,
  strokes: Stroke[],
  bgColor = CANVAS_BG_COLOR,
): void {
  // Clear and fill canvas background
  ctx.save()
  ctx.fillStyle = bgColor
  ctx.fillRect(0, 0, width, height)
  ctx.restore()

  for (const stroke of strokes) {
    drawStrokeToContext(ctx, stroke, width, height, bgColor)
  }
}
