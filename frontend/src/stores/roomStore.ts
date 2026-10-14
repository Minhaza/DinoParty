import { create } from 'zustand'

import type {
  EventEnvelope,
  GameAbortedPayload,
  GameErrorPayload,
  GameFinishedPayload,
  GameStartedPayload,
  HostChangedPayload,
  PhaseStartedPayload,
  PlayerDisconnectedPayload,
  PlayerJoinedPayload,
  PlayerLeftPayload,
  PlayerReconnectedPayload,
  ReadyUpdatedPayload,
  RematchVotesUpdatedPayload,
  RoomDto,
  RoomSettingsUpdatedPayload,
  RoomStatusChangedPayload,
  SnapshotDto,
  SnapshotSeq,
} from '@/api/types'
import { updateServerClockOffset } from '@/lib/serverClock'

import { useGameSessionStore } from './gameSessionStore'

export type SyncStatus = 'idle' | 'syncing' | 'synced' | 'error'

export interface RoomState {
  room: RoomDto | null
  serverNow: string | null
  lastSeq: SnapshotSeq
  syncStatus: SyncStatus
  needsResync: boolean
  lastError: string | null
  terminalNotice: string | null

  applySnapshot: (snapshot: SnapshotDto) => void
  applyEnvelope: (envelope: EventEnvelope<unknown>) => 'applied' | 'ignored' | 'gap'
  setSyncStatus: (status: SyncStatus) => void
  setNeedsResync: (needsResync: boolean) => void
  clearLastError: () => void
  reset: () => void
}

const initialSeq: SnapshotSeq = { room: 0, private: 0 }

export const useRoomStore = create<RoomState>((set, get) => ({
  room: null,
  serverNow: null,
  lastSeq: { ...initialSeq },
  syncStatus: 'idle',
  needsResync: false,
  lastError: null,
  terminalNotice: null,

  applySnapshot: (snapshot: SnapshotDto) => {
    updateServerClockOffset(snapshot.serverNow)
    useGameSessionStore.getState().applySnapshot(snapshot.game, snapshot.room)

    set({
      room: snapshot.room,
      serverNow: snapshot.serverNow,
      lastSeq: { room: snapshot.seq.room, private: snapshot.seq.private },
      syncStatus: 'synced',
      needsResync: false,
    })
  },

  applyEnvelope: (envelope: EventEnvelope<unknown>) => {
    const state = get()

    if (envelope.ts) {
      updateServerClockOffset(envelope.ts)
    }

    // Handle errors arriving on /user/queue/errors (seq = 0, unsequenced)
    if (envelope.type === 'ERROR' || envelope.seq === 0) {
      const payload = envelope.payload as GameErrorPayload | undefined
      if (payload && typeof payload.message === 'string') {
        set({ lastError: payload.message })
      }
      return 'applied'
    }

    // A snapshot replaces the remembered seq wholesale (contract §3.2)
    if (envelope.type === 'ROOM_SNAPSHOT') {
      state.applySnapshot(envelope.payload as SnapshotDto)
      return 'applied'
    }

    const streamKey = envelope.stream === 'ROOM' ? 'room' : 'private'
    const currentSeq = state.lastSeq[streamKey]

    // Deduplication check: ignore events at or before current sequence
    if (envelope.seq <= currentSeq) {
      return 'ignored'
    }

    // Gap detection: if there is a sequence gap, trigger resynchronization
    if (envelope.seq > currentSeq + 1) {
      set({ needsResync: true })
      return 'gap'
    }

    // Next event in sequence: advance sequence counter
    const nextSeq: SnapshotSeq = {
      ...state.lastSeq,
      [streamKey]: envelope.seq,
    }

    // Forward game lifecycle events to gameSessionStore
    dispatchGameLifecycleEvents(envelope)

    // Dispatch event to update room state
    const currentRoom = state.room
    if (!currentRoom) {
      // Ignore until a snapshot arrives
      set({ lastSeq: nextSeq })
      return 'applied'
    }

    const updatedRoom = reduceRoomEvent(currentRoom, envelope)

    let terminalNotice = state.terminalNotice
    let lastError = state.lastError

    if (envelope.type === 'SESSION_REPLACED') {
      terminalNotice = 'SESSION_REPLACED'
    } else if (envelope.type === 'GAME_ERROR') {
      const err = envelope.payload as GameErrorPayload
      lastError = err.message || err.code
    }

    set({
      room: updatedRoom,
      lastSeq: nextSeq,
      serverNow: envelope.ts || state.serverNow,
      terminalNotice,
      lastError,
    })

    return 'applied'
  },

  setSyncStatus: (syncStatus) => set({ syncStatus }),
  setNeedsResync: (needsResync) => set({ needsResync }),
  clearLastError: () => set({ lastError: null }),

  reset: () => {
    useGameSessionStore.getState().reset()
    set({
      room: null,
      serverNow: null,
      lastSeq: { ...initialSeq },
      syncStatus: 'idle',
      needsResync: false,
      lastError: null,
      terminalNotice: null,
    })
  },
}))

function dispatchGameLifecycleEvents(envelope: EventEnvelope<unknown>): void {
  const sessionStore = useGameSessionStore.getState()
  switch (envelope.type) {
    case 'GAME_STARTED':
      sessionStore.applyGameStarted(envelope.payload as GameStartedPayload)
      break
    case 'PHASE_STARTED':
      sessionStore.applyPhaseStarted(envelope.payload as PhaseStartedPayload, envelope.ts)
      break
    case 'GAME_FINISHED':
      sessionStore.applyGameFinished(envelope.payload as GameFinishedPayload)
      break
    case 'GAME_ABORTED':
      sessionStore.applyGameAborted(envelope.payload as GameAbortedPayload)
      break
    case 'REMATCH_VOTES_UPDATED':
      sessionStore.applyRematchVotesUpdated(envelope.payload as RematchVotesUpdatedPayload)
      break
    case 'ROOM_STATUS_CHANGED':
      sessionStore.applyRoomStatusChanged(envelope.payload as RoomStatusChangedPayload)
      break
    case 'PLAYER_LEFT': {
      const { guestId } = envelope.payload as PlayerLeftPayload
      sessionStore.applyPlayerStatusChanged(guestId, 'LEFT')
      break
    }
    case 'PLAYER_DISCONNECTED': {
      const { guestId } = envelope.payload as PlayerDisconnectedPayload
      sessionStore.applyPlayerStatusChanged(guestId, 'DISCONNECTED')
      break
    }
    case 'PLAYER_RECONNECTED': {
      const { guestId } = envelope.payload as PlayerReconnectedPayload
      sessionStore.applyPlayerStatusChanged(guestId, 'ACTIVE')
      break
    }
    default:
      break
  }
}

function reduceRoomEvent(room: RoomDto, envelope: EventEnvelope<unknown>): RoomDto {
  switch (envelope.type) {
    case 'PLAYER_JOINED': {
      const { player } = envelope.payload as PlayerJoinedPayload
      const exists = room.players.some((p) => p.guestId === player.guestId)
      const players = exists
        ? room.players.map((p) => (p.guestId === player.guestId ? player : p))
        : [...room.players, player]
      return { ...room, players }
    }

    case 'PLAYER_LEFT': {
      const { guestId } = envelope.payload as PlayerLeftPayload
      const players = room.players.filter((p) => p.guestId !== guestId)
      return { ...room, players }
    }

    case 'READY_UPDATED': {
      const { guestId, ready } = envelope.payload as ReadyUpdatedPayload
      const players = room.players.map((p) => (p.guestId === guestId ? { ...p, ready } : p))
      return { ...room, players }
    }

    case 'HOST_CHANGED': {
      const { hostGuestId } = envelope.payload as HostChangedPayload
      const players = room.players.map((p) => ({
        ...p,
        isHost: p.guestId === hostGuestId,
      }))
      return { ...room, hostGuestId, players }
    }

    case 'ROOM_SETTINGS_UPDATED': {
      const { gameKey, settings, minPlayers, maxPlayers } =
        envelope.payload as RoomSettingsUpdatedPayload
      return {
        ...room,
        gameKey,
        settings,
        minPlayers,
        maxPlayers,
      }
    }

    case 'PLAYER_DISCONNECTED': {
      const { guestId } = envelope.payload as PlayerDisconnectedPayload
      const players = room.players.map((p) =>
        p.guestId === guestId ? { ...p, connection: 'DISCONNECTED' as const } : p,
      )
      return { ...room, players }
    }

    case 'PLAYER_RECONNECTED': {
      const { guestId } = envelope.payload as PlayerReconnectedPayload
      const players = room.players.map((p) =>
        p.guestId === guestId ? { ...p, connection: 'CONNECTED' as const } : p,
      )
      return { ...room, players }
    }

    case 'ROOM_CLOSED': {
      return { ...room, status: 'CLOSED' }
    }

    case 'ROOM_STATUS_CHANGED': {
      const { status, reason } = envelope.payload as RoomStatusChangedPayload
      const resetReasons = ['REMATCH', 'RESULTS_TIMEOUT', 'GAME_ABORTED', 'SERVER_RESTART', 'START_CANCELLED']
      const shouldReset = reason && resetReasons.includes(reason)
      const players = shouldReset
        ? room.players.map((p) => ({ ...p, ready: false, score: null }))
        : room.players
      const sessionId = shouldReset && status === 'WAITING' ? null : room.sessionId
      return { ...room, status, sessionId, players }
    }

    case 'GAME_STARTED': {
      const { sessionId } = envelope.payload as GameStartedPayload
      return { ...room, status: 'PLAYING', sessionId }
    }

    case 'GAME_FINISHED': {
      return { ...room, status: 'RESULTS' }
    }

    case 'GAME_ABORTED': {
      return { ...room, status: 'WAITING', sessionId: null }
    }

    case 'ROOM_SNAPSHOT': {
      const snapshot = envelope.payload as SnapshotDto
      return snapshot.room
    }

    default:
      return room
  }
}
