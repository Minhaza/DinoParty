// Drawing wire types shared by every game that draws (api-contracts §2.1 `Stroke`, §3.9 batch protocol).

export type StrokeTool = 'PENCIL' | 'ERASER'

export interface Stroke {
  strokeId: string
  tool: StrokeTool
  color: string
  size: number
  points: [number, number][]
  closed?: boolean
}

export interface CanvasBudget {
  points: number
  maxPoints: number
  strokes: number
  maxStrokes: number
}

/** One stroke batch as sent by `DRAW_STROKE` / `TELEPHONE_DRAW_STROKE` (identical payloads). */
export interface DrawStrokePayload {
  strokeId: string
  batchNo: number
  tool: StrokeTool
  color: string
  size: number
  points: [number, number][]
  final: boolean
}

/**
 * How the drawer's canvas must react to a rejected stroke batch (api-contracts §3.9):
 * `RESTART` continues with a new `strokeId`, `FULL` stops drawing until undo/clear frees budget.
 */
export interface StrokeRejection {
  strokeId: string
  kind: 'RESTART' | 'FULL'
  nonce: number
}

/** Largest stroke batch (api-contracts §3.9); a snapshot this close to the budget stays "full". */
export const MAX_POINTS_PER_BATCH = 200

/** Whether a server-reported budget leaves no room for another full batch or stroke. */
export function isBudgetExhausted(budget: CanvasBudget): boolean {
  return (
    budget.points > budget.maxPoints - MAX_POINTS_PER_BATCH || budget.strokes >= budget.maxStrokes
  )
}
