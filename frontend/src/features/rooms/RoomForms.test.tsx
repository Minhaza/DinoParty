import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import { useAuthStore } from '@/stores/authStore'
import { GUEST, mockApi, problem, renderApp, roomFixture } from '@/test/mockApi'

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
  it('creates a room with the chosen game settings and opens the lobby', async () => {
    const calls = mockApi({
      ...guestMe,
      'POST /rooms': { status: 201, body: roomFixture({ gameKey: 'telephone' }) },
    })
    renderForm(<CreateRoomForm />)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: /dino telephone/i }))
    await user.selectOptions(screen.getByLabelText('Max players'), '6')
    await user.selectOptions(screen.getByLabelText('Drawing time'), '120')
    await user.click(screen.getByRole('button', { name: '+ Create room' }))

    expect(await screen.findByText('Lobby page')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      gameKey: 'telephone',
      maxPlayers: 6,
      settings: { promptSeconds: 60, drawSeconds: 120, describeSeconds: 45 },
    })
    await waitFor(() => expect(useAuthStore.getState().lastRoomCode).toBe('AB3D7K'))
  })

  it('shows an error when the guest is already in a room', async () => {
    mockApi({ ...guestMe, 'POST /rooms': problem(409, 'ALREADY_IN_ROOM') })
    renderForm(<CreateRoomForm />)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: '+ Create room' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('You are already in a room')
  })

  it('only offers player counts valid for the selected game', async () => {
    mockApi(guestMe)
    renderForm(<CreateRoomForm initialGame="telephone" />)

    const options = Array.from(
      (await screen.findByLabelText('Max players')).querySelectorAll('option'),
    ).map((o) => o.value)

    expect(options).toEqual(['4', '5', '6', '7', '8'])
  })
})
