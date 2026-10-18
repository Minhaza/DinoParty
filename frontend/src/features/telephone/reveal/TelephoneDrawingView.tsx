import * as React from 'react'
import { FastForward, ImageOff } from 'lucide-react'

import { CANVAS_ASPECT_RATIO, CANVAS_BG_COLOR } from '@/features/drawing/coords'
import { renderAllStrokes } from '@/features/drawing/render'
import type { Stroke } from '@/features/drawing/types'
import { cn } from '@/lib/utils'

import { replayDurationMs, strokePrefix, totalPoints } from './strokeReplay'
import { usePrefersReducedMotion } from './usePrefersReducedMotion'

export interface TelephoneDrawingViewProps {
  /** The stored strokes; null when the client does not hold them (withheld by a snapshot). */
  strokes: Stroke[] | null
  /** Accessible name of the drawing. */
  label: string
  /** Animate the strokes in drawing order once (skippable; off under reduced motion). */
  replay?: boolean
  /** A small preview: no replay, no controls. */
  compact?: boolean
  className?: string
}

/**
 * A read-only drawing in the shared format: normalized 0–10 000 coordinates on a 16:10 canvas,
 * drawn by the same renderer as the live canvas (eraser strokes paint the paper colour), sharp on
 * high-DPI screens and redrawn only when its strokes or size change.
 */
export const TelephoneDrawingView = React.memo(function TelephoneDrawingView({
  strokes,
  label,
  replay = false,
  compact = false,
  className,
}: TelephoneDrawingViewProps) {
  const containerRef = React.useRef<HTMLDivElement | null>(null)
  const canvasRef = React.useRef<HTMLCanvasElement | null>(null)
  const [width, setWidth] = React.useState(0)
  const reducedMotion = usePrefersReducedMotion()
  const animate = replay && !compact && !reducedMotion && strokes !== null
  const [replaying, setReplaying] = React.useState(animate)
  const held = strokes !== null

  // The canvas only exists once the strokes do: a withheld drawing that arrives later (a resync
  // while the cursor is on it) must still be measured.
  React.useEffect(() => {
    const container = containerRef.current
    if (!held || !container) return
    const measure = () => setWidth(Math.floor(container.getBoundingClientRect().width))
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measure)
    observer.observe(container)
    return () => observer.disconnect()
  }, [held])

  const draw = React.useCallback(
    (visible: Stroke[]) => {
      const canvas = canvasRef.current
      const ctx = canvas?.getContext('2d')
      if (!canvas || !ctx || width <= 0) return
      const height = Math.floor(width / CANVAS_ASPECT_RATIO)
      const dpr = typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1
      canvas.width = Math.floor(width * dpr)
      canvas.height = Math.floor(height * dpr)
      ctx.save()
      ctx.scale(dpr, dpr)
      renderAllStrokes(ctx, width, height, visible, CANVAS_BG_COLOR)
      ctx.restore()
    },
    [width],
  )

  React.useEffect(() => {
    if (strokes === null) return
    if (!replaying) {
      draw(strokes)
      return
    }
    const points = totalPoints(strokes)
    const duration = replayDurationMs(points)
    if (duration === 0) {
      setReplaying(false)
      return
    }
    let frame = 0
    let start: number | null = null
    const tick = (now: number) => {
      start ??= now
      const progress = Math.min(1, (now - start) / duration)
      draw(strokePrefix(strokes, Math.ceil(points * progress)))
      if (progress < 1) frame = requestAnimationFrame(tick)
      else setReplaying(false)
    }
    draw([])
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [strokes, replaying, draw])

  if (strokes === null) {
    return (
      <div
        role="img"
        aria-label={`${label} (available in the results)`}
        className={cn(
          'flex aspect-[16/10] w-full flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-border bg-muted/50 p-3 text-center text-sm text-muted-foreground',
          className,
        )}
      >
        <ImageOff className="size-5" aria-hidden="true" />
        {!compact && <span>This drawing will be available in the results.</span>}
      </div>
    )
  }

  return (
    <div
      ref={containerRef}
      className={cn(
        'relative aspect-[16/10] w-full overflow-hidden rounded-xl bg-[#FFFDF8] ring-1 ring-border/70',
        className,
      )}
    >
      <canvas ref={canvasRef} role="img" aria-label={label} className="block size-full" />
      {replaying && (
        <button
          type="button"
          onClick={() => setReplaying(false)}
          className="absolute right-2 bottom-2 inline-flex min-h-9 items-center gap-1.5 rounded-full bg-card/90 px-3 text-xs font-bold text-foreground shadow-xs ring-1 ring-border outline-none focus-visible:ring-[3px] focus-visible:ring-ring/60"
        >
          <FastForward className="size-3.5" aria-hidden="true" />
          Skip replay
        </button>
      )}
    </div>
  )
})
