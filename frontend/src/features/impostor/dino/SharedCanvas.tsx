import * as React from 'react'

import { DrawCanvas } from '@/features/drawing/DrawCanvas'
import type { DrawStrokePayload, Stroke, StrokeRejection } from '@/features/drawing/types'

import type { ImpostorDrawStrokePayload, ImpostorStroke } from '../common/types'

/** Every Dino Impostor stroke uses the same width, so no player's line stands out (G5). */
export const IMPOSTOR_STROKE_SIZE = 2

export interface SharedCanvasProps {
  strokes: ImpostorStroke[]
  colors: Record<string, string>
  currentGuestId: string | null | undefined
  /** The current phase is a `DRAWING` turn. */
  drawingPhase: boolean
  isMyTurn: boolean
  /** `phaseId` of the turn: a new turn re-arms the one-stroke lock. */
  turnPhaseId: string | null
  /** The server refused another stroke of ours this turn. */
  locked: boolean
  canvasFull: boolean
  strokeRejection: StrokeRejection | null
  drawerName: string | null
  /** Sends one `IMPOSTOR_DRAW_STROKE` batch; `false` when it could not be sent. */
  onSend: (payload: ImpostorDrawStrokePayload) => boolean
  onLocalStrokeStart: (stroke: ImpostorStroke) => void
  onLocalPoints: (strokeId: string, points: [number, number][], isFinal: boolean) => void
}

/**
 * The one shared canvas ([DI-6]): only the drawer of the turn draws, one `PENCIL` stroke in their
 * own colour, with no toolbar, undo or clear. Every stroke is painted in its author's session colour.
 */
export function SharedCanvas({
  strokes,
  colors,
  currentGuestId,
  drawingPhase,
  isMyTurn,
  turnPhaseId,
  locked,
  canvasFull,
  strokeRejection,
  drawerName,
  onSend,
  onLocalStrokeStart,
  onLocalPoints,
}: SharedCanvasProps) {
  const myColor = currentGuestId ? (colors[currentGuestId] ?? null) : null
  // Turn in which this client started its stroke (UI only; the points live in the store).
  const [placedInTurn, setPlacedInTurn] = React.useState<string | null>(null)
  const placed = locked || (turnPhaseId !== null && placedInTurn === turnPhaseId)
  const canDraw = drawingPhase && isMyTurn && !locked && myColor !== null

  const painted = React.useMemo<Stroke[]>(
    () => strokes.map((s) => ({ ...s, color: colors[s.authorGuestId] ?? s.color })),
    [strokes, colors],
  )

  const handleSend = React.useCallback(
    (batch: DrawStrokePayload) => onSend({ ...batch, tool: 'PENCIL' }),
    [onSend],
  )
  const handleStart = React.useCallback(
    (stroke: Stroke) => {
      if (!currentGuestId) return
      setPlacedInTurn(turnPhaseId)
      onLocalStrokeStart({
        ...stroke,
        closed: stroke.closed ?? false,
        authorGuestId: currentGuestId,
      })
    },
    [currentGuestId, turnPhaseId, onLocalStrokeStart],
  )

  const status = !drawingPhase
    ? null
    : isMyTurn
      ? myColor === null
        ? 'Getting your colour…'
        : placed
          ? 'Stroke placed — nice! Waiting for the next dino.'
          : 'Your turn: draw one stroke. Lift your finger or pen to finish it.'
      : drawerName
        ? `Watching ${drawerName} draw…`
        : null

  return (
    <div className="flex h-full min-h-0 w-full flex-col gap-2">
      <div className="flex-1 min-h-[220px]">
        <DrawCanvas
          isDrawer={canDraw}
          isDrawingPhase={drawingPhase}
          tool="PENCIL"
          color={myColor ?? '#000000'}
          size={IMPOSTOR_STROKE_SIZE}
          strokes={painted}
          canvasFull={canvasFull}
          strokeRejection={strokeRejection}
          onSendStrokeBatch={handleSend}
          onLocalStrokeStart={handleStart}
          onLocalPoints={onLocalPoints}
          singleStroke
          strokeLockKey={turnPhaseId}
          ariaLabel={
            canDraw && !placed
              ? 'Shared canvas — your turn, draw one stroke'
              : 'Shared canvas with every stroke so far'
          }
        />
      </div>
      {status && (
        <p className="px-1 text-center text-sm font-medium text-muted-foreground">{status}</p>
      )}
    </div>
  )
}
