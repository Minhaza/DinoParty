import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { GameSnapshot } from '@/api/types'
import {
  ARTIST_ROLE,
  sketchySnapshot,
  SS_PLAYERS,
  VIEWER_ARTIST,
} from '@/features/impostor/sketchy/fixtures'
import { useSketchySaurusStore } from '@/features/impostor/sketchy/sketchySaurusStore'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import { GameStage } from './GameStage'

const renderStage = (roomStatus: string) =>
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <GameStage
        roomStatus={roomStatus}
        gameKey="sketchy-saurus"
        players={SS_PLAYERS}
        currentGuestId={VIEWER_ARTIST}
        isHost={true}
        onVoteRematch={vi.fn()}
        onReturnToLobby={vi.fn()}
      />
    </QueryClientProvider>,
  )

describe('GameStage × Sketchy Saurus', () => {
  beforeEach(() => {
    useGameSessionStore.getState().reset()
    useSketchySaurusStore.getState().reset()
    HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue(null)
  })

  it('renders the Sketchy Saurus stage while playing', () => {
    const snapshot = sketchySnapshot('SKETCH', ARTIST_ROLE)
    useGameSessionStore.getState().applySnapshot(snapshot as unknown as GameSnapshot)
    useSketchySaurusStore.getState().applySnapshot(snapshot)
    renderStage('PLAYING')
    expect(screen.getByRole('region', { name: /Sketchy Saurus gameplay stage/i })).toBeVisible()
    expect(screen.queryByText(/interactive game controls/i)).not.toBeInTheDocument()
  })

  it('reuses the shared scoreboard and rematch controls for results', () => {
    useGameSessionStore.setState({
      status: 'FINISHED',
      endReason: 'COMPLETED',
      results: {
        standings: [{ rank: 1, guestId: VIEWER_ARTIST, nickname: 'Rexy', score: 500, detail: {} }],
      },
      rematch: { votes: {} },
    })
    renderStage('RESULTS')
    expect(screen.getByText(/final standings/i)).toBeInTheDocument()
    expect(screen.getByText(/play again/i)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: /Sketchy Saurus stage/i })).toBeNull()
  })
})
