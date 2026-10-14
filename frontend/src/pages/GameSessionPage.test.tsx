import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { GameSnapshot, RoomDto } from '@/api/types'
import { useAuthStore } from '@/stores/authStore'
import { useGameSessionStore } from '@/stores/gameSessionStore'
import { useRoomStore } from '@/stores/roomStore'
import { GUEST, mockApi, renderApp, roomFixture } from '@/test/mockApi'

import { GameSessionPage } from './GameSessionPage'

const guestMe = { 'GET /guests/me': { status: 200, body: GUEST } }

const OTHER_PLAYER = {
  guestId: '22222222-2222-2222-2222-222222222222',
  nickname: 'Tricia',
  avatarKey: 'dino-tricera',
  avatarColor: null,
  ready: true,
  connection: 'CONNECTED' as const,
  isHost: false,
  score: 50,
}

function renderSessionPage(path = '/rooms/AB3D7K/play') {
  return renderApp(
    [
      { path: '/rooms/:code/play', element: <GameSessionPage /> },
      { path: '/rooms/:code/results', element: <GameSessionPage /> },
      { path: '/rooms/:code', element: <p>Lobby Room</p> },
      { path: '/', element: <p>Home Page</p> },
    ],
    path,
  )
}

describe('GameSessionPage', () => {
  beforeEach(() => {
    localStorage.clear()
    useAuthStore.setState({ token: 'tok', lastRoomCode: null })
    useRoomStore.getState().reset()
    useGameSessionStore.getState().reset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders loading state initially then loads room and players', async () => {
    const room: RoomDto = {
      ...roomFixture(),
      gameKey: 'draw-guess',
      status: 'PLAYING',
      players: [
        {
          guestId: GUEST.id,
          nickname: GUEST.nickname,
          avatarKey: GUEST.avatarKey,
          avatarColor: null,
          ready: true,
          connection: 'CONNECTED',
          isHost: true,
          score: 100,
        },
        OTHER_PLAYER,
      ],
    }

    mockApi({
      ...guestMe,
      'GET /rooms/AB3D7K': { status: 200, body: room },
    })

    renderSessionPage()

    expect(await screen.findByText('Draw & Guess')).toBeInTheDocument()
    expect(screen.getByText('AB3D7K')).toBeInTheDocument()
    const triciaElements = await screen.findAllByText('Tricia')
    expect(triciaElements.length).toBeGreaterThanOrEqual(1)
  })

  it('prompts non-members to join room from lobby', async () => {
    // Non-member preview response
    const preview = {
      code: 'AB3D7K',
      gameKey: 'draw-guess',
      hostNickname: 'HostDino',
      playerCount: 3,
      maxPlayers: 8,
      status: 'PLAYING',
    }

    mockApi({
      ...guestMe,
      'GET /rooms/AB3D7K': { status: 200, body: preview },
    })

    renderSessionPage()

    expect(await screen.findByRole('heading', { name: 'Join Room First' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to Lobby' })).toHaveAttribute(
      'href',
      '/rooms/AB3D7K',
    )
  })

  it('renders game stage with phase and server countdown when PLAYING', async () => {
    const room: RoomDto = {
      ...roomFixture(),
      gameKey: 'draw-guess',
      status: 'PLAYING',
      players: [
        {
          guestId: GUEST.id,
          nickname: GUEST.nickname,
          avatarKey: GUEST.avatarKey,
          avatarColor: null,
          ready: true,
          connection: 'CONNECTED',
          isHost: true,
          score: 100,
        },
        OTHER_PLAYER,
      ],
    }

    const gameSnapshot: GameSnapshot = {
      sessionId: 'sess-123',
      gameKey: 'draw-guess',
      status: 'RUNNING',
      order: [GUEST.id, OTHER_PLAYER.guestId],
      phase: 'DRAWING_PHASE',
      phaseId: 'p-1',
      phaseStartedAt: new Date().toISOString(),
      deadlineAt: new Date(Date.now() + 30000).toISOString(),
      scores: { [GUEST.id]: 100, [OTHER_PLAYER.guestId]: 50 },
      players: [
        { guestId: GUEST.id, status: 'ACTIVE' },
        { guestId: OTHER_PLAYER.guestId, status: 'ACTIVE' },
      ],
      results: null,
      rematch: null,
    }

    mockApi({
      ...guestMe,
      'GET /rooms/AB3D7K': { status: 200, body: room },
    })

    // Seed gameSessionStore with active session snapshot
    useGameSessionStore.getState().applySnapshot(gameSnapshot, room)

    renderSessionPage()

    const drawingPhaseElements = await screen.findAllByText('Drawing Phase')
    expect(drawingPhaseElements.length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('(Your Turn)')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Draw & Guess gameplay stage' })).toBeInTheDocument()
  })

  it('renders results view and rematch controls when in RESULTS status', async () => {
    const room: RoomDto = {
      ...roomFixture(),
      gameKey: 'draw-guess',
      status: 'RESULTS',
      players: [
        {
          guestId: GUEST.id,
          nickname: GUEST.nickname,
          avatarKey: GUEST.avatarKey,
          avatarColor: null,
          ready: true,
          connection: 'CONNECTED',
          isHost: true,
          score: 150,
        },
        OTHER_PLAYER,
      ],
    }

    const gameSnapshot: GameSnapshot = {
      sessionId: 'sess-123',
      gameKey: 'draw-guess',
      status: 'FINISHED',
      order: [GUEST.id, OTHER_PLAYER.guestId],
      phase: 'RESULTS',
      phaseId: 'p-res',
      phaseStartedAt: new Date().toISOString(),
      deadlineAt: null,
      scores: { [GUEST.id]: 150, [OTHER_PLAYER.guestId]: 50 },
      players: [
        { guestId: GUEST.id, status: 'ACTIVE' },
        { guestId: OTHER_PLAYER.guestId, status: 'ACTIVE' },
      ],
      results: {
        standings: [
          { rank: 1, guestId: GUEST.id, score: 150, nickname: GUEST.nickname },
          { rank: 2, guestId: OTHER_PLAYER.guestId, score: 50, nickname: OTHER_PLAYER.nickname },
        ],
      },
      rematch: { votes: {} },
    }

    mockApi({
      ...guestMe,
      'GET /rooms/AB3D7K': { status: 200, body: room },
    })

    useGameSessionStore.getState().applySnapshot(gameSnapshot, room)

    renderSessionPage('/rooms/AB3D7K/results')

    expect(await screen.findByText('Game Finished!')).toBeInTheDocument()
    expect(screen.getByText('Play Again?')).toBeInTheDocument()
    expect(screen.getByText('0 / 2 players ready')).toBeInTheDocument()
  })

  it('allows user to open leave dialog and navigate away', async () => {
    const user = userEvent.setup()
    const room: RoomDto = {
      ...roomFixture(),
      gameKey: 'draw-guess',
      status: 'PLAYING',
      players: [
        {
          guestId: GUEST.id,
          nickname: GUEST.nickname,
          avatarKey: GUEST.avatarKey,
          avatarColor: null,
          ready: true,
          connection: 'CONNECTED',
          isHost: false,
          score: 0,
        },
      ],
    }

    mockApi({
      ...guestMe,
      'GET /rooms/AB3D7K': { status: 200, body: room },
      'POST /rooms/AB3D7K/leave': { status: 200, body: {} },
    })

    renderSessionPage()

    const leaveBtn = await screen.findByRole('button', { name: 'Leave' })
    await user.click(leaveBtn)

    expect(screen.getByRole('heading', { name: 'Leave Game Session?' })).toBeInTheDocument()
    const confirmLeave = screen.getByRole('button', { name: 'Leave Game' })
    await user.click(confirmLeave)

    expect(await screen.findByText('Home Page')).toBeInTheDocument()
  })
})
