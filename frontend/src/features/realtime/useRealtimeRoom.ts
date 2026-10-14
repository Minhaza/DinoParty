import * as React from 'react'

import { getRoomSnapshot, setRoomReady, startRoom } from '@/api/rooms'
import type { RoomDto } from '@/api/types'
import { useRoomStore } from '@/stores/roomStore'

import { useConnectionStore } from './connectionStore'
import { useRealtimeClient } from './realtimeContext'

export function useRealtimeRoom(
  code: string,
  isMember: boolean,
  guestId: string,
  initialRoom?: RoomDto,
) {
  const client = useRealtimeClient()
  const room = useRoomStore((s) => s.room)
  const syncStatus = useRoomStore((s) => s.syncStatus)
  const needsResync = useRoomStore((s) => s.needsResync)
  const lastError = useRoomStore((s) => s.lastError)
  const terminalNotice = useRoomStore((s) => s.terminalNotice)
  const applySnapshot = useRoomStore((s) => s.applySnapshot)
  const setSyncStatus = useRoomStore((s) => s.setSyncStatus)
  const clearLastError = useRoomStore((s) => s.clearLastError)

  const connectionStatus = useConnectionStore((s) => s.status)
  const terminalReason = useConnectionStore((s) => s.terminalReason)
  const reconnectAttempt = useConnectionStore((s) => s.reconnectAttempt)
  const isOnline = useConnectionStore((s) => s.isOnline)

  const [isTogglingReady, setIsTogglingReady] = React.useState(false)
  const [isStartingGame, setIsStartingGame] = React.useState(false)
  const [startError, setStartError] = React.useState<string | null>(null)

  // Initialize store with initialRoom if not yet set
  React.useEffect(() => {
    if (initialRoom && (!room || room.code !== initialRoom.code)) {
      useRoomStore.setState({
        room: initialRoom,
      })
    }
  }, [initialRoom, room])

  // Subscribe to STOMP room streams
  React.useEffect(() => {
    if (!isMember || !code) return

    setSyncStatus('syncing')
    client.subscribeToRoom(code)

    return () => {
      client.unsubscribeFromRoom()
    }
  }, [code, isMember, client, setSyncStatus])

  // Handle sequence gap resync
  React.useEffect(() => {
    if (!needsResync || !isMember || !code) return

    let isMounted = true
    getRoomSnapshot(code)
      .then((snapshot) => {
        if (!isMounted) return
        applySnapshot(snapshot)
      })
      .catch(() => {
        if (!isMounted) return
        client.requestSnapshot()
      })

    return () => {
      isMounted = false
    }
  }, [needsResync, code, isMember, client, applySnapshot])

  // Current player from room
  const currentRoom = room ?? initialRoom ?? null
  const currentPlayer = currentRoom?.players.find((p) => p.guestId === guestId)
  const isHost = currentRoom ? currentRoom.hostGuestId === guestId : false
  const isReady = currentPlayer?.ready ?? false

  // Toggle ready action
  const toggleReady = React.useCallback(async () => {
    if (!currentRoom || isTogglingReady) return

    setIsTogglingReady(true)
    const targetReady = !isReady

    try {
      if (connectionStatus === 'CONNECTED') {
        client.setReady(targetReady)
      } else {
        // Fallback to REST if offline / reconnecting
        const updated = await setRoomReady(currentRoom.code, targetReady)
        useRoomStore.setState((s) => ({
          room: s.room ? { ...s.room, players: updated.players } : updated,
        }))
      }
    } catch (err) {
      console.error('Failed to toggle ready', err)
    } finally {
      setIsTogglingReady(false)
    }
  }, [currentRoom, isTogglingReady, isReady, connectionStatus, client])

  // Start game action (host only)
  const handleStartGame = React.useCallback(async (): Promise<RoomDto | null> => {
    if (!currentRoom || !isHost || isStartingGame) return null

    setIsStartingGame(true)
    setStartError(null)

    try {
      const updated = await startRoom(currentRoom.code)
      useRoomStore.setState((s) => ({
        room: s.room ? { ...s.room, status: updated.status } : updated,
      }))
      return updated
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to start game'
      setStartError(msg)
      return null
    } finally {
      setIsStartingGame(false)
    }
  }, [currentRoom, isHost, isStartingGame])

  // Rematch vote action
  const voteRematch = React.useCallback(
    async (vote: boolean) => {
      if (!currentRoom) return
      client.voteRematch(vote)
    },
    [currentRoom, client],
  )

  return {
    room: currentRoom,
    currentPlayer,
    isHost,
    isReady,
    isTogglingReady,
    isStartingGame,
    startError,
    syncStatus,
    connectionStatus,
    terminalReason: terminalReason ?? terminalNotice,
    reconnectAttempt,
    isOnline,
    lastError,
    clearLastError,
    toggleReady,
    startGame: handleStartGame,
    voteRematch,
  }
}
