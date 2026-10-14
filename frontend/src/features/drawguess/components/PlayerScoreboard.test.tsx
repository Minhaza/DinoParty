import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import type { PlayerDto } from '@/api/types'
import { PlayerScoreboard } from './PlayerScoreboard'

const mockPlayers: PlayerDto[] = [
  {
    guestId: 'p1',
    nickname: 'Rex',
    avatarKey: 'dino-rex',
    avatarColor: null,
    ready: true,
    connection: 'CONNECTED',
    isHost: true,
    score: 100,
  },
  {
    guestId: 'p2',
    nickname: 'Trixie',
    avatarKey: 'dino-tricera',
    avatarColor: null,
    ready: true,
    connection: 'CONNECTED',
    isHost: false,
    score: 300,
  },
  {
    guestId: 'p3',
    nickname: 'Spike',
    avatarKey: 'dino-stego',
    avatarColor: null,
    ready: true,
    connection: 'DISCONNECTED',
    isHost: false,
    score: 50,
  },
]

describe('PlayerScoreboard', () => {
  it('renders player rankings, drawer badge, and solved status', () => {
    render(
      <PlayerScoreboard
        players={mockPlayers}
        drawerGuestId="p1"
        guessedGuestIds={['p2']}
        scores={{ p1: 100, p2: 300, p3: 50 }}
        scoreDeltas={{ p2: 150 }}
        currentGuestId="p1"
      />,
    )

    // Sorted descending: Trixie (300), Rex (100), Spike (50)
    expect(screen.getByText('Trixie')).toBeInTheDocument()
    expect(screen.getByText('Rex')).toBeInTheDocument()
    expect(screen.getByText('Spike')).toBeInTheDocument()

    expect(screen.getByTitle('Drawer')).toBeInTheDocument()
    expect(screen.getByTitle('Guessed correctly')).toBeInTheDocument()
    expect(screen.getByTitle('Disconnected')).toBeInTheDocument()
    expect(screen.getByText('+150')).toBeInTheDocument()
  })
})
