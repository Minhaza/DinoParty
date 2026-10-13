import { useQuery } from '@tanstack/react-query'

import { getMe, guestQueryKey } from '@/api/guests'
import type { GuestDto } from '@/api/types'
import { useAuthStore } from '@/stores/authStore'

export type GuestState =
  | { status: 'anonymous'; guest: null; retry: () => void }
  | { status: 'loading'; guest: null; retry: () => void }
  | { status: 'error'; guest: null; retry: () => void }
  | { status: 'ready'; guest: GuestDto; retry: () => void }

/**
 * Restores the guest identity from the persisted token (`GET /guests/me`). The query is the single
 * source of truth for the profile; the store only keeps the token.
 */
export function useGuest(): GuestState {
  const token = useAuthStore((s) => s.token)
  const query = useQuery({
    queryKey: guestQueryKey,
    queryFn: ({ signal }) => getMe(signal),
    enabled: token !== null,
    retry: false,
    staleTime: 5 * 60_000,
  })
  const retry = () => void query.refetch()

  if (token === null) return { status: 'anonymous', guest: null, retry }
  if (query.data) return { status: 'ready', guest: query.data, retry }
  if (query.isError) return { status: 'error', guest: null, retry }
  return { status: 'loading', guest: null, retry }
}
