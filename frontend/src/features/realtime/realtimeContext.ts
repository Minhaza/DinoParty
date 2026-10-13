import * as React from 'react'

import { useAuthStore } from '@/stores/authStore'

import { RealtimeClient } from './realtimeClient'

export const RealtimeContext = React.createContext<RealtimeClient | null>(null)

let sharedClient: RealtimeClient | null = null

export function getSharedRealtimeClient(): RealtimeClient {
  if (!sharedClient) {
    const token = useAuthStore.getState().token
    sharedClient = new RealtimeClient({ token })
  }
  return sharedClient
}

export function setSharedRealtimeClient(client: RealtimeClient | null): void {
  sharedClient = client
}

export function useRealtimeClient(): RealtimeClient {
  const client = React.useContext(RealtimeContext)
  if (!client) {
    return getSharedRealtimeClient()
  }
  return client
}
