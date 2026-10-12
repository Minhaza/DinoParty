import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'

import { SystemStatus } from './SystemStatus'

function renderWidget() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <SystemStatus />
    </QueryClientProvider>,
  )
}

function mockFetch(status: number, body: unknown) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status })))
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('SystemStatus', () => {
  it('shows UP with database and version when the backend is healthy', async () => {
    mockFetch(200, { status: 'UP', version: '0.0.1', db: 'UP', time: '2026-01-01T00:00:00Z' })
    renderWidget()

    expect(await screen.findByText('Backend UP')).toBeInTheDocument()
    expect(screen.getByText(/Database UP · version 0\.0\.1/)).toBeInTheDocument()
  })

  it('shows DOWN when the backend reports its database is down', async () => {
    mockFetch(503, { status: 'DOWN', version: '0.0.1', db: 'DOWN', time: '2026-01-01T00:00:00Z' })
    renderWidget()

    expect(await screen.findByText('Backend DOWN')).toBeInTheDocument()
  })

  it('shows unreachable when the request fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network')))
    renderWidget()

    expect(await screen.findByText('Backend unreachable')).toBeInTheDocument()
  })
})
