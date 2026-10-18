import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { PlayerDto } from '@/api/types'
import { albumsResponse, REVEAL_SESSION } from '@/features/telephone/fixtures'
import { useTelephoneStore } from '@/features/telephone/telephoneStore'
import { mockApi } from '@/test/mockApi'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import { GameStage } from './GameStage'

const players: PlayerDto[] = [
  {
    guestId: 'guest-1',
    nickname: 'Rex',
    avatarKey: 'dino-rex',
    avatarColor: null,
    ready: true,
    connection: 'CONNECTED',
    isHost: true,
    score: null,
  },
]

const renderStage = (roomStatus: string) =>
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <GameStage
        roomStatus={roomStatus}
        gameKey="telephone"
        players={players}
        currentGuestId="guest-1"
        isHost={true}
        onVoteRematch={vi.fn()}
        onReturnToLobby={vi.fn()}
      />
    </QueryClientProvider>,
  )

describe('GameStage × Dino Telephone', () => {
  beforeEach(() => {
    useGameSessionStore.getState().reset()
    useTelephoneStore.getState().reset()
  })

  it('renders the Telephone stage inside the shared shell while playing', () => {
    renderStage('PLAYING')
    expect(screen.getByRole('region', { name: /dino telephone gameplay stage/i })).toBeVisible()
    expect(screen.queryByText(/interactive game controls/i)).not.toBeInTheDocument()
  })

  it('TP11 shows the albums instead of a scoreboard, with the shared rematch controls', async () => {
    mockApi({
      [`GET /sessions/${REVEAL_SESSION}/albums`]: { status: 200, body: albumsResponse },
    })
    useGameSessionStore.setState({
      sessionId: REVEAL_SESSION,
      status: 'FINISHED',
      endReason: 'COMPLETED',
      results: { standings: [] },
      rematch: { votes: {} },
    })
    renderStage('RESULTS')
    expect(screen.getByRole('heading', { name: /the albums are in/i })).toBeInTheDocument()
    expect(await screen.findByRole('list', { name: /albums/i })).toBeInTheDocument()
    expect(screen.queryByText(/final standings/i)).not.toBeInTheDocument()
    expect(screen.getByText(/play again/i)).toBeInTheDocument()
    vi.unstubAllGlobals()
  })
})
