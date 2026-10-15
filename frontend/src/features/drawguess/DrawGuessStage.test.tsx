import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { PlayerDto } from '@/api/types'
import type { RealtimeClient } from '@/features/realtime/realtimeClient'
import { RealtimeContext } from '@/features/realtime/realtimeContext'
import { useGameSessionStore } from '@/stores/gameSessionStore'
import { DrawGuessChatPanel } from './components/DrawGuessChatPanel'
import { DrawGuessStage } from './DrawGuessStage'
import { useDrawGuessStore } from './drawGuessStore'

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

describe('DrawGuessStage', () => {
  let mockClient: {
    sendCommand: ReturnType<typeof vi.fn>
  }

  beforeEach(() => {
    useDrawGuessStore.getState().reset()
    useGameSessionStore.getState().reset()

    mockClient = {
      sendCommand: vi.fn().mockReturnValue('action-1'),
    }

    // Mock HTMLCanvasElement.getContext
    HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue({
      save: vi.fn(),
      restore: vi.fn(),
      beginPath: vi.fn(),
      arc: vi.fn(),
      moveTo: vi.fn(),
      lineTo: vi.fn(),
      quadraticCurveTo: vi.fn(),
      fill: vi.fn(),
      stroke: vi.fn(),
      fillRect: vi.fn(),
      scale: vi.fn(),
      lineCap: 'butt',
      lineJoin: 'miter',
      strokeStyle: '#000',
      fillStyle: '#000',
      lineWidth: 1,
    })

    Element.prototype.getBoundingClientRect = vi.fn().mockReturnValue({
      left: 0,
      top: 0,
      width: 640,
      height: 400,
      right: 640,
      bottom: 400,
      x: 0,
      y: 0,
      toJSON: () => {},
    })
    Element.prototype.scrollIntoView = vi.fn()
  })

  it('renders WORD_CHOICE modal for drawer and allows selecting word', () => {
    useGameSessionStore.setState({
      phase: 'WORD_CHOICE',
      phaseId: 'phase-wc-1',
      deadlineAt: '2026-10-14T12:00:15.000Z',
    })
    useDrawGuessStore.setState({
      drawerGuestId: 'p1',
      wordChoices: ['VELOCIRAPTOR', 'VOLCANO', 'FOSSIL'],
    })

    render(
      <RealtimeContext.Provider value={mockClient as unknown as RealtimeClient}>
        <DrawGuessStage players={mockPlayers} currentGuestId="p1" />
      </RealtimeContext.Provider>,
    )

    expect(screen.getByRole('heading', { name: 'Pick Your Word!' })).toBeInTheDocument()
    const volcanoBtn = screen.getByRole('button', { name: 'VOLCANO' })
    fireEvent.click(volcanoBtn)

    expect(mockClient.sendCommand).toHaveBeenCalledWith({
      type: 'CHOOSE_WORD',
      phaseId: 'phase-wc-1',
      payload: { wordIndex: 1 },
    })
  })

  it('renders DRAWING phase with toolbar for drawer', () => {
    useGameSessionStore.setState({
      phase: 'DRAWING',
      phaseId: 'phase-dr-1',
      deadlineAt: '2026-10-14T12:01:15.000Z',
    })
    useDrawGuessStore.setState({
      drawerGuestId: 'p1',
      word: 'VOLCANO',
      hint: '_______',
    })

    render(
      <RealtimeContext.Provider value={mockClient as unknown as RealtimeClient}>
        <DrawGuessStage players={mockPlayers} currentGuestId="p1" />
      </RealtimeContext.Provider>,
    )

    expect(screen.getByText('Your word:')).toBeInTheDocument()
    expect(screen.getByText('VOLCANO')).toBeInTheDocument()
    // Drawing toolbar should be present
    expect(screen.getByRole('toolbar', { name: /drawing controls/i })).toBeInTheDocument()
  })

  it('renders DRAWING phase with guess input and hints for guesser', () => {
    useGameSessionStore.setState({
      phase: 'DRAWING',
      phaseId: 'phase-dr-1',
      deadlineAt: '2026-10-14T12:01:15.000Z',
    })
    useDrawGuessStore.setState({
      drawerGuestId: 'p1',
      word: null, // guessers do not see word
      hint: 'V___A_O',
    })

    render(
      <RealtimeContext.Provider value={mockClient as unknown as RealtimeClient}>
        <DrawGuessStage players={mockPlayers} currentGuestId="p2" />
        <DrawGuessChatPanel players={mockPlayers} currentGuestId="p2" />
      </RealtimeContext.Provider>,
    )

    // Drawing toolbar should NOT be rendered for guessers
    expect(screen.queryByRole('toolbar', { name: /drawing controls/i })).not.toBeInTheDocument()

    // Guess input should be enabled
    const input = screen.getByPlaceholderText('Type your guess here…')
    expect(input).toBeEnabled()

    fireEvent.change(input, { target: { value: 'volcano' } })
    const submitBtn = screen.getByRole('button', { name: /send guess/i })
    fireEvent.click(submitBtn)

    expect(mockClient.sendCommand).toHaveBeenCalledWith({
      type: 'SUBMIT_GUESS',
      phaseId: 'phase-dr-1',
      payload: { text: 'volcano' },
    })
  })

  it('renders ROUND_END overlay with word reveal and scores', () => {
    useGameSessionStore.setState({
      phase: 'ROUND_END',
      phaseId: 'phase-re-1',
      deadlineAt: '2026-10-14T12:01:21.000Z',
    })
    useDrawGuessStore.setState({
      drawerGuestId: 'p1',
      word: 'VOLCANO',
      round: 1,
      totalRounds: 4,
      roundEnd: {
        reason: 'ALL_GUESSED',
        scoreDeltas: { p1: 300, p2: 450 },
      },
    })

    render(
      <RealtimeContext.Provider value={mockClient as unknown as RealtimeClient}>
        <DrawGuessStage players={mockPlayers} currentGuestId="p2" />
      </RealtimeContext.Provider>,
    )

    expect(screen.getByRole('heading', { name: 'Round 1 of 4 Complete!' })).toBeInTheDocument()
    expect(screen.getByText('All Guessed!')).toBeInTheDocument()
  })
})
