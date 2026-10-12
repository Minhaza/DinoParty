import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'

import { createQueryClient } from './queryClient'
import { routes } from './routes'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('routes', () => {
  it('renders the home page with the system status widget', async () => {
    renderAt('/')

    expect(screen.getByRole('heading', { level: 1, name: 'DinoParty' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'System status' })).toBeInTheDocument()
    expect(await screen.findByText('Backend unreachable')).toBeInTheDocument()
  })

  it('labels unbuilt pages as coming in a later phase', () => {
    renderAt('/games')

    expect(screen.getByRole('heading', { name: 'Games' })).toBeInTheDocument()
    expect(screen.getByText('Coming in Phase 2')).toBeInTheDocument()
  })

  it('accepts an invite code in the join route', () => {
    renderAt('/join/ABC234')

    expect(screen.getByRole('heading', { name: 'Join room' })).toBeInTheDocument()
  })

  it('renders not-found for unknown paths', () => {
    renderAt('/nope')

    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})
