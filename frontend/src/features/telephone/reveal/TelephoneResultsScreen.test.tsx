import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { PlayerDto } from '@/api/types'
import { mockApi, problem } from '@/test/mockApi'

import { albumDto, albumsResponse, REVEAL_SESSION } from '../fixtures'
import { TelephoneResultsScreen } from './TelephoneResultsScreen'

const players: PlayerDto[] = ['guest-1', 'guest-2', 'guest-3'].map((guestId, i) => ({
  guestId,
  nickname: ['Rex', 'Trixie', 'Ptera'][i],
  avatarKey: 'dino-rex',
  avatarColor: null,
  ready: true,
  connection: 'CONNECTED',
  isHost: i === 0,
  score: null,
}))

const base = `/sessions/${REVEAL_SESSION}/albums`

function renderScreen(props: Partial<React.ComponentProps<typeof TelephoneResultsScreen>> = {}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <TelephoneResultsScreen
        sessionId={REVEAL_SESSION}
        endReason="COMPLETED"
        players={players}
        retryDelayMs={5}
        {...props}
      />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue(null)
  vi.stubGlobal(
    'requestAnimationFrame',
    vi.fn(() => 1),
  )
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
})
afterEach(() => vi.unstubAllGlobals())

describe('TelephoneResultsScreen', () => {
  it('TP11 lists every album with its owner, and no scores or winners', async () => {
    mockApi({ [`GET ${base}`]: { status: 200, body: albumsResponse } })
    renderScreen()

    expect(screen.getByRole('heading', { name: /the albums are in/i })).toBeInTheDocument()
    const list = await screen.findByRole('list', { name: /albums/i })
    expect(within(list).getAllByRole('button', { name: /open .* album/i })).toHaveLength(4)
    expect(within(list).getByText(/rex.s album/i)).toBeInTheDocument()
    expect(within(list).getByText(/a blank page or two/i)).toBeInTheDocument()
    expect(screen.queryByText(/rank|winner|score|standings/i)).not.toBeInTheDocument()
  })

  it('opens an album with every entry in order, then goes back to the list', async () => {
    mockApi({
      [`GET ${base}`]: { status: 200, body: albumsResponse },
      [`GET ${base}/2`]: { status: 200, body: albumDto(2) },
    })
    renderScreen()

    fireEvent.click(await screen.findByRole('button', { name: /open ptera.s album/i }))
    expect(await screen.findAllByText('Prompt 3')).not.toHaveLength(0)
    expect(screen.getAllByRole('article')).toHaveLength(4)
    expect(screen.getByText(/left the game/i)).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: /album timeline/i })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /all albums/i }))
    expect(await screen.findByRole('list', { name: /albums/i })).toBeInTheDocument()
  })

  it('replays an album page by page and can show it whole again', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mockApi({
      [`GET ${base}`]: { status: 200, body: albumsResponse },
      [`GET ${base}/0`]: { status: 200, body: albumDto(0) },
    })
    renderScreen()
    fireEvent.click(await screen.findByRole('button', { name: /open rex.s album/i }))
    await screen.findAllByText('Prompt 1')

    fireEvent.click(screen.getByRole('button', { name: /replay album/i }))
    expect(screen.getAllByRole('article')).toHaveLength(1)
    for (let i = 0; i < 4; i++) await act(() => vi.advanceTimersByTimeAsync(1_300))
    expect(screen.getAllByRole('article')).toHaveLength(4)

    fireEvent.click(screen.getByRole('button', { name: /replay album/i }))
    fireEvent.click(screen.getByRole('button', { name: /show whole album/i }))
    expect(screen.getAllByRole('article')).toHaveLength(4)
    vi.useRealTimers()
  })

  it('waits while the albums are still being stored', async () => {
    let calls = 0
    mockApi({
      [`GET ${base}`]: () =>
        ++calls < 3 ? problem(409, 'RESULTS_NOT_READY') : { status: 200, body: albumsResponse },
    })
    renderScreen()
    expect(await screen.findByText(/gathering the albums/i)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('list', { name: /albums/i })).toBeInTheDocument())
    expect(calls).toBe(3)
  })

  it('TP11 explains an early end and flags the unfinished albums', async () => {
    mockApi({
      [`GET ${base}`]: {
        status: 200,
        body: {
          ...albumsResponse,
          complete: false,
          albums: albumsResponse.albums.map((a) => ({ ...a, entryCount: 2, complete: false })),
        },
      },
    })
    renderScreen({ endReason: 'INSUFFICIENT_PLAYERS' })
    expect(screen.getByRole('heading', { name: /the game ended early/i })).toBeInTheDocument()
    expect(await screen.findAllByText(/unfinished/i)).toHaveLength(4)
  })

  it('has a meaningful empty state for a game that kept no albums', async () => {
    mockApi({ [`GET ${base}`]: { status: 200, body: { ...albumsResponse, albums: [] } } })
    renderScreen()
    expect(await screen.findByText(/no albums were kept/i)).toBeInTheDocument()
  })

  it('handles a refused read without showing anything of the albums', async () => {
    mockApi({ [`GET ${base}`]: problem(403, 'NOT_IN_ROOM') })
    renderScreen()
    expect(await screen.findByRole('alert')).toHaveTextContent(/no longer part of this room/i)
    expect(screen.queryByRole('list', { name: /albums/i })).not.toBeInTheDocument()
  })

  it('never shows the albums of another session', async () => {
    const calls = mockApi({
      [`GET ${base}`]: { status: 200, body: albumsResponse },
      'GET /sessions/session-next/albums': { status: 200, body: { ...albumsResponse, albums: [] } },
    })
    const view = renderScreen()
    await screen.findByRole('list', { name: /albums/i })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    view.rerender(
      <QueryClientProvider client={client}>
        <TelephoneResultsScreen sessionId="session-next" endReason="COMPLETED" players={players} />
      </QueryClientProvider>,
    )
    expect(await screen.findByText(/no albums were kept/i)).toBeInTheDocument()
    expect(calls.map((c) => c.path)).toContain('/sessions/session-next/albums')
  })
})
