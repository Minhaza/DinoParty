import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { DrawingToolbar } from './DrawingToolbar'

describe('DrawingToolbar', () => {
  it('allows tool, color, and size selection when drawing is enabled', () => {
    const onSelectTool = vi.fn()
    const onSelectColor = vi.fn()
    const onSelectSize = vi.fn()
    const onUndo = vi.fn()
    const onClear = vi.fn()

    render(
      <DrawingToolbar
        tool="PENCIL"
        color="#2B2B2B"
        size={2}
        canDraw={true}
        hasStrokes={true}
        onSelectTool={onSelectTool}
        onSelectColor={onSelectColor}
        onSelectSize={onSelectSize}
        onUndo={onUndo}
        onClear={onClear}
      />,
    )

    // Select Eraser
    const eraserBtn = screen.getByRole('button', { name: /eraser tool/i })
    fireEvent.click(eraserBtn)
    expect(onSelectTool).toHaveBeenCalledWith('ERASER')

    // Select Size 3
    const thickBtn = screen.getByRole('button', { name: /thick brush size/i })
    fireEvent.click(thickBtn)
    expect(onSelectSize).toHaveBeenCalledWith(3)

    // Select Red color
    const redBtn = screen.getByRole('button', { name: /select red color/i })
    fireEvent.click(redBtn)
    expect(onSelectColor).toHaveBeenCalledWith('#EF4444')

    // Undo and Clear
    const undoBtn = screen.getByRole('button', { name: /undo last stroke/i })
    fireEvent.click(undoBtn)
    expect(onUndo).toHaveBeenCalledTimes(1)

    const clearBtn = screen.getByRole('button', { name: /clear all drawing/i })
    fireEvent.click(clearBtn)
    expect(onClear).toHaveBeenCalledTimes(1)
  })

  it('disables controls when canDraw is false', () => {
    render(
      <DrawingToolbar
        tool="PENCIL"
        color="#2B2B2B"
        size={2}
        canDraw={false}
        hasStrokes={true}
        onSelectTool={vi.fn()}
        onSelectColor={vi.fn()}
        onSelectSize={vi.fn()}
        onUndo={vi.fn()}
        onClear={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: /pencil tool/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /eraser tool/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /undo last stroke/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /clear all drawing/i })).toBeDisabled()
  })

  it('disables undo and clear when hasStrokes is false', () => {
    render(
      <DrawingToolbar
        tool="PENCIL"
        color="#2B2B2B"
        size={2}
        canDraw={true}
        hasStrokes={false}
        onSelectTool={vi.fn()}
        onSelectColor={vi.fn()}
        onSelectSize={vi.fn()}
        onUndo={vi.fn()}
        onClear={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: /undo last stroke/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /clear all drawing/i })).toBeDisabled()
  })
})
