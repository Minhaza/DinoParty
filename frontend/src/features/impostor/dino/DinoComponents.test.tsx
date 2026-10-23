import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { ImpostorDrawStrokePayload, ImpostorStroke } from '../common/types'
import { colorsFor, DI_PLAYERS, fixtureStroke, seatOrderFor } from './fixtures'
import { IMPOSTOR_STROKE_SIZE, SharedCanvas, type SharedCanvasProps } from './SharedCanvas'
import { StrokeLegend } from './StrokeLegend'
import { TurnStrip, type TurnStripProps } from './TurnStrip'

const playersById = new Map(DI_PLAYERS.map((p) => [p.guestId, p]))
const colors = colorsFor(DI_PLAYERS)
const seatOrder = seatOrderFor(DI_PLAYERS)

describe('TurnStrip', () => {
  const props = (over: Partial<TurnStripProps> = {}): TurnStripProps => ({
    seatOrder,
    playersById,
    colors,
    drawerGuestId: seatOrder[2],
    currentGuestId: 'di-g1',
    lap: 0,
    laps: 2,
    turn: 2,
    totalTurns: 20,
    deadlineAt: new Date(Date.now() + 18_000).toISOString(),
    ...over,
  })

  it('DI5_shows every seat with colour and initial, and marks the drawer', () => {
    render(<TurnStrip {...props()} />)
    const seats = within(screen.getByRole('list', { name: /seat order/i })).getAllByRole('listitem')
    expect(seats).toHaveLength(10)
    const current = seats.find((s) => s.getAttribute('aria-current') === 'step')
    expect(current).toHaveTextContent(playersById.get(seatOrder[2])?.nickname ?? '')
    expect(current).toHaveTextContent(/drawing/i)
    expect(seats.filter((s) => s.getAttribute('aria-current') === 'step')).toHaveLength(1)
    // Initial badges: Stego and Spinny clash on S, so they get two letters.
    expect(seats[1]).toHaveTextContent('ST')
    expect(screen.getByText(/is drawing/i)).toBeInTheDocument()
  })

  it('DI7_shows the lap and turn counter and the turn countdown', () => {
    render(<TurnStrip {...props()} />)
    expect(screen.getByText('Lap 1/2 · Turn 3/20')).toBeInTheDocument()
    expect(screen.getByLabelText(/time left this turn: 0:1[78]/i)).toBeInTheDocument()
  })

  it('DI6_announces "Your turn" once per turn', () => {
    const live = () => screen.getByTestId('your-turn-announcement')
    const { rerender } = render(<TurnStrip {...props()} />)
    expect(live()).toHaveTextContent('')

    rerender(<TurnStrip {...props({ drawerGuestId: 'di-g1', turn: 9 })} />)
    expect(live()).toHaveAttribute('aria-live', 'assertive')
    expect(live()).toHaveTextContent(/^Your turn — add one stroke\. Turn 10\.$/)
    // A re-render inside the same turn does not change (and so does not repeat) the message.
    rerender(<TurnStrip {...props({ drawerGuestId: 'di-g1', turn: 9 })} />)
    expect(live()).toHaveTextContent(/^Your turn — add one stroke\. Turn 10\.$/)

    rerender(<TurnStrip {...props({ drawerGuestId: seatOrder[0], turn: 10, lap: 1 })} />)
    expect(live()).toHaveTextContent('')
    rerender(<TurnStrip {...props({ drawerGuestId: 'di-g1', turn: 19, lap: 1 })} />)
    expect(live()).toHaveTextContent('Turn 20.')
  })
})

describe('StrokeLegend', () => {
  it('pairs every colour with an initial and a name', () => {
    render(
      <StrokeLegend
        seatOrder={[...seatOrder, 'gone']}
        playersById={playersById}
        colors={colors}
        currentGuestId="di-g1"
      />,
    )
    const items = within(screen.getByRole('region', { name: /stroke colours/i })).getAllByRole(
      'listitem',
    )
    expect(items).toHaveLength(11)
    expect(items[0]).toHaveTextContent(/^TTrixie$/)
    expect(items[9]).toHaveTextContent(/^REYou$/)
    expect(items[10]).toHaveTextContent(/\?A dino who left/)
  })
})

describe('SharedCanvas', () => {
  let strokeStyles: string[]

  beforeEach(() => {
    vi.useFakeTimers()
    strokeStyles = []
    Element.prototype.getBoundingClientRect = vi.fn().mockReturnValue({
      left: 0,
      top: 0,
      width: 800,
      height: 500,
      right: 800,
      bottom: 500,
      x: 0,
      y: 0,
      toJSON: () => {},
    })
    const ctx = {
      save: vi.fn(),
      restore: vi.fn(),
      beginPath: vi.fn(),
      arc: vi.fn(),
      moveTo: vi.fn(),
      lineTo: vi.fn(),
      quadraticCurveTo: vi.fn(),
      fill: vi.fn(),
      stroke: vi.fn(),
      fillRect: vi.fn(),
      scale: vi.fn(),
      lineCap: 'butt',
      lineJoin: 'miter',
      fillStyle: '#000',
      lineWidth: 1,
    }
    Object.defineProperty(ctx, 'strokeStyle', {
      set: (v: string) => strokeStyles.push(v),
      get: () => strokeStyles[strokeStyles.length - 1] ?? '#000',
    })
    HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue(ctx)
    Element.prototype.setPointerCapture = vi.fn()
    Element.prototype.releasePointerCapture = vi.fn()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  const renderCanvas = (over: Partial<SharedCanvasProps> = {}) => {
    const onSend = vi.fn((p: ImpostorDrawStrokePayload) => Boolean(p))
    const onLocalStrokeStart = vi.fn()
    const utils = render(
      <SharedCanvas
        strokes={[]}
        colors={colors}
        currentGuestId="di-g1"
        drawingPhase
        isMyTurn
        turnPhaseId="turn-9"
        locked={false}
        canvasFull={false}
        strokeRejection={null}
        drawerName="You"
        onSend={onSend}
        onLocalStrokeStart={onLocalStrokeStart}
        onLocalPoints={vi.fn()}
        {...over}
      />,
    )
    return { onSend, onLocalStrokeStart, ...utils }
  }

  const draw = (pointerId: number, x = 80) => {
    const canvas = screen.getByRole('img')
    fireEvent.pointerDown(canvas, { clientX: x, clientY: 50, pointerId, button: 0 })
    fireEvent.pointerMove(canvas, { clientX: x + 80, clientY: 100, pointerId })
    vi.advanceTimersByTime(50)
    fireEvent.pointerUp(canvas, { pointerId })
  }

  it('DI6_draws only on your turn', () => {
    const { onSend } = renderCanvas({ isMyTurn: false, drawerName: 'Trixie' })
    draw(1)
    expect(onSend).not.toHaveBeenCalled()
    expect(screen.getByText(/watching trixie draw/i)).toBeInTheDocument()
  })

  it('DI6_sends one PENCIL stroke in your own colour and then locks', () => {
    const { onSend, onLocalStrokeStart } = renderCanvas()
    draw(1)
    draw(2, 300)
    const batches = onSend.mock.calls.map((c) => c[0])
    expect(batches.length).toBeGreaterThan(0)
    expect(new Set(batches.map((b) => b.strokeId)).size).toBe(1)
    for (const b of batches) {
      expect(b.tool).toBe('PENCIL')
      expect(b.color).toBe(colors['di-g1'])
      expect(b.size).toBe(IMPOSTOR_STROKE_SIZE)
    }
    expect(onLocalStrokeStart.mock.calls[0][0]).toMatchObject({ authorGuestId: 'di-g1' })
    expect(screen.getByText(/stroke placed/i)).toBeInTheDocument()
  })

  it('DI6_stays locked when the server refused a stroke this turn', () => {
    const { onSend } = renderCanvas({ locked: true })
    draw(1)
    expect(onSend).not.toHaveBeenCalled()
  })

  it('cannot draw before its colour is known', () => {
    const { onSend } = renderCanvas({ colors: {} })
    draw(1)
    expect(onSend).not.toHaveBeenCalled()
    expect(screen.getByText(/getting your colour/i)).toBeInTheDocument()
  })

  it('D56_paints every stroke in its author colour', () => {
    const strokes: ImpostorStroke[] = [
      fixtureStroke('di-g2', 0, colors),
      // A stroke whose batch colour disagrees is still painted in its author's colour.
      { ...fixtureStroke('di-g3', 1, colors), color: '#123456' },
    ]
    renderCanvas({ strokes, isMyTurn: false })
    expect(strokeStyles).toContain(colors['di-g2'])
    expect(strokeStyles).toContain(colors['di-g3'])
    expect(strokeStyles).not.toContain('#123456')
  })

  it('offers no toolbar, undo or clear', () => {
    renderCanvas()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
