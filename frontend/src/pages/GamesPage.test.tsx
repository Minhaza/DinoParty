import { screen, within } from '@testing-library/react'
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
  renderAt,
  TELEPHONE_DETAIL,
} from '@/test/mockApi'

import { GamesPage } from './GamesPage'

beforeEach(() => {
  localStorage.clear()
  useAuthStore.setState({ token: 'tok', lastRoomCode: null })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const guestMe = { 'GET /guests/me': { status: 200, body: GUEST } }

describe('GamesPage', () => {
  it('lists the games with server names, taglines and player limits', async () => {
    mockApi({ ...guestMe, ...gameHandlers() })
    renderAt(<GamesPage />)

    const draw = await screen.findByRole('article', { name: 'Draw & Guess' })
    expect(draw).toHaveTextContent('Easy to learn • High energy')
    expect(draw).toHaveTextContent('2 - 10 players')
    expect(
      await within(draw).findByText(/Take turns drawing a word while others guess/),
    ).toBeInTheDocument()
    const phone = screen.getByRole('article', { name: 'Dino Telephone' })
    expect(phone).toHaveTextContent('4 - 8 players')
  })

  it('hides disabled games and marks games this client cannot play', async () => {
    mockApi({
      ...guestMe,
      'GET /games': {
        status: 200,
        body: [
          gameSummary(DRAW_GUESS_DETAIL),
          { ...gameSummary(TELEPHONE_DETAIL), enabled: false },
          {
            key: 'mystery',
            name: 'Mystery Game',
            tagline: 'Who knows',
            minPlayers: 3,
            maxPlayers: 6,
            iconKey: 'mystery',
            enabled: true,
          },
        ],
      },
      'GET /games/draw-guess': { status: 200, body: DRAW_GUESS_DETAIL },
      'GET /games/mystery': {
        status: 200,
        body: {
          ...DRAW_GUESS_DETAIL,
          key: 'mystery',
          name: 'Mystery Game',
          iconKey: 'mystery',
          description: 'A secret game.',
        },
      },
    })
    renderAt(<GamesPage />)

    const mystery = await screen.findByRole('article', { name: 'Mystery Game' })
    expect(mystery).toHaveTextContent('3 - 6 players')
    expect(within(mystery).getByText('Not available in this version')).toBeInTheDocument()
    expect(within(mystery).queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByRole('article', { name: 'Dino Telephone' })).not.toBeInTheDocument()
  })

  it('opens room creation with the chosen game selected', async () => {
    mockApi({ ...guestMe, ...gameHandlers() })
    renderAt(<GamesPage />)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Play Dino Telephone' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('button', { name: /Dino Telephone/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })

  it('offers a retry when the games cannot be loaded', async () => {
    let fail = true
    mockApi({
      ...guestMe,
      'GET /games': () =>
        fail ? problem(503, 'SERVER_ERROR') : { status: 200, body: GAME_DETAILS.map(gameSummary) },
    })
    renderAt(<GamesPage />)
    const user = userEvent.setup()

    expect(await screen.findByRole('alert')).toHaveTextContent('The server had a problem')
    fail = false
    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByRole('article', { name: 'Draw & Guess' })).toBeInTheDocument()
  })
})
