import { useQuery, type QueryClient } from '@tanstack/react-query'

import { gameQueryKeys, getGame, listGames } from '@/api/games'
import type { GameSummaryDto } from '@/api/types'

/** The registry only changes with a deployment, so a fetched game list stays fresh for a while. */
const GAMES_STALE_MS = 5 * 60_000

const enabledOnly = (games: GameSummaryDto[]) => games.filter((g) => g.enabled)

/**
 * Game discovery (`GET /games`). The TanStack Query cache is the single copy of the list; every
 * screen reads it through these hooks instead of keeping its own.
 */
export function useGames() {
  return useQuery({
    queryKey: gameQueryKeys.all,
    queryFn: ({ signal }) => listGames(signal),
    staleTime: GAMES_STALE_MS,
  })
}

/** Games a room can be created for; disabled ones are never offered. */
export function useEnabledGames() {
  return useQuery({
    queryKey: gameQueryKeys.all,
    queryFn: ({ signal }) => listGames(signal),
    staleTime: GAMES_STALE_MS,
    select: enabledOnly,
  })
}

/** `GET /games/{key}` for the selected game; nothing is fetched while `key` is empty. */
export function useGameDetail(key: string | null | undefined) {
  return useQuery({
    queryKey: gameQueryKeys.detail(key ?? ''),
    queryFn: ({ signal }) => getGame(key ?? '', signal),
    enabled: Boolean(key),
    staleTime: GAMES_STALE_MS,
  })
}

export function prefetchGameDetail(queryClient: QueryClient, key: string) {
  return queryClient.prefetchQuery({
    queryKey: gameQueryKeys.detail(key),
    queryFn: ({ signal }) => getGame(key, signal),
    staleTime: GAMES_STALE_MS,
  })
}

/** Display name of a game from the cached list, or a neutral label until it is known. */
export function useGameName(key: string | null | undefined): string {
  const { data } = useGames()
  return data?.find((g) => g.key === key)?.name ?? 'Party game'
}
