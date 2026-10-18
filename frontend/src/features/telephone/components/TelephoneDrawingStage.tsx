import * as React from 'react'
import { Send } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { DrawCanvas } from '@/features/drawing/DrawCanvas'
import { DrawingToolbar } from '@/features/drawing/DrawingToolbar'
import type {
  DrawStrokePayload,
  Stroke,
  StrokeRejection,
  StrokeTool,
} from '@/features/drawing/types'

import type { SubmissionStatus } from '../telephoneStore'
import type { TelephoneSource } from '../types'
import { TelephoneSubmissionStatus } from './TelephoneSubmissionStatus'

export interface TelephoneDrawingStageProps {
  source: TelephoneSource | null | undefined
  strokes: Stroke[]
  canvasFull: boolean
  strokeRejection: StrokeRejection | null
  submission: SubmissionStatus
  submitError: string | null
  offline: boolean
  syncing: boolean
  onSendStrokeBatch: (payload: DrawStrokePayload) => boolean
  onLocalStrokeStart: (stroke: Stroke) => void
  onLocalPoints: (strokeId: string, points: [number, number][], isFinal: boolean) => void
  onUndo: () => void
  onClear: () => void
  onSubmit: () => void
}

/** DRAW step: the sentence to draw, the shared canvas engine and a one-shot commit of the draft. */
export function TelephoneDrawingStage({
  source,
  strokes,
  canvasFull,
  strokeRejection,
  submission,
  submitError,
  offline,
  syncing,
  onSendStrokeBatch,
  onLocalStrokeStart,
  onLocalPoints,
  onUndo,
  onClear,
  onSubmit,
}: TelephoneDrawingStageProps) {
  const [tool, setTool] = React.useState<StrokeTool>('PENCIL')
  const [color, setColor] = React.useState<string>('#2B2B2B')
  const [size, setSize] = React.useState<number>(2)

  const busy = submission === 'submitting' || submission === 'submitted'
  const canDraw = !busy && !offline && !syncing && source !== undefined
  const isEmpty = strokes.length === 0

  return (
    <div className="flex min-w-0 flex-col gap-3">
      <div className="flex min-w-0 flex-col gap-2">
        <div
          className="mx-auto aspect-[16/10] w-full"
          // Fit the whole canvas on screen: width follows the height left under the header and toolbar.
          style={{ maxWidth: 'max(24rem, calc((100dvh - 25rem) * 1.6))' }}
        >
          <DrawCanvas
            isDrawer={true}
            isDrawingPhase={canDraw}
            tool={tool}
            color={color}
            size={size}
            strokes={strokes}
            canvasFull={canvasFull}
            strokeRejection={strokeRejection}
            onSendStrokeBatch={onSendStrokeBatch}
            onLocalStrokeStart={onLocalStrokeStart}
            onLocalPoints={onLocalPoints}
            ariaLabel={canDraw ? 'Your drawing (interactive canvas)' : 'Your drawing'}
          />
        </div>

        <DrawingToolbar
          tool={tool}
          color={color}
          size={size}
          canDraw={canDraw}
          hasStrokes={!isEmpty}
          onSelectTool={setTool}
          onSelectColor={setColor}
          onSelectSize={setSize}
          onUndo={onUndo}
          onClear={onClear}
        />

        <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
          <div className="min-h-5">
            <TelephoneSubmissionStatus
              submission={submission}
              submitError={submitError}
              offline={offline}
              syncing={syncing}
            />
            {isEmpty && !busy && !offline && !syncing && (
              <p className="text-sm text-muted-foreground">
                A blank page is allowed, but the next dino will thank you for a doodle.
              </p>
            )}
          </div>
          <Button
            type="button"
            size="lg"
            className="w-full sm:w-auto"
            disabled={busy || offline || syncing}
            onClick={onSubmit}
          >
            <Send className="size-4" aria-hidden="true" />
            {isEmpty ? 'Submit a blank page' : 'Send drawing'}
          </Button>
        </div>
      </div>
    </div>
  )
}
