import { beforeEach, describe, expect, it } from 'vitest'

import type { EventEnvelope, RoomDto, SnapshotDto } from '@/api/types'
import { useRoomStore } from '@/stores/roomStore'

const BASE_ROOM: RoomDto = {
  id: 'room-1',
  code: 'AB3D7K',
  inviteUrl: 'http://localhost:5173/join/AB3D7K',
  status: 'WAITING',
  gameKey: 'draw-guess',
  hostGuestId: 'guest-1',
  minPlayers: 2,
  maxPlayers: 8,
  settings: { cycles: 2 },
  players: [
    {
      guestId: 'guest-1',
      nickname: 'Rexy',
      avatarKey: 'dino-rex',
      avatarColor: null,
      ready: false,
      connection: 'CONNECTED',
      isHost: true,
      score: null,
    },
  ],
  sessionId: null,
  createdAt: '2026-01-01T00:00:00.000Z',
}

const BASE_SNAPSHOT: SnapshotDto = {
  room: BASE_ROOM,
  serverNow: '2026-01-01T00:00:00.000Z',
  seq: { room: 10, private: 5 },
  game: null,
}

describe('roomStore', () => {
  beforeEach(() => {
    useRoomStore.getState().reset()
  })

  it('applies snapshot and initializes room and sequence counters', () => {
    useRoomStore.getState().applySnapshot(BASE_SNAPSHOT)

    const state = useRoomStore.getState()
    expect(state.room).toEqual(BASE_ROOM)
    expect(state.lastSeq).toEqual({ room: 10, private: 5 })
    expect(state.syncStatus).toBe('synced')
    expect(state.needsResync).toBe(false)
  })

  it('ignores duplicate or stale sequence numbers (seq <= lastSeq)', () => {
    useRoomStore.getState().applySnapshot(BASE_SNAPSHOT)

    const staleEnvelope: EventEnvelope = {
      type: 'READY_UPDATED',
      roomId: 'room-1',
      roomCode: 'AB3D7K',
      ts: '2026-01-01T00:00:01.000Z',
      seq: 10, // Same as lastSeq.room
      stream: 'ROOM',
      actionId: 'act-1',
      payload: { guestId: 'guest-1', ready: true },
    }

    const outcome = useRoomStore.getState().applyEnvelope(staleEnvelope)
    expect(outcome).toBe('ignored')
    expect(useRoomStore.getState().room?.players[0].ready).toBe(false)
  })

  it('detects sequence gaps and flags needsResync', () => {
    useRoomStore.getState().applySnapshot(BASE_SNAPSHOT)

    const gapEnvelope: EventEnvelope = {
      type: 'READY_UPDATED',
      roomId: 'room-1',
      roomCode: 'AB3D7K',
      ts: '2026-01-01T00:00:01.000Z',
      seq: 12, // Gap: current is 10, expected 11
      stream: 'ROOM',
      actionId: 'act-2',
      payload: { guestId: 'guest-1', ready: true },
    }

    const outcome = useRoomStore.getState().applyEnvelope(gapEnvelope)
    expect(outcome).toBe('gap')
    expect(useRoomStore.getState().needsResync).toBe(true)
  })

  it('applies consecutive events in sequence (seq === lastSeq + 1)', () => {
    useRoomStore.getState().applySnapshot(BASE_SNAPSHOT)

    const validEnvelope: EventEnvelope = {
      type: 'READY_UPDATED',
      roomId: 'room-1',
      roomCode: 'AB3D7K',
      ts: '2026-01-01T00:00:01.000Z',
      seq: 11, // Exactly next in sequence
      stream: 'ROOM',
      actionId: 'act-3',
      payload: { guestId: 'guest-1', ready: true },
    }

    const outcome = useRoomStore.getState().applyEnvelope(validEnvelope)
    expect(outcome).toBe('applied')
    expect(useRoomStore.getState().lastSeq.room).toBe(11)
    expect(useRoomStore.getState().room?.players[0].ready).toBe(true)
  })

  it('handles PLAYER_JOINED and PLAYER_LEFT events', () => {
    useRoomStore.getState().applySnapshot(BASE_SNAPSHOT)

    // Player 2 joins
    useRoomStore.getState().applyEnvelope({
      type: 'PLAYER_JOINED',
      roomId: 'room-1',
      roomCode: 'AB3D7K',
      ts: '2026-01-01T00:00:01.000Z',
      seq: 11,
      stream: 'ROOM',
      actionId: null,
      payload: {
        player: {
          guestId: 'guest-2',
          nickname: 'Tricia',
          avatarKey: 'dino-tricera',
          avatarColor: null,
          ready: false,
          connection: 'CONNECTED',
          isHost: false,
          score: null,
        },
      },
    })

    expect(useRoomStore.getState().room?.players).toHaveLength(2)
    expect(useRoomStore.getState().room?.players[1].nickname).toBe('Tricia')

    // Player 2 leaves
    useRoomStore.getState().applyEnvelope({
      type: 'PLAYER_LEFT',
      roomId: 'room-1',
      roomCode: 'AB3D7K',
      ts: '2026-01-01T00:00:02.000Z',
      seq: 12,
      stream: 'ROOM',
      actionId: null,
      payload: { guestId: 'guest-2', reason: 'LEFT' },
    })

    expect(useRoomStore.getState().room?.players).toHaveLength(1)
    expect(useRoomStore.getState().room?.players[0].guestId).toBe('guest-1')
  })

  it('handles HOST_CHANGED event', () => {
    useRoomStore.getState().applySnapshot({
      ...BASE_SNAPSHOT,
      room: {
        ...BASE_ROOM,
        players: [
          BASE_ROOM.players[0],
          {
            guestId: 'guest-2',
            nickname: 'Tricia',
            avatarKey: 'dino-tricera',
            avatarColor: null,
            ready: true,
            connection: 'CONNECTED',
            isHost: false,
            score: null,
          },
        ],
      },
    })

    useRoomStore.getState().applyEnvelope({
      type: 'HOST_CHANGED',
      roomId: 'room-1',
      roomCode: 'AB3D7K',
      ts: '2026-01-01T00:00:01.000Z',
      seq: 11,
      stream: 'ROOM',
      actionId: null,
      payload: { hostGuestId: 'guest-2' },
    })

    const state = useRoomStore.getState()
    expect(state.room?.hostGuestId).toBe('guest-2')
    expect(state.room?.players.find((p) => p.guestId === 'guest-1')?.isHost).toBe(false)
    expect(state.room?.players.find((p) => p.guestId === 'guest-2')?.isHost).toBe(true)
  })

  it('handles PLAYER_DISCONNECTED and PLAYER_RECONNECTED events', () => {
    useRoomStore.getState().applySnapshot(BASE_SNAPSHOT)

    useRoomStore.getState().applyEnvelope({
      type: 'PLAYER_DISCONNECTED',
      roomId: 'room-1',
      roomCode: 'AB3D7K',
      ts: '2026-01-01T00:00:01.000Z',
      seq: 11,
      stream: 'ROOM',
      actionId: null,
      payload: { guestId: 'guest-1', graceEndsAt: '2026-01-01T00:00:30.000Z' },
    })

    expect(useRoomStore.getState().room?.players[0].connection).toBe('DISCONNECTED')

    useRoomStore.getState().applyEnvelope({
      type: 'PLAYER_RECONNECTED',
      roomId: 'room-1',
      roomCode: 'AB3D7K',
      ts: '2026-01-01T00:00:05.000Z',
      seq: 12,
      stream: 'ROOM',
      actionId: null,
      payload: { guestId: 'guest-1' },
    })

    expect(useRoomStore.getState().room?.players[0].connection).toBe('CONNECTED')
  })

  it('handles ROOM_CLOSED event', () => {
    useRoomStore.getState().applySnapshot(BASE_SNAPSHOT)

    useRoomStore.getState().applyEnvelope({
      type: 'ROOM_CLOSED',
      roomId: 'room-1',
      roomCode: 'AB3D7K',
      ts: '2026-01-01T00:00:01.000Z',
      seq: 11,
      stream: 'ROOM',
      actionId: null,
      payload: { reason: 'EMPTY' },
    })

    expect(useRoomStore.getState().room?.status).toBe('CLOSED')
  })

  it('handles unsequenced errors arriving on error queue (seq 0)', () => {
    useRoomStore.getState().applySnapshot(BASE_SNAPSHOT)

    const errorEnvelope: EventEnvelope = {
      type: 'ERROR',
      roomId: null,
      roomCode: null,
      ts: '2026-01-01T00:00:01.000Z',
      seq: 0,
      stream: 'PRIVATE',
      actionId: 'act-err',
      payload: { code: 'INVALID_COMMAND', message: 'Unknown command', retryable: false },
    }

    useRoomStore.getState().applyEnvelope(errorEnvelope)
    expect(useRoomStore.getState().lastError).toBe('Unknown command')
  })
})
