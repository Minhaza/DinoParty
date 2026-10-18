import * as React from 'react'

import { useRealtimeClient } from '@/features/realtime/realtimeContext'
import { useGameSessionStore } from '@/stores/gameSessionStore'
import { useDrawGuessStore } from './drawGuessStore'

/**
 * Sends a game command for the current phase. Returns `false` instead of throwing while the
 * socket is down: the reconnect snapshot restores the authoritative state afterwards.
 */
export function useDrawGuessCommands() {
  const client = useRealtimeClient()
  const phaseId = useGameSessionStore((s) => s.phaseId)
  const trackCommand = useDrawGuessStore((s) => s.trackCommand)

  return React.useCallback(
    (type: string, payload: object, strokeId?: string): boolean => {
      if (!phaseId) return false
      try {
        const actionId = client.sendCommand({ type, phaseId, payload })
        trackCommand(actionId, type, strokeId)
        return true
      } catch {
        return false
      }
    },
    [client, phaseId, trackCommand],
  )
}
