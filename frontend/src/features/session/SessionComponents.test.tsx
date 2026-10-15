import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { PlayerDto, StandingsEntry } from '@/api/types'
import { resetServerClock } from '@/lib/serverClock'

import { GameInterruptedBanner } from './GameInterruptedBanner'
import { GameStartingOverlay } from './GameStartingOverlay'
import { PlayerRoster } from './PlayerRoster'
import { RematchControls } from './RematchControls'
import { Scoreboard } from './Scoreboard'
import { ServerCountdown } from './ServerCountdown'

const mockPlayers: PlayerDto[] = [
  {
    guestId: 'p1',
    nickname: 'Rex',
    avatarKey: 'dino-rex',
    avatarColor: null,
    ready: true,
    connection: 'CONNECTED',
    isHost: true,
    score: 150,
  },
  {
    guestId: 'p2',
    nickname: 'Trixie',
    avatarKey: 'dino-tricera',
    avatarColor: null,
    ready: true,
    connection: 'CONNECTED',
    isHost: false,
    score: 90,
  },
  {
    guestId: 'p3',
    nickname: 'Velo',
    avatarKey: 'dino-raptor',
    avatarColor: null,
    ready: false,
    connection: 'DISCONNECTED',
    isHost: false,
    score: 40,
  },
]

describe('Session Components', () => {
  beforeEach(() => {
    resetServerClock()
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-14T12:00:00.000Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  describe('ServerCountdown', () => {
    it('renders time remaining and aria-label', () => {
      const deadlineAt = '2026-10-14T12:00:45.000Z'
      render(<ServerCountdown deadlineAt={deadlineAt} />)

      expect(screen.getByLabelText('Time remaining: 0:45')).toBeInTheDocument()
      expect(screen.getByText('0:45')).toBeInTheDocument()
    })

    it('returns null when deadline is missing', () => {
      const { container } = render(<ServerCountdown deadlineAt={null} />)
      expect(container.firstChild).toBeNull()
    })
  })

  describe('PlayerRoster', () => {
    it('renders player list with avatars, nicknames, and host badge', () => {
      render(<PlayerRoster players={mockPlayers} currentGuestId="p1" hostGuestId="p1" />)

      expect(screen.getByText('Rex')).toBeInTheDocument()
      expect(screen.getByText('Trixie')).toBeInTheDocument()
      expect(screen.getByText('Velo')).toBeInTheDocument()
      expect(screen.getByText('You')).toBeInTheDocument()
      expect(screen.getByText('Disconnected')).toBeInTheDocument()
      expect(screen.getByText('150')).toBeInTheDocument()
    })

    it('allows host to kick other players', async () => {
      vi.useRealTimers() // user-event works best with real timers
      const onKick = vi.fn().mockResolvedValue(undefined)
      const user = userEvent.setup()

      render(
        <PlayerRoster
          players={mockPlayers}
          currentGuestId="p1"
          hostGuestId="p1"
          isHost={true}
          onKickPlayer={onKick}
        />,
      )

      const kickBtn = screen.getByRole('button', { name: 'Kick Trixie' })
      await user.click(kickBtn)

      expect(screen.getByRole('heading', { name: 'Kick Trixie?' })).toBeInTheDocument()
      const confirmBtn = screen.getByRole('button', { name: 'Kick Player' })
      await user.click(confirmBtn)

      expect(onKick).toHaveBeenCalledWith('p2')
    })
  })

  describe('Scoreboard', () => {
    it('ranks players by standings with medals and positions', () => {
      const standings: StandingsEntry[] = [
        { rank: 1, guestId: 'p1', score: 200, nickname: 'Rex' },
        { rank: 2, guestId: 'p2', score: 100, nickname: 'Trixie' },
      ]

      render(<Scoreboard standings={standings} players={mockPlayers} currentGuestId="p2" />)

      expect(screen.getByText('1st place')).toBeInTheDocument()
      expect(screen.getByText('2nd place')).toBeInTheDocument()
      expect(screen.getByText('200')).toBeInTheDocument()
      expect(screen.getByText('100')).toBeInTheDocument()
    })
  })

  describe('RematchControls', () => {
    it('shows rematch progress and allows voting', async () => {
      vi.useRealTimers()
      const user = userEvent.setup()
      const onVote = vi.fn().mockResolvedValue(undefined)

      render(
        <RematchControls
          votes={{ p1: true }}
          playerCount={3}
          currentGuestId="p2"
          isHost={false}
          onVoteRematch={onVote}
        />,
      )

      expect(screen.getByText('1 / 3 players ready')).toBeInTheDocument()

      const voteBtn = screen.getByRole('button', { name: 'Vote for Rematch' })
      await user.click(voteBtn)

      expect(onVote).toHaveBeenCalledWith(true)
    })
  })

  describe('GameStartingOverlay', () => {
    it('displays 3-second countdown', () => {
      const startsAt = '2026-10-14T12:00:03.000Z'
      render(<GameStartingOverlay startsAt={startsAt} gameTitle="Draw & Guess" />)

      expect(
        screen.getByRole('heading', { name: 'Get Ready for Draw & Guess!' }),
      ).toBeInTheDocument()
      expect(screen.getByLabelText('3 seconds')).toBeInTheDocument()
    })
  })

  describe('GameInterruptedBanner', () => {
    it('renders server restart alert with dismiss button', async () => {
      vi.useRealTimers()
      const user = userEvent.setup()
      const onDismiss = vi.fn()

      render(<GameInterruptedBanner isServerRestart={true} onDismiss={onDismiss} />)

      expect(screen.getByText('Game interrupted by server restart')).toBeInTheDocument()
      const dismissBtn = screen.getByRole('button', { name: 'Dismiss' })
      await user.click(dismissBtn)

      expect(onDismiss).toHaveBeenCalledTimes(1)
    })
  })
})
