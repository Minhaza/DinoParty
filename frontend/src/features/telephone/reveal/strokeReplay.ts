import type { Stroke } from '@/features/drawing/types'

// Drawing playback for the reveal ([TP-6], design §9): the server stores strokes in drawing order
// without timestamps, so a replay redraws growing prefixes of the same stroke list with the shared
// renderer. It is a visual effect only and never decides what is revealed.

export function totalPoints(strokes: readonly Stroke[]): number {
  return strokes.reduce((sum, s) => sum + s.points.length, 0)
}

/** The first `points` points of the drawing, as whole strokes plus a cut last one. */
export function strokePrefix(strokes: readonly Stroke[], points: number): Stroke[] {
  const out: Stroke[] = []
  let left = points
  for (const stroke of strokes) {
    if (left <= 0) break
    if (stroke.points.length <= left) {
      out.push(stroke)
      left -= stroke.points.length
    } else {
      out.push({ ...stroke, points: stroke.points.slice(0, left) })
      left = 0
    }
  }
  return out
}

const MIN_REPLAY_MS = 600
const MAX_REPLAY_MS = 3000
const MS_PER_POINT = 4

/** Proportional to the amount drawn, between 0.6 s and 3 s (0 for an empty canvas). */
export function replayDurationMs(points: number): number {
  if (points <= 0) return 0
  return Math.min(MAX_REPLAY_MS, Math.max(MIN_REPLAY_MS, points * MS_PER_POINT))
}
