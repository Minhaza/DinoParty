import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { GameSnapshot } from '@/api/types'
import { useDinoImpostorStore } from '@/features/impostor/dino/dinoImpostorStore'
import { DI_PLAYERS, VIEWER_ARTIST, voteArtist } from '@/features/impostor/dino/fixtures'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import { GameStage } from './GameStage'

const renderStage = (roomStatus: string) =>
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <GameStage
        roomStatus={roomStatus}
        gameKey="dino-impostor"
        players={DI_PLAYERS}
        currentGuestId={VIEWER_ARTIST}
        isHost={true}
        onVoteRematch={vi.fn()}
        onReturnToLobby={vi.fn()}
      />
    </QueryClientProvider>,
  )

describe('GameStage × Dino Impostor', () => {
  beforeEach(() => {
    useGameSessionStore.getState().reset()
    useDinoImpostorStore.getState().reset()
    HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue(null)
  })

  it('renders the Dino Impostor stage while playing', () => {
    useGameSessionStore.getState().applySnapshot(voteArtist as unknown as GameSnapshot)
    useDinoImpostorStore.getState().applySnapshot(voteArtist)
    renderStage('PLAYING')
    expect(screen.getByRole('region', { name: /dino impostor gameplay stage/i })).toBeVisible()
    expect(screen.queryByText(/interactive game controls/i)).not.toBeInTheDocument()
  })

  it('reuses the shared scoreboard and rematch controls for results', () => {
    useGameSessionStore.setState({
      status: 'FINISHED',
      endReason: 'COMPLETED',
      results: {
        standings: [{ rank: 1, guestId: VIEWER_ARTIST, nickname: 'Rexy', score: 7, detail: {} }],
      },
      rematch: { votes: {} },
    })
    renderStage('RESULTS')
    expect(screen.getByText(/final standings/i)).toBeInTheDocument()
    expect(screen.getByText(/play again/i)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: /dino impostor gameplay stage/i })).toBeNull()
  })
})
