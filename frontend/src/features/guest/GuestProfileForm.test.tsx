import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import { useAuthStore } from '@/stores/authStore'
import { GUEST, mockApi, problem, renderAt } from '@/test/mockApi'

import { GuestProfileForm } from './GuestProfileForm'

beforeEach(() => {
  localStorage.clear()
  useAuthStore.setState({ token: null, lastRoomCode: null })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('GuestProfileForm', () => {
  it('creates a guest, stores only the token and reports the profile', async () => {
    const calls = mockApi({
      'POST /guests': {
        status: 201,
        body: { guest: GUEST, token: 'tok-123', expiresAt: '2026-02-01T00:00:00.000Z' },
      },
    })
    const onDone = vi.fn()
    renderAt(<GuestProfileForm onDone={onDone} />)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('Party nickname'), '  Rexy ')
    await user.click(screen.getByRole('button', { name: 'Select Tricera (Triceratops)' }))
    await user.click(screen.getByRole('button', { name: 'Create profile' }))

    await waitFor(() => expect(onDone).toHaveBeenCalledWith(GUEST))
    expect(calls[0]).toMatchObject({
      method: 'POST',
      path: '/guests',
      body: { nickname: 'Rexy', avatarKey: 'dino-tricera' },
    })
    expect(useAuthStore.getState().token).toBe('tok-123')
  })

  it('rejects too-short nicknames locally without calling the server', async () => {
    const calls = mockApi({})
    renderAt(<GuestProfileForm />)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('Party nickname'), 'a')
    await user.click(screen.getByRole('button', { name: 'Create profile' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Nickname must be 2–20 characters.')
    expect(calls).toHaveLength(0)
  })

  it('shows the server message for an invalid nickname', async () => {
    mockApi({
      'POST /guests': problem(400, 'VALIDATION_FAILED', {
        fieldErrors: [{ field: 'nickname', message: 'may only contain letters and digits' }],
      }),
    })
    renderAt(<GuestProfileForm />)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('Party nickname'), 'bad<>')
    await user.click(screen.getByRole('button', { name: 'Create profile' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'may only contain letters and digits',
    )
    expect(useAuthStore.getState().token).toBeNull()
  })

  it('updates an existing guest with PATCH and keeps the token', async () => {
    useAuthStore.setState({ token: 'tok-123' })
    const calls = mockApi({
      'PATCH /guests/me': { status: 200, body: { ...GUEST, nickname: 'Renamed' } },
    })
    renderAt(<GuestProfileForm guest={GUEST} />)
    const user = userEvent.setup()

    const input = screen.getByLabelText('Party nickname')
    await user.clear(input)
    await user.type(input, 'Renamed')
    await user.click(screen.getByRole('button', { name: 'Save profile' }))

    expect(await screen.findByText('Profile saved.')).toBeInTheDocument()
    expect(calls[0]).toMatchObject({ method: 'PATCH', path: '/guests/me' })
    expect(calls[0].authorization).toBe('Bearer tok-123')
  })
})
