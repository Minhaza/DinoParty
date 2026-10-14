import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { PlayerDto } from '@/api/types'
import { GuessFeed } from './GuessFeed'
import type { DrawGuessChatMessage } from '../types'

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

describe('GuessFeed', () => {
  it('renders chat messages and distinguishes solver chat', () => {
    // Mock scrollIntoView
    Element.prototype.scrollIntoView = vi.fn()

    const chat: DrawGuessChatMessage[] = [
      { guestId: 'p1', text: 'mountain', scope: 'PUBLIC' },
      { guestId: 'p2', text: 'lava', scope: 'GUESSERS' },
    ]

    render(
      <GuessFeed
        chat={chat}
        players={mockPlayers}
        guessedGuestIds={['p2']}
        currentGuestId="p1"
        feedback={null}
      />,
    )

    expect(screen.getByText('mountain')).toBeInTheDocument()
    expect(screen.getByText('lava')).toBeInTheDocument()
    expect(screen.getByText('Solver Chat')).toBeInTheDocument()
    expect(screen.getByText('(You)')).toBeInTheDocument()
    expect(screen.getByText('Trixie guessed the secret word!')).toBeInTheDocument()
  })

  it('renders feedback notice for close guess and duplicate guess', () => {
    Element.prototype.scrollIntoView = vi.fn()

    const { rerender } = render(
      <GuessFeed
        chat={[]}
        players={mockPlayers}
        guessedGuestIds={[]}
        currentGuestId="p1"
        feedback="CLOSE"
      />,
    )

    expect(screen.getByText("You're so close! Check your spelling!")).toBeInTheDocument()

    rerender(
      <GuessFeed
        chat={[]}
        players={mockPlayers}
        guessedGuestIds={[]}
        currentGuestId="p1"
        feedback="DUPLICATE"
      />,
    )

    expect(screen.getByText('That word was already guessed this round!')).toBeInTheDocument()
  })
})
