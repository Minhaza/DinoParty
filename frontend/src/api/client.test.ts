import { useAuthStore } from '@/stores/authStore'
import { mockApi, problem } from '@/test/mockApi'

import { apiRequest, ApiError } from './client'

beforeEach(() => {
  localStorage.clear()
  useAuthStore.setState({ token: null, lastRoomCode: null })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('api client auth', () => {
  it('sends the stored guest token as a bearer header', async () => {
    useAuthStore.setState({ token: 'secret-token' })
    const calls = mockApi({ 'GET /guests/me': { status: 200, body: {} } })

    await apiRequest('/guests/me')

    expect(calls[0].authorization).toBe('Bearer secret-token')
  })

  it('sends no Authorization header without a token', async () => {
    const calls = mockApi({ 'GET /health': { status: 200, body: {} } })

    await apiRequest('/health')

    expect(calls[0].authorization).toBeNull()
  })

  it('forgets a rejected token on 401 so the UI can offer a new profile', async () => {
    useAuthStore.setState({ token: 'expired', lastRoomCode: 'AB3D7K' })
    mockApi({ 'GET /guests/me': problem(401, 'UNAUTHENTICATED') })

    await expect(apiRequest('/guests/me')).rejects.toBeInstanceOf(ApiError)

    expect(useAuthStore.getState().token).toBeNull()
    expect(useAuthStore.getState().lastRoomCode).toBeNull()
  })

  it('exposes the problem code on ApiError', async () => {
    mockApi({ 'POST /rooms/ZZZZZZ/join': problem(404, 'ROOM_NOT_FOUND') })

    await expect(apiRequest('/rooms/ZZZZZZ/join', { method: 'POST' })).rejects.toMatchObject({
      status: 404,
      code: 'ROOM_NOT_FOUND',
    })
  })
})
