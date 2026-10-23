import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import {
  ARTIST_ROLE,
  AWARE_IMPOSTOR_ROLE,
  sampleDrawings,
  sampleStroke,
  SS_PLAYERS,
  UNAWARE_IMPOSTOR_ROLE,
  VIEWER_ARTIST,
  VIEWER_IMPOSTOR,
} from './fixtures'
import { GalleryGrid } from './GalleryGrid'
import { SketchCanvas } from './SketchCanvas'
import { SketchWaiting } from './SketchWaiting'
import type { SketchyCommands } from './useSketchyCommands'

function mockCommands(over: Partial<SketchyCommands> = {}): SketchyCommands {
  return {
    drawStroke: vi.fn().mockReturnValue(true),
    undo: vi.fn().mockReturnValue(true),
    clear: vi.fn().mockReturnValue(true),
    submit: vi.fn().mockReturnValue(true),
    chat: vi.fn().mockReturnValue(true),
    vote: vi.fn().mockReturnValue(true),
    guess: vi.fn().mockReturnValue(true),
    ...over,
  }
}

describe('SketchCanvas', () => {
  it('frees the submit button again when the server rejects the submit', () => {
    const props = {
      role: ARTIST_ROLE,
      strokes: [],
      canvasFull: false,
      strokeRejection: null,
      submitted: false,
      deadlineAt: '2099-01-01T00:00:25.000Z',
      commands: mockCommands(),
      onLocalStrokeStart: vi.fn(),
      onLocalPoints: vi.fn(),
      onUndoLocalStroke: vi.fn(),
      onClearLocalStrokes: vi.fn(),
    }
    const { rerender } = render(<SketchCanvas {...props} />)
    fireEvent.click(screen.getByRole('button', { name: /Submit sketch/i }))
    expect(screen.getByRole('button', { name: /Submitting/i })).toBeDisabled()
    rerender(<SketchCanvas {...props} submitError="Try again" />)
    expect(screen.getByRole('button', { name: /Submit sketch/i })).toBeEnabled()
  })

  it('renders artist prompt with secret word and category', () => {
    const commands = mockCommands()
    render(
      <SketchCanvas
        role={ARTIST_ROLE}
        strokes={[]}
        canvasFull={false}
        strokeRejection={null}
        submitted={false}
        deadlineAt="2099-01-01T00:00:25.000Z"
        commands={commands}
        onLocalStrokeStart={vi.fn()}
        onLocalPoints={vi.fn()}
        onUndoLocalStroke={vi.fn()}
        onClearLocalStrokes={vi.fn()}
      />,
    )

    expect(screen.getByText('Amber')).toBeInTheDocument()
    expect(screen.getByText('Category: Fossils')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Submit sketch/i })).toBeEnabled()
  })

  it('SS3_unaware impostor sees decoy word prompt just like an artist', () => {
    const commands = mockCommands()
    render(
      <SketchCanvas
        role={UNAWARE_IMPOSTOR_ROLE}
        strokes={[]}
        canvasFull={false}
        strokeRejection={null}
        submitted={false}
        deadlineAt="2099-01-01T00:00:25.000Z"
        commands={commands}
        onLocalStrokeStart={vi.fn()}
        onLocalPoints={vi.fn()}
        onUndoLocalStroke={vi.fn()}
        onClearLocalStrokes={vi.fn()}
      />,
    )

    expect(screen.getByText('Trilobite')).toBeInTheDocument()
    expect(screen.getByText('Category: Fossils')).toBeInTheDocument()
  })

  it('renders aware impostor blend-in alert', () => {
    const commands = mockCommands()
    render(
      <SketchCanvas
        role={AWARE_IMPOSTOR_ROLE}
        strokes={[]}
        canvasFull={false}
        strokeRejection={null}
        submitted={false}
        deadlineAt="2099-01-01T00:00:25.000Z"
        commands={commands}
        onLocalStrokeStart={vi.fn()}
        onLocalPoints={vi.fn()}
        onUndoLocalStroke={vi.fn()}
        onClearLocalStrokes={vi.fn()}
      />,
    )

    expect(screen.getByText(/You are the Impostor! Blend in!/i)).toBeInTheDocument()
  })

  it('submits sketch on button click', () => {
    const commands = mockCommands()
    render(
      <SketchCanvas
        role={ARTIST_ROLE}
        strokes={[sampleStroke(VIEWER_ARTIST, 's1')]}
        canvasFull={false}
        strokeRejection={null}
        submitted={false}
        deadlineAt="2099-01-01T00:00:25.000Z"
        commands={commands}
        onLocalStrokeStart={vi.fn()}
        onLocalPoints={vi.fn()}
        onUndoLocalStroke={vi.fn()}
        onClearLocalStrokes={vi.fn()}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: /Submit sketch/i }))
    expect(commands.submit).toHaveBeenCalledTimes(1)
  })

  it('shows canvasFull budget warning banner', () => {
    const commands = mockCommands()
    render(
      <SketchCanvas
        role={ARTIST_ROLE}
        strokes={[sampleStroke(VIEWER_ARTIST, 's1')]}
        canvasFull={true}
        strokeRejection={null}
        submitted={false}
        deadlineAt="2099-01-01T00:00:25.000Z"
        commands={commands}
        onLocalStrokeStart={vi.fn()}
        onLocalPoints={vi.fn()}
        onUndoLocalStroke={vi.fn()}
        onClearLocalStrokes={vi.fn()}
      />,
    )

    expect(screen.getByText(/Drawing budget full/i)).toBeInTheDocument()
  })
})

describe('SketchWaiting', () => {
  it('renders submitted state with progress bar and player statuses', () => {
    render(
      <SketchWaiting
        strokes={[sampleStroke(VIEWER_ARTIST, 's1')]}
        submittedGuestIds={['ss-g1', 'ss-g3']}
        players={SS_PLAYERS}
        deadlineAt="2099-01-01T00:00:25.000Z"
      />,
    )

    expect(screen.getByText('Sketch submitted!')).toBeInTheDocument()
    expect(screen.getByText(/Progress \(2 of 5 ready\)/i)).toBeInTheDocument()
    expect(screen.getByText('40%')).toBeInTheDocument()
    expect(screen.getAllByText('Ready')).toHaveLength(2)
    expect(screen.getAllByText('Drawing...')).toHaveLength(3)
  })
})

describe('GalleryGrid', () => {
  it('renders drawings with author info and empty placeholders', () => {
    const onVote = vi.fn()
    render(
      <GalleryGrid
        drawings={sampleDrawings}
        players={SS_PLAYERS}
        currentGuestId={VIEWER_ARTIST}
        myVote={null}
        pendingVote={null}
        votedGuestIds={['ss-g1']}
        enabled={true}
        onVote={onVote}
        deadlineAt="2099-01-01T00:00:25.000Z"
      />,
    )

    expect(screen.getByText('The Gallery · Spot the Impostor!')).toBeInTheDocument()
    expect(screen.getByText('No drawing submitted')).toBeInTheDocument()

    // Own card shows (You) and cannot vote for self
    expect(screen.getByText('(You)')).toBeInTheDocument()

    // Radio buttons for others
    const radios = screen.getAllByRole('radio')
    expect(radios).toHaveLength(4) // 5 players minus viewer

    // Voting for player 2
    fireEvent.click(radios[0])
    expect(onVote).toHaveBeenCalledWith(sampleDrawings[1].guestId)
  })

  it('allows withdrawing vote', () => {
    const onVote = vi.fn()
    render(
      <GalleryGrid
        drawings={sampleDrawings}
        players={SS_PLAYERS}
        currentGuestId={VIEWER_ARTIST}
        myVote={VIEWER_IMPOSTOR}
        pendingVote={null}
        votedGuestIds={['ss-g1']}
        enabled={true}
        onVote={onVote}
        deadlineAt="2099-01-01T00:00:25.000Z"
      />,
    )

    expect(screen.getByText(/You voted for Trixie/i)).toBeInTheDocument()
    const withdrawBtn = screen.getByRole('button', { name: /Withdraw my vote/i })
    expect(withdrawBtn).toBeEnabled()
    fireEvent.click(withdrawBtn)
    expect(onVote).toHaveBeenCalledWith(null)
  })

  it('phone layout: one column, shrinkable cards, no fixed pixel widths', () => {
    const { container } = render(
      <GalleryGrid
        drawings={sampleDrawings}
        players={SS_PLAYERS}
        currentGuestId={VIEWER_ARTIST}
        myVote={null}
        pendingVote={null}
        votedGuestIds={[]}
        enabled
        onVote={vi.fn()}
        deadlineAt={null}
      />,
    )
    const grid = container.querySelector('.grid')
    expect(grid?.className).toContain('grid-cols-1')
    for (const card of container.querySelectorAll('label')) {
      expect(card.className).toContain('min-w-0')
    }
    expect(container.innerHTML).not.toMatch(/w-\[\d+px\]/)
  })

  it('keeps your own drawing out of the vote and votes from the keyboard', async () => {
    const onVote = vi.fn()
    render(
      <GalleryGrid
        drawings={sampleDrawings}
        players={SS_PLAYERS}
        currentGuestId={VIEWER_ARTIST}
        myVote={null}
        pendingVote={null}
        votedGuestIds={[]}
        enabled
        onVote={onVote}
        deadlineAt={null}
      />,
    )
    const radios = screen.getAllByRole('radio')
    expect(radios).toHaveLength(sampleDrawings.length - 1)
    radios[0].focus()
    await userEvent.keyboard('{ArrowDown}')
    expect(onVote).toHaveBeenCalledWith(radios[1].getAttribute('value'))
  })
})
