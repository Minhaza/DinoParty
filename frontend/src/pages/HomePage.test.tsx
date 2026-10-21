import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import { useAuthStore } from '@/stores/authStore'
import {
  DRAW_GUESS_DETAIL,
  gameHandlers,
  gameSummary,
  GUEST,
  mockApi,
  renderAt,
  TELEPHONE_DETAIL,
} from '@/test/mockApi'

import { HomePage } from './HomePage'

beforeEach(() => {
  localStorage.clear()
  useAuthStore.setState({ token: 'tok', lastRoomCode: null })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const base = {
  'GET /guests/me': { status: 200, body: GUEST },
  'GET /health': {
    status: 200,
    body: { status: 'UP', version: 'test', db: 'UP', time: '2026-01-01T00:00:00Z' },
  },
}

describe('HomePage', () => {
  it('features the available games from the server', async () => {
    mockApi({ ...base, ...gameHandlers() })
    renderAt(<HomePage />)

    expect(await screen.findByRole('heading', { name: 'Draw & Guess' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Dino Telephone' })).toBeInTheDocument()
    expect(screen.getByText('Zero drawing skill needed')).toBeInTheDocument()
    // Overall player range across the enabled games, from their server bounds.
    expect(screen.getByText('2 - 10 players')).toBeInTheDocument()
  })

  it('leaves disabled games out of the featured list and the player range', async () => {
    mockApi({
      ...base,
      'GET /games': {
        status: 200,
        body: [
          { ...gameSummary(DRAW_GUESS_DETAIL), enabled: false },
          gameSummary(TELEPHONE_DETAIL),
        ],
      },
    })
    renderAt(<HomePage />)

    expect(await screen.findByRole('heading', { name: 'Dino Telephone' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Draw & Guess' })).not.toBeInTheDocument()
    expect(screen.getByText('4 - 8 players')).toBeInTheDocument()
  })

  it('starts room creation for a featured game', async () => {
    mockApi({ ...base, ...gameHandlers() })
    renderAt(<HomePage />)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Start Dino Telephone' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('button', { name: /Dino Telephone/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })
})
