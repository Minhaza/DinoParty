import * as React from 'react'

import { useAuthStore } from '@/stores/authStore'

import { useConnectionStore } from './connectionStore'
import { RealtimeClient } from './realtimeClient'
import { getSharedRealtimeClient, RealtimeContext } from './realtimeContext'

export interface RealtimeProviderProps {
  children: React.ReactNode
  client?: RealtimeClient
}

export function RealtimeProvider({ children, client }: RealtimeProviderProps) {
  const activeClient = React.useMemo(() => client ?? getSharedRealtimeClient(), [client])
  const token = useAuthStore((s) => s.token)
  const setIsOnline = useConnectionStore((s) => s.setIsOnline)

  React.useEffect(() => {
    activeClient.setToken(token)
    if (token) {
      activeClient.connect()
    } else {
      activeClient.disconnect()
    }
  }, [activeClient, token])

  React.useEffect(() => {
    const handleOnline = () => {
      setIsOnline(true)
      if (useAuthStore.getState().token) {
        activeClient.connect()
      }
    }

    const handleOffline = () => {
      setIsOnline(false)
    }

    window.addEventListener('online', handleOnline)
    window.addEventListener('offline', handleOffline)

    return () => {
      window.removeEventListener('online', handleOnline)
      window.removeEventListener('offline', handleOffline)
    }
  }, [activeClient, setIsOnline])

  return (
    <RealtimeContext.Provider value={activeClient}>
      {children}
    </RealtimeContext.Provider>
  )
}
