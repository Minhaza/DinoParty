import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'

import {
  DRAW_GUESS_DETAIL,
  gameHandlers,
  gameSummary,
  mockApi,
  TELEPHONE_DETAIL,
} from '@/test/mockApi'

import { useEnabledGames, useGameDetail, useGameName, useGames } from './useGames'

afterEach(() => {
  vi.unstubAllGlobals()
})

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

describe('game discovery hooks', () => {
  it('shares one cached list between every consumer', async () => {
    const calls = mockApi(gameHandlers())
    const Wrapper = wrapper()

    const { result } = renderHook(
      () => ({ all: useGames(), enabled: useEnabledGames(), name: useGameName('telephone') }),
      { wrapper: Wrapper },
    )

    await waitFor(() => expect(result.current.all.isSuccess).toBe(true))
    expect(result.current.name).toBe('Dino Telephone')
    expect(calls.filter((c) => c.path === '/games')).toHaveLength(1)
  })

  it('treats disabled games as unavailable', async () => {
    const off = { ...gameSummary(TELEPHONE_DETAIL), enabled: false }
    mockApi({ 'GET /games': { status: 200, body: [gameSummary(DRAW_GUESS_DETAIL), off] } })

    const { result } = renderHook(() => useEnabledGames(), { wrapper: wrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.map((g) => g.key)).toEqual(['draw-guess'])
  })

  it('falls back to a neutral name while loading or for unknown keys', async () => {
    mockApi(gameHandlers())

    const { result } = renderHook(() => useGameName('mystery'), { wrapper: wrapper() })

    expect(result.current).toBe('Party game')
    await waitFor(() => expect(result.current).toBe('Party game'))
  })

  it('fetches a detail only for a selected key, once', async () => {
    const calls = mockApi(gameHandlers())
    const Wrapper = wrapper()

    const { result, rerender } = renderHook(({ key }) => useGameDetail(key), {
      wrapper: Wrapper,
      initialProps: { key: null as string | null },
    })
    expect(calls).toHaveLength(0)

    rerender({ key: 'telephone' })
    await waitFor(() => expect(result.current.data?.key).toBe('telephone'))
    rerender({ key: 'draw-guess' })
    await waitFor(() => expect(result.current.data?.key).toBe('draw-guess'))
    rerender({ key: 'telephone' })

    expect(result.current.data?.key).toBe('telephone')
    expect(calls.map((c) => c.path)).toEqual(['/games/telephone', '/games/draw-guess'])
  })
})
