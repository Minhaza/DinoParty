import { ApiError } from './client'
import { getHealth } from './health'

const UP = { status: 'UP', version: '1.0.0', db: 'UP', time: '2026-01-01T00:00:00.000Z' }

function mockFetch(status: number, body: unknown) {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(typeof body === 'string' ? body : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('getHealth', () => {
  it('calls the versioned health endpoint and returns the body', async () => {
    const fetchMock = mockFetch(200, UP)

    await expect(getHealth()).resolves.toEqual(UP)
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/health', expect.anything())
  })

  it('treats a 503 health body as a DOWN reading, not an error', async () => {
    const down = { ...UP, status: 'DOWN', db: 'DOWN' }
    mockFetch(503, down)

    await expect(getHealth()).resolves.toEqual(down)
  })

  it('throws ApiError for unexpected responses', async () => {
    mockFetch(401, { status: 401, code: 'UNAUTHENTICATED', title: 'Authentication required' })

    await expect(getHealth()).rejects.toBeInstanceOf(ApiError)
  })

  it('throws ApiError when a 200 body has the wrong shape', async () => {
    mockFetch(200, { hello: 'world' })

    await expect(getHealth()).rejects.toBeInstanceOf(ApiError)
  })
})
