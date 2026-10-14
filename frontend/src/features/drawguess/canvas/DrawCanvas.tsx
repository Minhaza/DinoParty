import * as React from 'react'

import { generateUUID } from '@/lib/uuid'
import { useDrawGuessStore } from '../drawGuessStore'
import type { DrawStrokePayload, Stroke, StrokeTool } from '../types'
import { CANVAS_ASPECT_RATIO, CANVAS_BG_COLOR, clientToVirtualCoord } from './coords'
import { renderAllStrokes } from './render'

export interface DrawCanvasProps {
  isDrawer: boolean
  isDrawingPhase: boolean
  tool: StrokeTool
  color: string
  size: number
  onSendStrokeBatch: (payload: DrawStrokePayload) => void
}

const BATCH_INTERVAL_MS = 50
const MAX_POINTS_PER_BATCH = 200

export function DrawCanvas({
  isDrawer,
  isDrawingPhase,
  tool,
  color,
  size,
  onSendStrokeBatch,
}: DrawCanvasProps) {
  const containerRef = React.useRef<HTMLDivElement | null>(null)
  const canvasRef = React.useRef<HTMLCanvasElement | null>(null)

  const [dimensions, setDimensions] = React.useState<{ width: number; height: number }>({
    width: 640,
    height: 400,
  })

  const strokes = useDrawGuessStore((s) => s.strokes)
  const addLocalStroke = useDrawGuessStore((s) => s.addLocalStroke)
  const appendLocalPoints = useDrawGuessStore((s) => s.appendLocalPoints)

  // Active stroke tracking for the drawer
  const activeStrokeRef = React.useRef<{
    strokeId: string
    pointerId: number
    batchNo: number
    tool: StrokeTool
    color: string
    size: number
    bufferedPoints: [number, number][]
    intervalId: ReturnType<typeof setInterval> | null
  } | null>(null)

  // Keep latest props in refs for timer callbacks
  const onSendBatchRef = React.useRef(onSendStrokeBatch)
  React.useEffect(() => {
    onSendBatchRef.current = onSendStrokeBatch
  }, [onSendStrokeBatch])

  // Resize observer to ensure responsive 16:10 canvas fitting container
  React.useEffect(() => {
    const container = containerRef.current
    if (!container) return

    const updateSize = () => {
      const rect = container.getBoundingClientRect()
      const availWidth = Math.max(280, rect.width)
      const availHeight = Math.max(180, rect.height)

      let w = availWidth
      let h = w / CANVAS_ASPECT_RATIO

      if (h > availHeight) {
        h = availHeight
        w = h * CANVAS_ASPECT_RATIO
      }

      setDimensions({
        width: Math.floor(w),
        height: Math.floor(h),
      })
    }

    updateSize()
    if (typeof ResizeObserver !== 'undefined') {
      const observer = new ResizeObserver(updateSize)
      observer.observe(container)

      return () => observer.disconnect()
    }
  }, [])

  // Redraw canvas whenever strokes or dimensions change
  React.useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const dpr = typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1
    const { width, height } = dimensions

    canvas.width = Math.floor(width * dpr)
    canvas.height = Math.floor(height * dpr)
    canvas.style.width = `${width}px`
    canvas.style.height = `${height}px`

    ctx.save()
    ctx.scale(dpr, dpr)
    renderAllStrokes(ctx, width, height, strokes, CANVAS_BG_COLOR)
    ctx.restore()
  }, [strokes, dimensions])

  const flushBatch = React.useCallback((isFinal: boolean) => {
    const active = activeStrokeRef.current
    if (!active) return

    const pointsToSend = [...active.bufferedPoints]
    active.bufferedPoints = []

    // If points are empty and not final, skip
    if (pointsToSend.length === 0 && !isFinal) return

    // If points are empty and isFinal and batchNo === 0, no points were drawn at all
    if (pointsToSend.length === 0 && isFinal && active.batchNo === 0) return

    const batch: DrawStrokePayload = {
      strokeId: active.strokeId,
      batchNo: active.batchNo,
      tool: active.tool,
      color: active.color,
      size: active.size,
      points: pointsToSend,
      final: isFinal,
    }

    active.batchNo += 1
    onSendBatchRef.current(batch)
  }, [])

  const handlePointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (!isDrawer || !isDrawingPhase) return
    if (e.button !== 0 && e.pointerType === 'mouse') return // left button only

    const canvas = canvasRef.current
    if (!canvas) return

    try {
      e.currentTarget.setPointerCapture(e.pointerId)
    } catch {
      // Ignore if pointer capture fails in test environment
    }

    const rect = canvas.getBoundingClientRect()
    const pt = clientToVirtualCoord(e.clientX, e.clientY, rect)
    const strokeId = generateUUID()

    // Add local stroke to store immediately for preview
    const localStroke: Stroke = {
      strokeId,
      tool,
      color,
      size,
      points: [pt],
      closed: false,
    }
    addLocalStroke(localStroke)

    // Set up active stroke state
    activeStrokeRef.current = {
      strokeId,
      pointerId: e.pointerId,
      batchNo: 0,
      tool,
      color,
      size,
      bufferedPoints: [pt],
      intervalId: setInterval(() => {
        flushBatch(false)
      }, BATCH_INTERVAL_MS),
    }
  }

  const handlePointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const active = activeStrokeRef.current
    if (!active || e.pointerId !== active.pointerId) return

    const canvas = canvasRef.current
    if (!canvas) return

    const rect = canvas.getBoundingClientRect()
    const pt = clientToVirtualCoord(e.clientX, e.clientY, rect)

    // Append to local store stroke for instant preview
    appendLocalPoints(active.strokeId, [pt], false)

    active.bufferedPoints.push(pt)

    // Flush if batch buffer reaches limit
    if (active.bufferedPoints.length >= MAX_POINTS_PER_BATCH) {
      flushBatch(false)
    }
  }

  const endStroke = (pointerId: number) => {
    const active = activeStrokeRef.current
    if (!active || pointerId !== active.pointerId) return

    if (active.intervalId) {
      clearInterval(active.intervalId)
    }

    appendLocalPoints(active.strokeId, [], true)
    flushBatch(true)

    activeStrokeRef.current = null
  }

  const handlePointerUp = (e: React.PointerEvent<HTMLCanvasElement>) => {
    try {
      e.currentTarget.releasePointerCapture(e.pointerId)
    } catch {
      // Ignore if release fails
    }
    endStroke(e.pointerId)
  }

  const handlePointerCancel = (e: React.PointerEvent<HTMLCanvasElement>) => {
    endStroke(e.pointerId)
  }

  const canDraw = isDrawer && isDrawingPhase

  return (
    <div
      ref={containerRef}
      className="relative flex items-center justify-center w-full h-full min-h-[220px] overflow-hidden select-none"
    >
      <canvas
        ref={canvasRef}
        role="img"
        aria-label={canDraw ? 'Drawing canvas (interactive)' : 'Draw & Guess canvas view'}
        className={`touch-none rounded-xl shadow-xs transition-shadow ${
          canDraw
            ? 'cursor-crosshair ring-2 ring-primary/30 hover:ring-primary/50'
            : 'cursor-default ring-1 ring-border/60'
        }`}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerCancel}
      />
    </div>
  )
}
