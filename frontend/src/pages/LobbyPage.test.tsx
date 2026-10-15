import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import { useAuthStore } from '@/stores/authStore'
import { GUEST, mockApi, problem, renderApp, roomFixture } from '@/test/mockApi'

import { LobbyPage } from './LobbyPage'

beforeEach(() => {
  localStorage.clear()
  useAuthStore.setState({ token: 'tok', lastRoomCode: null })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const guestMe = { 'GET /guests/me': { status: 200, body: GUEST } }

function renderLobby() {
  return renderApp(
    [
      { path: '/rooms/:code', element: <LobbyPage /> },
      { path: '/', element: <p>Home page</p> },
    ],
    '/rooms/ab3d7k',
  )
}

const OTHER = {
  guestId: '33333333-3333-3333-3333-333333333333',
  nickname: 'Tricia',
  avatarKey: 'dino-tricera',
  avatarColor: null,
  ready: false,
  connection: 'CONNECTED',
  isHost: false,
  score: null,
}

describe('LobbyPage', () => {
  it('shows players with host and you badges and lets the host edit settings', async () => {
    const room = roomFixture()
    room.players.push(OTHER)
    mockApi({ ...guestMe, 'GET /rooms/AB3D7K': { status: 200, body: room } })
    renderLobby()

    const list = await screen.findByRole('list', { name: 'Players' })
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(items[0]).toHaveTextContent('Rexy')
    expect(items[0]).toHaveTextContent('Host')
    expect(items[0]).toHaveTextContent('You')
    expect(items[1]).toHaveTextContent('Tricia')
    expect(items[1]).not.toHaveTextContent('Host')
    expect(screen.getByRole('form', { name: 'Room settings' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('AB3D7K')
    expect(useAuthStore.getState().lastRoomCode).toBe('AB3D7K')
  })

  it('hides the settings editor from non-hosts', async () => {
    const room = roomFixture({ hostGuestId: OTHER.guestId })
    room.players = [
      { ...OTHER, isHost: true },
      { ...room.players[0], isHost: false },
    ]
    mockApi({ ...guestMe, 'GET /rooms/AB3D7K': { status: 200, body: room } })
    renderLobby()

    expect(await screen.findByText('Only the host can change these.')).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: 'Room settings' })).not.toBeInTheDocument()
    expect(screen.getByText('Word Pool Settings')).toBeInTheDocument()
  })

  it('displays custom word pack summary for non-hosts in Draw & Guess', async () => {
    const room = roomFixture({
      hostGuestId: OTHER.guestId,
      settings: {
        cycles: 2,
        drawSeconds: 80,
        wordSource: 'custom',
        wordCategories: [],
        difficulty: 'hard',
        wordPackIds: ['p1'],
      },
    })
    room.players = [
      { ...OTHER, isHost: true },
      { ...room.players[0], isHost: false },
    ]
    mockApi({
      ...guestMe,
      'GET /rooms/AB3D7K': { status: 200, body: room },
      'GET /rooms/AB3D7K/word-packs': {
        status: 200,
        body: {
          packs: [
            {
              id: 'p1',
              roomId: 'AB3D7K',
              name: 'Space Pack',
              description: 'Galactic words',
              wordCount: 25,
              createdAt: '2026-01-01T00:00:00Z',
              updatedAt: '2026-01-01T00:00:00Z',
            },
          ],
        },
      },
    })
    renderLobby()

    expect(await screen.findByText('Word Pool Settings')).toBeInTheDocument()
    expect(await screen.findByText('Space Pack')).toBeInTheDocument()
    expect(screen.getByText('Hard')).toBeInTheDocument()
  })

  it('lets a non-member join from the preview', async () => {
    const preview = {
      code: 'AB3D7K',
      gameKey: 'draw-guess',
      status: 'WAITING',
      playerCount: 1,
      maxPlayers: 8,
      hostNickname: 'Tricia',
    }
    const calls = mockApi({
      ...guestMe,
      'GET /rooms/AB3D7K': { status: 200, body: preview },
      'POST /rooms/AB3D7K/join': { status: 200, body: roomFixture() },
    })
    renderLobby()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Join room' }))

    expect(await screen.findByRole('list', { name: 'Players' })).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.path === '/rooms/AB3D7K/join')).toBe(true)
  })

  it('disables joining a full room', async () => {
    mockApi({
      ...guestMe,
      'GET /rooms/AB3D7K': {
        status: 200,
        body: {
          code: 'AB3D7K',
          gameKey: 'telephone',
          status: 'WAITING',
          playerCount: 4,
          maxPlayers: 4,
          hostNickname: 'Tricia',
        },
      },
    })
    renderLobby()

    expect(await screen.findByRole('button', { name: 'Join room' })).toBeDisabled()
    expect(screen.getByText('This room is full.')).toBeInTheDocument()
  })

  it('reports an unknown room', async () => {
    mockApi({ ...guestMe, 'GET /rooms/AB3D7K': problem(404, 'ROOM_NOT_FOUND') })
    renderLobby()

    expect(await screen.findByRole('heading', { name: 'Room not found' })).toBeInTheDocument()
  })

  it('leaves the room, clears the hint and returns home', async () => {
    useAuthStore.setState({ lastRoomCode: 'AB3D7K' })
    const calls = mockApi({
      ...guestMe,
      'GET /rooms/AB3D7K': { status: 200, body: roomFixture() },
      'POST /rooms/AB3D7K/leave': { status: 204 },
    })
    renderLobby()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: /leave room/i }))

    expect(await screen.findByText('Home page')).toBeInTheDocument()
    expect(calls.some((c) => c.path === '/rooms/AB3D7K/leave')).toBe(true)
    expect(useAuthStore.getState().lastRoomCode).toBeNull()
  })

  it('saves host settings with a PATCH', async () => {
    const calls = mockApi({
      ...guestMe,
      'GET /rooms/AB3D7K': { status: 200, body: roomFixture() },
      'PATCH /rooms/AB3D7K/settings': {
        status: 200,
        body: roomFixture({ maxPlayers: 5 }),
      },
    })
    renderLobby()
    const user = userEvent.setup()

    await user.selectOptions(await screen.findByLabelText('Max players'), '5')
    await user.click(screen.getByRole('button', { name: 'Save settings' }))

    expect(await screen.findByText('Settings saved.')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'PATCH')?.body).toMatchObject({ maxPlayers: 5 })
  })

  it('offers a new profile when the stored session was rejected', async () => {
    mockApi({ 'GET /guests/me': problem(401, 'UNAUTHENTICATED') })
    renderLobby()

    expect(await screen.findByText('First, pick your dino')).toBeInTheDocument()
    expect(useAuthStore.getState().token).toBeNull()
  })
})
