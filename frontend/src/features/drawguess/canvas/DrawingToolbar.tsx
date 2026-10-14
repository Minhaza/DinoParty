import { Eraser, Pencil, RotateCcw, Trash2 } from 'lucide-react'

import { Button } from '@/components/ui/button'
import type { StrokeTool } from '../types'
import { BRUSH_SIZES, DRAW_COLORS } from './coords'

export interface DrawingToolbarProps {
  tool: StrokeTool
  color: string
  size: number
  canDraw: boolean
  hasStrokes: boolean
  onSelectTool: (tool: StrokeTool) => void
  onSelectColor: (color: string) => void
  onSelectSize: (size: number) => void
  onUndo: () => void
  onClear: () => void
}

export function DrawingToolbar({
  tool,
  color,
  size,
  canDraw,
  hasStrokes,
  onSelectTool,
  onSelectColor,
  onSelectSize,
  onUndo,
  onClear,
}: DrawingToolbarProps) {
  return (
    <div
      role="toolbar"
      aria-label="Drawing controls"
      className="flex flex-wrap items-center justify-between gap-2.5 p-2 rounded-xl bg-card border border-border shadow-xs"
    >
      {/* Tool Selector: Pencil vs Eraser */}
      <div className="flex items-center gap-1 bg-muted/60 p-1 rounded-lg">
        <Button
          type="button"
          size="sm"
          variant={tool === 'PENCIL' ? 'default' : 'ghost'}
          disabled={!canDraw}
          onClick={() => onSelectTool('PENCIL')}
          className="h-8 px-2.5 gap-1.5 text-xs font-semibold"
          aria-label="Pencil tool"
          aria-pressed={tool === 'PENCIL'}
        >
          <Pencil className="size-3.5" />
          <span className="hidden sm:inline">Pencil</span>
        </Button>
        <Button
          type="button"
          size="sm"
          variant={tool === 'ERASER' ? 'default' : 'ghost'}
          disabled={!canDraw}
          onClick={() => onSelectTool('ERASER')}
          className="h-8 px-2.5 gap-1.5 text-xs font-semibold"
          aria-label="Eraser tool"
          aria-pressed={tool === 'ERASER'}
        >
          <Eraser className="size-3.5" />
          <span className="hidden sm:inline">Eraser</span>
        </Button>
      </div>

      {/* Brush Size Selector */}
      <div className="flex items-center gap-1.5 bg-muted/40 px-2 py-1 rounded-lg">
        <span className="text-xs font-medium text-muted-foreground hidden sm:inline mr-1">
          Size:
        </span>
        {BRUSH_SIZES.map((s) => (
          <button
            key={s.size}
            type="button"
            disabled={!canDraw}
            onClick={() => onSelectSize(s.size)}
            aria-label={`${s.label} brush size`}
            aria-pressed={size === s.size}
            className={`flex items-center justify-center size-7 rounded-md transition-all ${
              size === s.size
                ? 'bg-primary text-primary-foreground shadow-xs'
                : 'hover:bg-muted text-foreground'
            } disabled:opacity-40 disabled:pointer-events-none`}
          >
            <div
              className="rounded-full bg-current"
              style={{
                width: `${s.size * 3 + 2}px`,
                height: `${s.size * 3 + 2}px`,
              }}
            />
          </button>
        ))}
      </div>

      {/* Color Palette (Active when tool === 'PENCIL') */}
      <div className="flex items-center gap-1 flex-wrap max-w-full">
        {DRAW_COLORS.map((c) => {
          const isSelected = tool === 'PENCIL' && color.toUpperCase() === c.hex.toUpperCase()
          return (
            <button
              key={c.id}
              type="button"
              disabled={!canDraw || tool === 'ERASER'}
              onClick={() => {
                onSelectColor(c.hex)
                if (tool !== 'PENCIL') onSelectTool('PENCIL')
              }}
              title={c.label}
              aria-label={`Select ${c.label} color`}
              aria-pressed={isSelected}
              className={`size-6 sm:size-6.5 rounded-full border border-black/20 shadow-xs transition-transform hover:scale-110 active:scale-95 disabled:opacity-40 disabled:pointer-events-none relative ${
                isSelected
                  ? 'ring-2 ring-primary ring-offset-2 ring-offset-background scale-110'
                  : ''
              }`}
              style={{ backgroundColor: c.hex }}
            />
          )
        })}
      </div>

      {/* Actions: Undo and Clear */}
      <div className="flex items-center gap-1 ml-auto">
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={!canDraw || !hasStrokes}
          onClick={onUndo}
          className="h-8 px-2.5 gap-1.5 text-xs font-semibold"
          aria-label="Undo last stroke"
        >
          <RotateCcw className="size-3.5" />
          <span className="hidden sm:inline">Undo</span>
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={!canDraw || !hasStrokes}
          onClick={onClear}
          className="h-8 px-2.5 gap-1.5 text-xs font-semibold text-destructive hover:text-destructive hover:bg-destructive/10"
          aria-label="Clear all drawing"
        >
          <Trash2 className="size-3.5" />
          <span className="hidden sm:inline">Clear</span>
        </Button>
      </div>
    </div>
  )
}
