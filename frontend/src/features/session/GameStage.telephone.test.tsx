import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { PlayerDto } from '@/api/types'
import { useTelephoneStore } from '@/features/telephone/telephoneStore'
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
    <GameStage
      roomStatus={roomStatus}
      gameKey="telephone"
      players={players}
      currentGuestId="guest-1"
      isHost={true}
      onVoteRematch={vi.fn()}
      onReturnToLobby={vi.fn()}
    />,
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

  it('TP11 shows no scoreboard in results, because Telephone is unscored', () => {
    useGameSessionStore.setState({
      status: 'FINISHED',
      endReason: 'COMPLETED',
      results: { standings: [] },
      rematch: { votes: {} },
    })
    renderStage('RESULTS')
    expect(screen.getByText(/every chain is complete/i)).toBeInTheDocument()
    expect(screen.queryByText(/final standings/i)).not.toBeInTheDocument()
  })
})
