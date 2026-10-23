import * as React from 'react'
import { Check, Loader2, Sparkles } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { DrawCanvas } from '@/features/drawing/DrawCanvas'
import { DrawingToolbar } from '@/features/drawing/DrawingToolbar'
import { ServerCountdown } from '@/features/session/ServerCountdown'
import type { Stroke, StrokeRejection, StrokeTool } from '@/features/drawing/types'
import { cn } from '@/lib/utils'

import type { ImpostorRoleInfo } from '../common/types'
import type { SketchyCommands } from './useSketchyCommands'

export interface SketchCanvasProps {
  role: ImpostorRoleInfo | null
  strokes: Stroke[]
  canvasFull: boolean
  strokeRejection: StrokeRejection | null
  submitted: boolean
  submitError?: string | null
  deadlineAt: string | null
  commands: SketchyCommands
  onLocalStrokeStart: (stroke: Stroke) => void
  onLocalPoints: (strokeId: string, points: [number, number][], isFinal: boolean) => void
  onUndoLocalStroke: () => void
  onClearLocalStrokes: () => void
}

export function SketchCanvas({
  role,
  strokes,
  canvasFull,
  strokeRejection,
  submitted,
  submitError,
  deadlineAt,
  commands,
  onLocalStrokeStart,
  onLocalPoints,
  onUndoLocalStroke,
  onClearLocalStrokes,
}: SketchCanvasProps) {
  const [tool, setTool] = React.useState<StrokeTool>('PENCIL')
  const [color, setColor] = React.useState('#2563EB')
  const [size, setSize] = React.useState(3)
  const [sent, setSent] = React.useState(false)
  // A rejected submit frees the button for another try (the store keeps `submitted` false).
  const submitting = sent && !submitError

  const handleUndo = React.useCallback(() => {
    if (submitted) return
    onUndoLocalStroke()
    commands.undo()
  }, [submitted, onUndoLocalStroke, commands])

  const handleClear = React.useCallback(() => {
    if (submitted) return
    onClearLocalStrokes()
    commands.clear()
  }, [submitted, onClearLocalStrokes, commands])

  const handleSubmit = React.useCallback(() => {
    if (submitted || submitting) return
    setSent(true)
    if (!commands.submit()) setSent(false)
  }, [submitted, submitting, commands])

  return (
    <div className="flex flex-col gap-3 max-w-4xl mx-auto w-full animate-in fade-in duration-200">
      {/* Header bar: Word prompt + Timer + Submit button */}
      <div className="flex flex-wrap items-center justify-between gap-3 p-3 sm:p-4 rounded-2xl border border-border bg-card shadow-xs">
        <div className="flex flex-col">
          <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {role?.category?.label ? `Category: ${role.category.label}` : 'Category'}
          </span>
          <div className="flex items-center gap-2">
            {role?.role === 'IMPOSTOR' ? (
              <span className="font-fredoka text-xl sm:text-2xl font-bold text-destructive">
                You are the Impostor! Blend in!
              </span>
            ) : role?.word ? (
              <div className="flex items-center gap-2">
                <Sparkles className="size-5 text-primary" aria-hidden="true" />
                <span className="font-fredoka text-xl sm:text-2xl font-bold text-primary">
                  {role.word}
                </span>
              </div>
            ) : (
              <span className="font-fredoka text-xl font-bold">Draw your sketch!</span>
            )}
          </div>
        </div>

        <div className="flex items-center gap-3">
          <ServerCountdown deadlineAt={deadlineAt} size="md" />
          <Button
            type="button"
            variant="default"
            size="lg"
            disabled={submitted || submitting}
            onClick={handleSubmit}
            className="font-fredoka font-semibold px-5"
          >
            {submitting ? (
              <>
                <Loader2 className="size-4 animate-spin motion-reduce:animate-none mr-2" />
                Submitting...
              </>
            ) : (
              <>
                <Check className="size-4 mr-1.5" />
                Submit sketch
              </>
            )}
          </Button>
        </div>
      </div>

      {/* Toolbar */}
      <DrawingToolbar
        tool={tool}
        color={color}
        size={size}
        // Undo/clear must stay usable on a full canvas: they are the way out of it.
        canDraw={!submitted}
        hasStrokes={strokes.length > 0}
        onSelectTool={setTool}
        onSelectColor={setColor}
        onSelectSize={setSize}
        onUndo={handleUndo}
        onClear={handleClear}
      />

      {/* Canvas */}
      <div
        className={cn(
          'relative w-full rounded-2xl overflow-hidden border-2 border-border shadow-xs bg-white',
          canvasFull && 'border-amber-400',
        )}
      >
        <DrawCanvas
          isDrawer={!submitted}
          isDrawingPhase={!submitted}
          tool={tool}
          color={color}
          size={size}
          strokes={strokes}
          canvasFull={canvasFull}
          strokeRejection={strokeRejection}
          onSendStrokeBatch={commands.drawStroke}
          onLocalStrokeStart={onLocalStrokeStart}
          onLocalPoints={onLocalPoints}
          ariaLabel="Your sketch canvas"
        />
      </div>

      {submitError && (
        <p role="alert" className="text-sm font-medium text-destructive text-center">
          {submitError}
        </p>
      )}
    </div>
  )
}
