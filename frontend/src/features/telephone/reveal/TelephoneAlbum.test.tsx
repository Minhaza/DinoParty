import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { PlayerDto } from '@/api/types'
import type { Stroke } from '@/features/drawing/types'

import { sampleTelephoneStroke } from '../fixtures'
import type { AlbumEntryView } from '../types'
import { TelephoneAlbumStep } from './TelephoneAlbumStep'
import { TelephoneAlbumTimeline } from './TelephoneAlbumTimeline'
import { TelephoneAlbumViewer } from './TelephoneAlbumViewer'
import { TelephoneDrawingView } from './TelephoneDrawingView'

const renderAllStrokes = vi.hoisted(() => vi.fn())
vi.mock('@/features/drawing/render', async (importActual) => {
  const actual = await importActual<typeof import('@/features/drawing/render')>()
  return { ...actual, renderAllStrokes }
})

const players: PlayerDto[] = ['guest-1', 'guest-2', 'guest-3'].map((guestId, i) => ({
  guestId,
  nickname: ['Rex', 'Trixie', 'Ptera'][i],
  avatarKey: ['dino-rex', 'dino-tricera', 'dino-ptera'][i],
  avatarColor: null,
  ready: true,
  connection: 'CONNECTED',
  isHost: i === 0,
  score: null,
}))

const text = (
  step: number,
  value: string,
  extra: Partial<AlbumEntryView> = {},
): AlbumEntryView => ({
  step,
  kind: 'TEXT',
  authorGuestId: 'guest-1',
  authorNickname: 'Rex',
  text: value,
  strokes: null,
  omitted: false,
  fill: 'NONE',
  timedOut: false,
  placeholder: false,
  ...extra,
})

const drawing = (
  step: number,
  strokes: Stroke[] | null,
  extra: Partial<AlbumEntryView> = {},
): AlbumEntryView => ({
  step,
  kind: 'DRAWING',
  authorGuestId: 'guest-2',
  authorNickname: 'Trixie',
  text: null,
  strokes,
  omitted: strokes === null,
  fill: 'NONE',
  timedOut: false,
  placeholder: false,
  ...extra,
})

function mockReducedMotion(reduce: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: reduce && query.includes('reduce'),
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  )
}

const fakeContext = {
  save: vi.fn(),
  restore: vi.fn(),
  scale: vi.fn(),
  setTransform: vi.fn(),
  fillRect: vi.fn(),
} as unknown as CanvasRenderingContext2D

beforeEach(() => {
  renderAllStrokes.mockClear()
  HTMLCanvasElement.prototype.getContext = vi
    .fn()
    .mockReturnValue(fakeContext) as unknown as typeof HTMLCanvasElement.prototype.getContext
  Element.prototype.getBoundingClientRect = vi.fn().mockReturnValue({
    left: 0,
    top: 0,
    width: 640,
    height: 400,
    right: 640,
    bottom: 400,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  })
  vi.stubGlobal(
    'requestAnimationFrame',
    vi.fn(() => 1),
  )
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
  mockReducedMotion(false)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TelephoneAlbumStep', () => {
  it('shows a text entry with its step, kind and contributor', () => {
    render(<TelephoneAlbumStep entry={text(0, 'A raptor on roller skates')} players={players} />)

    const card = screen.getByRole('article', { name: /step 1/i })
    expect(within(card).getByText('A raptor on roller skates')).toBeInTheDocument()
    expect(within(card).getByText(/prompt/i)).toBeInTheDocument()
    expect(within(card).getByText('Rex')).toBeInTheDocument()
    expect(within(card).getByRole('img', { name: /dinosaur avatar/i })).toBeInTheDocument()
  })

  it('TP14 renders untrusted text literally, wraps long lines and keeps any script', () => {
    const hostile = '<img src=x onerror="alert(1)"> 🦖 恐竜が踊る مرحبا ' + 'ж'.repeat(120)
    const { container } = render(<TelephoneAlbumStep entry={text(2, hostile)} players={players} />)

    const paragraph = screen.getByText(hostile)
    expect(paragraph.tagName).toBe('P')
    expect(paragraph).toHaveAttribute('dir', 'auto')
    expect(paragraph.className).toMatch(/break-words/)
    expect(container.querySelector('img[src="x"]')).toBeNull()
    expect(screen.getByText(/description/i)).toBeInTheDocument()
  })

  it('renders a drawing through the shared renderer, eraser strokes included', () => {
    const eraser: Stroke = { ...sampleTelephoneStroke, strokeId: 'e-1', tool: 'ERASER' }
    render(
      <TelephoneAlbumStep entry={drawing(1, [sampleTelephoneStroke, eraser])} players={players} />,
    )

    expect(screen.getByRole('img', { name: /drawing by trixie/i })).toBeInTheDocument()
    expect(renderAllStrokes).toHaveBeenCalled()
    const strokes = renderAllStrokes.mock.calls.at(-1)?.[3] as Stroke[]
    expect(strokes.map((s) => s.tool)).toEqual(['PENCIL', 'ERASER'])
  })

  it('TP5 badges placeholders, timeouts, players who left and dino-picked prompts', () => {
    const { rerender } = render(
      <TelephoneAlbumStep
        entry={drawing(1, [], { placeholder: true, fill: 'TIMEOUT', timedOut: true })}
        players={players}
      />,
    )
    expect(screen.getByText(/blank page/i)).toBeInTheDocument()

    rerender(
      <TelephoneAlbumStep
        entry={text(2, 'half a thought', { fill: 'TIMEOUT', timedOut: true })}
        players={players}
      />,
    )
    expect(screen.getByText(/out of time/i)).toBeInTheDocument()

    rerender(
      <TelephoneAlbumStep
        entry={text(2, '(no description)', { placeholder: true, fill: 'LEFT_PLAYER' })}
        players={players}
      />,
    )
    expect(screen.getByText(/left the game/i)).toBeInTheDocument()
    expect(screen.getByText(/nothing written/i)).toBeInTheDocument()

    rerender(
      <TelephoneAlbumStep entry={text(0, 'volcano', { fill: 'AUTO_PROMPT' })} players={players} />,
    )
    expect(screen.getByText(/dino-picked prompt/i)).toBeInTheDocument()
  })

  it('TP6 explains a drawing withheld from a reconnect snapshot', () => {
    render(<TelephoneAlbumStep entry={drawing(3, null)} players={players} />)
    expect(screen.getByText(/available in the results/i)).toBeInTheDocument()
    expect(renderAllStrokes).not.toHaveBeenCalled()
  })

  it('keeps the attribution of a player who left, and never shows a guest id', () => {
    const { rerender, container } = render(
      <TelephoneAlbumStep
        entry={text(0, 'hi', { authorGuestId: 'guest-gone', authorNickname: 'Bronto' })}
        players={players}
      />,
    )
    expect(screen.getByText('Bronto')).toBeInTheDocument()
    expect(screen.getByText(/left/i)).toBeInTheDocument()

    rerender(
      <TelephoneAlbumStep
        entry={text(0, 'hi', { authorGuestId: 'guest-gone', authorNickname: null })}
        players={players}
      />,
    )
    expect(screen.getByText(/a departed dino/i)).toBeInTheDocument()
    expect(container.textContent).not.toContain('guest-gone')
  })
})

describe('TelephoneAlbumViewer', () => {
  it('lists the entries in step order and nothing else', () => {
    render(
      <TelephoneAlbumViewer
        ownerGuestId="guest-1"
        ownerNickname="Rex"
        entries={[text(0, 'first'), drawing(1, [sampleTelephoneStroke]), text(2, 'third')]}
        players={players}
        highlightStep={2}
      />,
    )

    expect(screen.getByRole('heading', { name: /rex.s album/i })).toBeInTheDocument()
    const items = screen.getAllByRole('article')
    expect(items.map((a) => a.getAttribute('aria-label'))).toEqual([
      expect.stringMatching(/step 1/i),
      expect.stringMatching(/step 2/i),
      expect.stringMatching(/step 3/i),
    ])
    expect(items[2]).toHaveAttribute('aria-current', 'step')
    expect(screen.queryByText(/step 4/i)).not.toBeInTheDocument()
  })
})

describe('TelephoneAlbumTimeline', () => {
  it('offers every listed contribution and reports the chosen step', () => {
    const onSelect = vi.fn()
    render(
      <TelephoneAlbumTimeline
        entries={[text(0, 'first'), drawing(1, [sampleTelephoneStroke]), text(2, 'third')]}
        players={players}
        selectedStep={0}
        onSelect={onSelect}
      />,
    )

    const buttons = screen.getAllByRole('button')
    expect(buttons).toHaveLength(3)
    expect(buttons[0]).toHaveAttribute('aria-current', 'step')
    expect(buttons[1]).toHaveAccessibleName(/step 2.*drawing.*trixie/i)
    fireEvent.click(buttons[2])
    expect(onSelect).toHaveBeenCalledWith(2)
  })
})

describe('TelephoneDrawingView', () => {
  it('replays a drawing that can be skipped', () => {
    render(<TelephoneDrawingView strokes={[sampleTelephoneStroke]} label="Drawing" replay />)
    const skip = screen.getByRole('button', { name: /skip/i })
    fireEvent.click(skip)
    expect(screen.queryByRole('button', { name: /skip/i })).not.toBeInTheDocument()
    expect(renderAllStrokes.mock.calls.at(-1)?.[3]).toEqual([sampleTelephoneStroke])
  })

  it('shows the finished drawing at once under reduced motion', () => {
    mockReducedMotion(true)
    render(<TelephoneDrawingView strokes={[sampleTelephoneStroke]} label="Drawing" replay />)
    expect(screen.queryByRole('button', { name: /skip/i })).not.toBeInTheDocument()
    expect(renderAllStrokes.mock.calls.at(-1)?.[3]).toEqual([sampleTelephoneStroke])
  })

  it('draws a withheld drawing once its strokes arrive (a resync on the cursor entry)', () => {
    const { rerender } = render(<TelephoneDrawingView strokes={null} label="Drawing" />)
    expect(renderAllStrokes).not.toHaveBeenCalled()
    rerender(<TelephoneDrawingView strokes={[sampleTelephoneStroke]} label="Drawing" />)
    expect(screen.getByRole('img', { name: 'Drawing' }).tagName).toBe('CANVAS')
    expect(renderAllStrokes.mock.calls.at(-1)?.[3]).toEqual([sampleTelephoneStroke])
  })
})
