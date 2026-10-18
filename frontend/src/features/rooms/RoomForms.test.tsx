import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import { useAuthStore } from '@/stores/authStore'
import {
  DRAW_GUESS_DETAIL,
  GAME_DETAILS,
  gameHandlers,
  gameSummary,
  GUEST,
  mockApi,
  problem,
  renderApp,
  roomFixture,
  TELEPHONE_DETAIL,
} from '@/test/mockApi'

import { CreateRoomForm } from './CreateRoomForm'
import { JoinRoomForm } from './JoinRoomForm'

beforeEach(() => {
  localStorage.clear()
  useAuthStore.setState({ token: 'tok', lastRoomCode: null })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const guestMe = { 'GET /guests/me': { status: 200, body: GUEST } }

function renderForm(element: React.ReactElement) {
  return renderApp(
    [
      { path: '/', element },
      { path: '/rooms/:code', element: <p>Lobby page</p> },
    ],
    '/',
  )
}

describe('JoinRoomForm', () => {
  it('validates the code format before calling the server', async () => {
    const calls = mockApi(guestMe)
    renderForm(<JoinRoomForm />)
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('Room code'), 'ab0')
    await user.click(screen.getByRole('button', { name: /join room/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Room codes are 6 characters')
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
  })

  it('joins by code and opens the lobby', async () => {
    const calls = mockApi({
      ...guestMe,
      'POST /rooms/AB3D7K/join': { status: 200, body: roomFixture() },
    })
    renderForm(<JoinRoomForm />)
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('Room code'), 'ab3d7k')
    await user.click(screen.getByRole('button', { name: /join room/i }))

    expect(await screen.findByText('Lobby page')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.authorization).toBe('Bearer tok')
    expect(useAuthStore.getState().lastRoomCode).toBe('AB3D7K')
  })

  it.each([
    ['ROOM_NOT_FOUND', 404, 'No room with that code exists'],
    ['ROOM_FULL', 409, 'That room is full.'],
    ['ROOM_IN_PROGRESS', 409, 'already started playing'],
    ['ROOM_CLOSED', 409, 'has been closed'],
    ['RATE_LIMITED', 429, 'Too many attempts'],
  ])('explains %s', async (code, status, text) => {
    mockApi({ ...guestMe, 'POST /rooms/AB3D7K/join': problem(status, code) })
    renderForm(<JoinRoomForm initialCode="AB3D7K" />)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: /join room/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(text)
  })

  it('points to the current room when already in one', async () => {
    useAuthStore.setState({ lastRoomCode: 'ZZ9YY8' })
    mockApi({ ...guestMe, 'POST /rooms/AB3D7K/join': problem(409, 'ALREADY_IN_ROOM') })
    renderForm(<JoinRoomForm initialCode="AB3D7K" />)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: /join room/i }))

    expect(await screen.findByRole('link', { name: 'Go to room ZZ9YY8' })).toHaveAttribute(
      'href',
      '/rooms/ZZ9YY8',
    )
  })

  it('asks anonymous visitors to pick a profile first', async () => {
    useAuthStore.setState({ token: null })
    mockApi({})
    renderForm(<JoinRoomForm />)

    expect(await screen.findByText('First, pick your dino')).toBeInTheDocument()
    expect(screen.queryByLabelText('Room code')).not.toBeInTheDocument()
  })
})

describe('CreateRoomForm', () => {
  const games = { ...guestMe, ...gameHandlers() }

  it('offers the enabled games from the server, hiding disabled and unplayable ones', async () => {
    const disabled = {
      ...gameSummary(TELEPHONE_DETAIL),
      key: 'retired',
      name: 'Retired Game',
      enabled: false,
    }
    const unknown = {
      ...gameSummary(TELEPHONE_DETAIL),
      key: 'mystery',
      name: 'Mystery Game',
      iconKey: 'mystery',
    }
    mockApi({
      ...games,
      'GET /games': {
        status: 200,
        body: [gameSummary(DRAW_GUESS_DETAIL), disabled, unknown, gameSummary(TELEPHONE_DETAIL)],
      },
    })
    renderForm(<CreateRoomForm />)

    expect(await screen.findByRole('button', { name: /Draw & Guess/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.getByRole('button', { name: /Dino Telephone/ })).toHaveTextContent('4–8 players')
    expect(screen.queryByRole('button', { name: /Retired Game/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Mystery Game/ })).not.toBeInTheDocument()
  })

  it('creates a room with the server defaults plus the host edits and opens the lobby', async () => {
    const calls = mockApi({
      ...games,
      'POST /rooms': { status: 201, body: roomFixture({ gameKey: 'telephone' }) },
    })
    renderForm(<CreateRoomForm />)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: /dino telephone/i }))
    await user.selectOptions(await screen.findByLabelText('Max players'), '6')
    await user.selectOptions(screen.getByLabelText('Drawing time'), '120')
    await user.selectOptions(screen.getByLabelText('Entries per album'), '5')
    await user.click(screen.getByRole('button', { name: '+ Create room' }))

    expect(await screen.findByText('Lobby page')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      gameKey: 'telephone',
      maxPlayers: 6,
      settings: {
        promptSeconds: 60,
        drawSeconds: 120,
        describeSeconds: 45,
        entriesPerChain: 5,
        allowSkipTimeout: true,
      },
    })
    await waitFor(() => expect(useAuthStore.getState().lastRoomCode).toBe('AB3D7K'))
  })

  it('renders every scalar setting of the selected game and keeps Draw & Guess word settings for the lobby', async () => {
    const calls = mockApi({
      ...games,
      'POST /rooms': { status: 201, body: roomFixture() },
    })
    renderForm(<CreateRoomForm />)
    const user = userEvent.setup()

    expect(await screen.findByLabelText('Word choice time')).toHaveValue('15')
    expect(screen.getByRole('checkbox', { name: 'Letter hints' })).toBeChecked()
    expect(screen.queryByLabelText('Difficulty')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '+ Create room' }))

    await screen.findByText('Lobby page')
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      gameKey: 'draw-guess',
      maxPlayers: 8,
      settings: DRAW_GUESS_DETAIL.defaultSettings,
    })
  })

  it('resets the settings to the new game defaults and fetches each detail once', async () => {
    const calls = mockApi(games)
    renderForm(<CreateRoomForm />)
    const user = userEvent.setup()

    await user.selectOptions(await screen.findByLabelText('Drawing time'), '120')
    await user.click(screen.getByRole('button', { name: /dino telephone/i }))
    expect(await screen.findByLabelText('Prompt time')).toHaveValue('60')
    expect(screen.getByLabelText('Drawing time')).toHaveValue('90')
    await user.click(screen.getByRole('button', { name: /draw & guess/i }))

    expect(await screen.findByLabelText('Drawing time')).toHaveValue('80')
    expect(calls.filter((c) => c.path === '/games/draw-guess')).toHaveLength(1)
    expect(calls.filter((c) => c.path === '/games/telephone')).toHaveLength(1)
  })

  it('only offers player counts valid for the selected game', async () => {
    mockApi(games)
    renderForm(<CreateRoomForm initialGame="telephone" />)

    const options = Array.from(
      (await screen.findByLabelText('Max players')).querySelectorAll('option'),
    ).map((o) => o.value)

    expect(options).toEqual(['4', '5', '6', '7', '8'])
  })

  it('cannot submit before the game settings have loaded', async () => {
    mockApi({ ...games, 'GET /games/draw-guess': () => new Promise<never>(() => {}) })
    renderForm(<CreateRoomForm />)

    await screen.findByRole('button', { name: /Draw & Guess/ })
    expect(screen.getByRole('button', { name: '+ Create room' })).toBeDisabled()
  })

  it('shows a rejected setting against its own field', async () => {
    mockApi({
      ...games,
      'POST /rooms': problem(400, 'VALIDATION_FAILED', {
        fieldErrors: [
          { field: 'settings.drawSeconds', message: 'drawSeconds must be between 30 and 180' },
          { field: 'settings.wordCategories', message: 'unknown category key' },
        ],
      }),
    })
    renderForm(<CreateRoomForm />)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '+ Create room' }))

    expect(await screen.findByLabelText('Drawing time')).toHaveAccessibleDescription(
      'drawSeconds must be between 30 and 180',
    )
    // A field the form does not show still reaches the host.
    expect(screen.getByRole('alert')).toHaveTextContent('unknown category key')
  })

  it('shows an error when the guest is already in a room', async () => {
    mockApi({ ...games, 'POST /rooms': problem(409, 'ALREADY_IN_ROOM') })
    renderForm(<CreateRoomForm />)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '+ Create room' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('You are already in a room')
  })

  it('lets the host retry when the game list cannot be loaded', async () => {
    let fail = true
    mockApi({
      ...games,
      'GET /games': () =>
        fail ? problem(500, 'SERVER_ERROR') : { status: 200, body: GAME_DETAILS.map(gameSummary) },
    })
    renderForm(<CreateRoomForm />)
    const user = userEvent.setup()

    expect(await screen.findByRole('alert')).toHaveTextContent('The server had a problem')
    fail = false
    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByRole('button', { name: /Draw & Guess/ })).toBeInTheDocument()
  })
})
