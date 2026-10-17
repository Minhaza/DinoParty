import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { GameSnapshot, PlayerDto } from '@/api/types'
import { useConnectionStore } from '@/features/realtime/connectionStore'
import type { RealtimeClient } from '@/features/realtime/realtimeClient'
import { RealtimeContext } from '@/features/realtime/realtimeContext'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import {
  albumRevealed,
  PHASE_REVEAL,
  REVEAL_SESSION,
  revealPhaseStarted,
  revealSnapshot,
} from '../fixtures'
import { useRevealStore } from '../revealStore'
import { TelephoneRevealView } from './TelephoneRevealView'

const players: PlayerDto[] = ['guest-1', 'guest-2', 'guest-3', 'guest-4'].map((guestId, i) => ({
  guestId,
  nickname: ['Rex', 'Trixie', 'Ptera', 'Steggy'][i],
  avatarKey: 'dino-rex',
  avatarColor: null,
  ready: true,
  connection: 'CONNECTED',
  isHost: i === 0,
  score: null,
}))

let counter = 0
const sendCommand = vi.fn(() => `nav-${++counter}`)

function renderView(isHost: boolean) {
  return render(
    <RealtimeContext.Provider value={{ sendCommand } as unknown as RealtimeClient}>
      <TelephoneRevealView players={players} currentGuestId="guest-1" isHost={isHost} />
    </RealtimeContext.Provider>,
  )
}

function startReveal() {
  act(() => {
    useGameSessionStore.setState({
      sessionId: REVEAL_SESSION,
      gameKey: 'telephone',
      status: 'RUNNING',
      phase: 'REVEAL',
      phaseId: PHASE_REVEAL,
      order: ['guest-1', 'guest-2', 'guest-3', 'guest-4'],
    })
    useRevealStore.getState().applyPhaseStarted(revealPhaseStarted, REVEAL_SESSION)
    useRevealStore.getState().applyAlbumRevealed(albumRevealed(0, 0))
  })
}

const reveal = (chain: number, entry: number, newly = true) =>
  act(() => useRevealStore.getState().applyAlbumRevealed(albumRevealed(chain, entry, newly)))

const sent = () =>
  sendCommand.mock.calls.map(
    (c) => (c as unknown as [{ type: string; phaseId: string; payload: unknown }])[0],
  )

describe('TelephoneRevealView', () => {
  beforeEach(() => {
    sendCommand.mockClear()
    useRevealStore.getState().reset()
    useGameSessionStore.getState().reset()
    useConnectionStore.setState({ status: 'CONNECTED' })
    HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue(null)
    vi.stubGlobal(
      'requestAnimationFrame',
      vi.fn(() => 1),
    )
    vi.stubGlobal('cancelAnimationFrame', vi.fn())
    startReveal()
  })

  afterEach(() => vi.unstubAllGlobals())

  it('TP6 opens the first album on its prompt and shows where everyone is', () => {
    renderView(false)

    expect(screen.getByRole('heading', { name: /rex.s album/i })).toBeInTheDocument()
    expect(screen.getByText('Prompt 1')).toBeInTheDocument()
    expect(screen.getAllByText('Album 1 of 4 — Step 1 of 4').length).toBeGreaterThan(0)
  })

  it('TP8 never renders an entry the server has not revealed', () => {
    renderView(true)
    expect(screen.queryByText('Prompt 2')).not.toBeInTheDocument()
    expect(screen.queryByText(/description 1\.3/i)).not.toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(1)
  })

  it('TP6 the host reveals the next entry with the cursor it saw, once', () => {
    renderView(true)

    const next = screen.getByRole('button', { name: /reveal next/i })
    expect(screen.getByRole('button', { name: /^previous$/i })).toBeDisabled()
    fireEvent.click(next)
    fireEvent.click(next) // a double click sends nothing more while the first is pending

    expect(sent()).toEqual([
      { type: 'REVEAL_NEXT', phaseId: PHASE_REVEAL, payload: { chainIndex: 0, entryIndex: 0 } },
    ])
    expect(next).toBeDisabled()

    reveal(0, 1)
    expect(screen.getByRole('button', { name: /reveal next/i })).toBeEnabled()
    expect(screen.getAllByRole('article')).toHaveLength(2)
  })

  it('the host acts on the shared cursor even while browsing locally', () => {
    renderView(true)
    reveal(0, 1)
    reveal(0, 2)
    fireEvent.click(screen.getByRole('button', { name: /^back$/i }))
    expect(screen.getAllByText('Album 1 of 4 — Step 2 of 4').length).toBeGreaterThan(0)

    fireEvent.click(screen.getByRole('button', { name: /reveal next/i }))
    expect(sent()[0].payload).toEqual({ chainIndex: 0, entryIndex: 2 })
  })

  it('offers to finish on the very last entry', () => {
    renderView(true)
    for (let i = 1; i < 16; i++) reveal(Math.floor(i / 4), i % 4)
    expect(screen.getByRole('button', { name: /finish/i })).toBeInTheDocument()
  })

  it('TP6 a player without the host role cannot move the shared reveal', () => {
    renderView(false)
    expect(screen.queryByRole('button', { name: /reveal next/i })).not.toBeInTheDocument()
    expect(screen.getByText(/host is turning the pages/i)).toBeInTheDocument()
    expect(sendCommand).not.toHaveBeenCalled()
  })

  it('browses revealed entries locally with buttons and arrow keys', () => {
    renderView(false)
    reveal(0, 1)
    reveal(0, 2)

    const stage = screen.getByRole('region', { name: /album reveal/i })
    fireEvent.keyDown(stage, { key: 'ArrowLeft' })
    expect(screen.getAllByText('Album 1 of 4 — Step 2 of 4').length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: /back to live/i })).toBeInTheDocument()

    fireEvent.keyDown(stage, { key: 'ArrowRight' })
    expect(screen.getAllByText('Album 1 of 4 — Step 3 of 4').length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: /back to live/i })).not.toBeInTheDocument()
    expect(sendCommand).not.toHaveBeenCalled()
  })

  it('browses to a previous album without rendering a timeline strip', () => {
    renderView(false)
    for (let i = 1; i <= 4; i++) reveal(Math.floor(i / 4), i % 4) // into album 2

    expect(screen.queryByRole('navigation', { name: /album timeline/i })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /previous album/i }))
    expect(screen.getAllByText('Album 1 of 4 — Step 4 of 4').length).toBeGreaterThan(0)
    expect(screen.queryByRole('navigation', { name: /album timeline/i })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^back$/i }))
    expect(screen.getAllByText('Album 1 of 4 — Step 3 of 4').length).toBeGreaterThan(0)
  })

  it('announces each newly revealed entry politely', () => {
    renderView(false)
    reveal(0, 1)
    const live = screen.getByTestId('reveal-announcer')
    expect(live).toHaveAttribute('aria-live', 'polite')
    expect(live).toHaveTextContent(/step 2 of 4.*trixie drew it/i)
  })

  it('TP6 a reconnect restores the position; a withheld drawing points to the results', () => {
    act(() => {
      useRevealStore.getState().reset()
      useGameSessionStore.getState().applySnapshot(revealSnapshot as unknown as GameSnapshot)
      useRevealStore.getState().applySnapshot(revealSnapshot)
    })
    renderView(false)

    expect(screen.getAllByText('Album 1 of 4 — Step 4 of 4').length).toBeGreaterThan(0)
    expect(screen.getByText(/available in the results/i)).toBeInTheDocument()
    expect(screen.queryByText('Prompt 3')).not.toBeInTheDocument()
  })

  it('tells a former host that the pages are no longer theirs to turn', () => {
    renderView(true)
    act(() => {
      useRevealStore.getState().trackNav('nav-x')
      useRevealStore
        .getState()
        .applyCommandError({ code: 'NOT_HOST', message: 'no', retryable: false }, 'nav-x')
    })
    expect(screen.getByRole('alert')).toHaveTextContent(/only the host/i)
  })

  it('allows host to toggle auto reveal and change speed', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    renderView(true)

    expect(screen.getByRole('button', { name: '3s' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '5s' })).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(screen.getByRole('button', { name: '3s' }))
    expect(screen.getByRole('button', { name: '3s' })).toHaveAttribute('aria-pressed', 'true')

    const autoBtn = screen.getByRole('button', { name: /auto reveal/i })
    fireEvent.click(autoBtn)

    expect(screen.getByRole('button', { name: /pause auto reveal/i })).toBeInTheDocument()

    await act(async () => {
      vi.advanceTimersByTime(3100)
    })

    expect(sent()).toEqual([
      { type: 'REVEAL_NEXT', phaseId: PHASE_REVEAL, payload: { chainIndex: 0, entryIndex: 0 } },
    ])

    reveal(0, 1)

    fireEvent.click(screen.getByRole('button', { name: /pause auto reveal/i }))
    expect(screen.getByRole('button', { name: /auto reveal/i })).toBeInTheDocument()

    vi.useRealTimers()
  })

  it('pauses auto reveal when host clicks Previous', () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    renderView(true)
    reveal(0, 1)

    fireEvent.click(screen.getByRole('button', { name: /auto reveal/i }))
    expect(screen.getByRole('button', { name: /pause auto reveal/i })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /^previous$/i }))
    expect(screen.getByRole('button', { name: /auto reveal/i })).toBeInTheDocument()

    vi.useRealTimers()
  })
})
