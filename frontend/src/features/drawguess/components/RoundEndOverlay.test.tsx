import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import type { PlayerDto } from '@/api/types'
import { RoundEndOverlay } from './RoundEndOverlay'

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
    score: 200,
  },
]

describe('RoundEndOverlay', () => {
  it('displays round completion, secret word, reason, and points earned', () => {
    render(
      <RoundEndOverlay
        round={2}
        totalRounds={4}
        word="VELOCIRAPTOR"
        roundEnd={{
          reason: 'ALL_GUESSED',
          scoreDeltas: { p1: 300, p2: 450 },
        }}
        players={mockPlayers}
        deadlineAt="2026-10-14T12:00:20.000Z"
      />,
    )

    expect(screen.getByRole('heading', { name: 'Round 2 of 4 Complete!' })).toBeInTheDocument()
    expect(screen.getByText('VELOCIRAPTOR')).toBeInTheDocument()
    expect(screen.getByText('All Guessed!')).toBeInTheDocument()
    expect(screen.getByText('Rex')).toBeInTheDocument()
    expect(screen.getByText('+300')).toBeInTheDocument()
    expect(screen.getByText('Trixie')).toBeInTheDocument()
    expect(screen.getByText('+450')).toBeInTheDocument()
  })

  it('handles TIME reason', () => {
    render(
      <RoundEndOverlay
        round={1}
        totalRounds={3}
        word="FOSSIL"
        roundEnd={{
          reason: 'TIME',
          scoreDeltas: {},
        }}
        players={mockPlayers}
        deadlineAt={null}
      />,
    )

    expect(screen.getByText("Time's Up!")).toBeInTheDocument()
    expect(screen.getByText('FOSSIL')).toBeInTheDocument()
  })
})
