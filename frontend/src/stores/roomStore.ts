import { create } from 'zustand'

import type {
  EventEnvelope,
  GameErrorPayload,
  HostChangedPayload,
  PlayerDisconnectedPayload,
  PlayerJoinedPayload,
  PlayerLeftPayload,
  PlayerReconnectedPayload,
  ReadyUpdatedPayload,
  RoomDto,
  RoomSettingsUpdatedPayload,
  RoomStatusChangedPayload,
  SnapshotDto,
  SnapshotSeq,
} from '@/api/types'

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

    // Handle errors arriving on /user/queue/errors (seq = 0, unsequenced)
    if (envelope.type === 'ERROR' || envelope.seq === 0) {
      const payload = envelope.payload as GameErrorPayload | undefined
      if (payload && typeof payload.message === 'string') {
        set({ lastError: payload.message })
      }
      return 'applied'
    }

    // A snapshot replaces the remembered seq wholesale (contract §3.2), so it is exempt from the
    // dedupe/gap checks: after a server restart the counters start again at 1 and a snapshot at
    // seq 1 would otherwise be dropped as a duplicate, leaving the client deaf to every later event.
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

  reset: () =>
    set({
      room: null,
      serverNow: null,
      lastSeq: { ...initialSeq },
      syncStatus: 'idle',
      needsResync: false,
      lastError: null,
      terminalNotice: null,
    }),
}))

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
      const { status } = envelope.payload as RoomStatusChangedPayload
      return { ...room, status }
    }

    case 'ROOM_SNAPSHOT': {
      const snapshot = envelope.payload as SnapshotDto
      return snapshot.room
    }

    default:
      return room
  }
}
