import * as React from 'react'

import { generateUUID } from '@/lib/uuid'
import type { DrawStrokePayload, Stroke, StrokeRejection, StrokeTool } from './types'
import { CANVAS_ASPECT_RATIO, CANVAS_BG_COLOR, clientToVirtualCoord } from './coords'
import { renderAllStrokes } from './render'

/**
 * The canvas engine shared by every drawing game. It owns pointer handling and batching only; the
 * game supplies the stroke list and stores local previews, so each game keeps its own state.
 */
export interface DrawCanvasProps {
  isDrawer: boolean
  isDrawingPhase: boolean
  tool: StrokeTool
  color: string
  size: number
  /** Strokes to render, in drawing order (server state plus the local preview). */
  strokes: Stroke[]
  /** The server budget is exhausted: drawing stays disabled until undo/clear. */
  canvasFull?: boolean
  /** Latest rejection of one of this client's batches; a new `nonce` re-triggers handling. */
  strokeRejection?: StrokeRejection | null
  /** Sends one batch; returns `false` when it could not be sent (socket down). */
  onSendStrokeBatch?: (payload: DrawStrokePayload) => boolean | void
  /** Stores a stroke started locally (preview). */
  onLocalStrokeStart?: (stroke: Stroke) => void
  /** Appends preview points to a local stroke, closing it when `isFinal`. */
  onLocalPoints?: (strokeId: string, points: [number, number][], isFinal: boolean) => void
  /** Accessible name; defaults to a generic one per mode. */
  ariaLabel?: string
}

const noop = () => {}

const BATCH_INTERVAL_MS = 50
// Server limits (api-contracts §3.9): 200 points per batch, 2000 per stroke.
const MAX_POINTS_PER_BATCH = 200
const MAX_POINTS_PER_STROKE = 2000
// Points closer than this (in 0..10000 units, ≈ 1 px on an 800 px canvas) add nothing visible but
// cost budget, so they are dropped before sending.
const MIN_POINT_DISTANCE = 12

type Point = [number, number]

interface ActiveStroke {
  strokeId: string
  pointerId: number
  batchNo: number
  /** Points of this stroke the server has been sent. */
  sentPoints: number
  tool: StrokeTool
  color: string
  size: number
  bufferedPoints: Point[]
  /** Last point drawn locally (sent or buffered); new strokes continue from here. */
  lastPoint: Point
  /** Last point handed to the server; a restarted stroke begins with it so the line stays joined. */
  lastSentPoint: Point | null
  /** Set when a batch failed or was rejected: the next flush continues with a new `strokeId`. */
  needsRestart: boolean
  intervalId: ReturnType<typeof setInterval> | null
}

export function DrawCanvas({
  isDrawer,
  isDrawingPhase,
  tool,
  color,
  size,
  strokes,
  canvasFull = false,
  strokeRejection = null,
  onSendStrokeBatch = noop,
  onLocalStrokeStart = noop,
  onLocalPoints = noop,
  ariaLabel,
}: DrawCanvasProps) {
  const containerRef = React.useRef<HTMLDivElement | null>(null)
  const canvasRef = React.useRef<HTMLCanvasElement | null>(null)

  const [dimensions, setDimensions] = React.useState<{ width: number; height: number }>({
    width: 640,
    height: 400,
  })

  const activeStrokeRef = React.useRef<ActiveStroke | null>(null)
  // Every strokeId the current gesture has used, so a late rejection of an earlier one still counts.
  const gestureStrokeIdsRef = React.useRef<Set<string>>(new Set())

  // Keep latest props in refs for timer callbacks
  const onSendBatchRef = React.useRef(onSendStrokeBatch)
  const onLocalStrokeStartRef = React.useRef(onLocalStrokeStart)
  const onLocalPointsRef = React.useRef(onLocalPoints)
  React.useEffect(() => {
    onSendBatchRef.current = onSendStrokeBatch
    onLocalStrokeStartRef.current = onLocalStrokeStart
    onLocalPointsRef.current = onLocalPoints
  }, [onSendStrokeBatch, onLocalStrokeStart, onLocalPoints])

  const addLocalStroke = React.useCallback((stroke: Stroke) => {
    onLocalStrokeStartRef.current(stroke)
  }, [])
  const appendLocalPoints = React.useCallback(
    (strokeId: string, points: [number, number][], isFinal: boolean) => {
      onLocalPointsRef.current(strokeId, points, isFinal)
    },
    [],
  )

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

  const send = React.useCallback((batch: DrawStrokePayload): boolean => {
    return onSendBatchRef.current(batch) !== false
  }, [])

  /** Starts a new server stroke for the ongoing gesture, beginning at `points[0]`. */
  const restartStroke = React.useCallback(
    (active: ActiveStroke, points: Point[], isFinal: boolean): boolean => {
      const strokeId = generateUUID()
      const batch: DrawStrokePayload = {
        strokeId,
        batchNo: 0,
        tool: active.tool,
        color: active.color,
        size: active.size,
        points,
        final: isFinal,
      }
      if (!send(batch)) return false

      addLocalStroke({
        strokeId,
        tool: active.tool,
        color: active.color,
        size: active.size,
        points,
        closed: isFinal,
      })
      gestureStrokeIdsRef.current.add(strokeId)
      active.strokeId = strokeId
      active.batchNo = 1
      active.sentPoints = points.length
      active.lastSentPoint = points[points.length - 1]
      active.needsRestart = false
      return true
    },
    [send, addLocalStroke],
  )

  /** Sends at most one batch of the active stroke; returns whether one was sent. */
  const flushOnce = React.useCallback(
    (active: ActiveStroke, isFinal: boolean): boolean => {
      const buffered = active.bufferedPoints
      if (buffered.length === 0 && !isFinal) return false

      if (active.needsRestart) {
        // Nothing new to draw: the broken stroke stays open on the server until the drawer's next
        // stroke closes it implicitly (api-contracts §3.9).
        if (buffered.length === 0) return false
        const start = active.lastSentPoint ? [active.lastSentPoint] : []
        const points = [...start, ...buffered].slice(0, MAX_POINTS_PER_BATCH)
        const rest = buffered.slice(points.length - start.length)
        if (!restartStroke(active, points, isFinal && rest.length === 0)) return false
        active.bufferedPoints = rest
        return true
      }

      // A stroke never exceeds the server's per-stroke limit: close it and continue in a new one.
      const room = MAX_POINTS_PER_STROKE - active.sentPoints
      const points = buffered.slice(0, Math.min(room, MAX_POINTS_PER_BATCH))
      const rest = buffered.slice(points.length)
      const strokeIsFull = active.sentPoints + points.length >= MAX_POINTS_PER_STROKE

      // The first batch needs at least one point; an empty first+final batch means nothing was drawn.
      if (points.length === 0 && active.batchNo === 0) return false

      const batch: DrawStrokePayload = {
        strokeId: active.strokeId,
        batchNo: active.batchNo,
        tool: active.tool,
        color: active.color,
        size: active.size,
        points,
        final: (isFinal && rest.length === 0) || strokeIsFull,
      }

      if (!send(batch)) {
        // The socket is down: keep the points and continue in a fresh stroke once it is back,
        // because the server may or may not have seen earlier batches of this one.
        active.needsRestart = true
        return false
      }

      active.batchNo += 1
      active.sentPoints += points.length
      if (points.length > 0) active.lastSentPoint = points[points.length - 1]
      active.bufferedPoints = rest

      if (strokeIsFull) {
        appendLocalPoints(active.strokeId, [], true)
        active.needsRestart = true
      }
      return true
    },
    [send, restartStroke, appendLocalPoints],
  )

  const flushBatch = React.useCallback(
    (isFinal: boolean) => {
      const active = activeStrokeRef.current
      if (!active) return
      // Normally one batch per tick; more only when the buffer holds over a batch or the stroke ends.
      while (flushOnce(active, isFinal)) {
        const pending = active.bufferedPoints.length
        if (pending === 0 || (!isFinal && pending < MAX_POINTS_PER_BATCH)) break
      }
    },
    [flushOnce],
  )

  const stopActiveStroke = React.useCallback(() => {
    const active = activeStrokeRef.current
    if (!active) return
    if (active.intervalId) clearInterval(active.intervalId)
    activeStrokeRef.current = null
    gestureStrokeIdsRef.current = new Set()
  }, [])

  const canDraw = isDrawer && isDrawingPhase && !canvasFull

  // A batch of the ongoing gesture was rejected (api-contracts §3.9): continue in a new stroke, or
  // close the stroke and stop when the canvas budget is exhausted.
  React.useEffect(() => {
    const active = activeStrokeRef.current
    if (!strokeRejection || !active) return
    if (!gestureStrokeIdsRef.current.has(strokeRejection.strokeId)) return
    if (strokeRejection.kind === 'FULL') {
      // A closing batch without points always fits the budget.
      if (active.batchNo > 0 && !active.needsRestart) {
        send({
          strokeId: active.strokeId,
          batchNo: active.batchNo,
          tool: active.tool,
          color: active.color,
          size: active.size,
          points: [],
          final: true,
        })
      }
      stopActiveStroke()
    } else if (strokeRejection.strokeId === active.strokeId) {
      active.needsRestart = true
    }
  }, [strokeRejection, send, stopActiveStroke])

  // The phase ended (or the role changed) mid-gesture: further batches would be STALE_ACTION.
  React.useEffect(() => {
    if (!isDrawer || !isDrawingPhase) stopActiveStroke()
  }, [isDrawer, isDrawingPhase, stopActiveStroke])

  React.useEffect(() => stopActiveStroke, [stopActiveStroke])

  const handlePointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (!canDraw) return
    if (e.button !== 0 && e.pointerType === 'mouse') return // left button only
    if (activeStrokeRef.current) return // one gesture at a time (second finger, pen + mouse)

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
    gestureStrokeIdsRef.current = new Set([strokeId])

    activeStrokeRef.current = {
      strokeId,
      pointerId: e.pointerId,
      batchNo: 0,
      sentPoints: 0,
      tool,
      color,
      size,
      bufferedPoints: [pt],
      lastPoint: pt,
      lastSentPoint: null,
      needsRestart: false,
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
    const [lx, ly] = active.lastPoint
    if (Math.hypot(pt[0] - lx, pt[1] - ly) < MIN_POINT_DISTANCE) return
    active.lastPoint = pt

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

  return (
    <div
      ref={containerRef}
      className="relative flex items-center justify-center w-full h-full min-h-[220px] overflow-hidden select-none"
    >
      <canvas
        ref={canvasRef}
        role="img"
        aria-label={
          ariaLabel ?? (canDraw ? 'Drawing canvas (interactive)' : 'Draw & Guess canvas view')
        }
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
      {isDrawer && isDrawingPhase && canvasFull && (
        <p
          role="status"
          className="absolute bottom-3 left-1/2 -translate-x-1/2 rounded-full bg-amber-100 px-3 py-1 text-xs font-semibold text-amber-900 shadow-sm"
        >
          Canvas full — undo or clear to keep drawing
        </p>
      )}
    </div>
  )
}
