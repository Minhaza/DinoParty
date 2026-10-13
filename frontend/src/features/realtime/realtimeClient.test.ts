import type { IMessage, StompSubscription } from '@stomp/stompjs'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { EventEnvelope } from '@/api/types'
import { useRoomStore } from '@/stores/roomStore'

import { useConnectionStore } from './connectionStore'
import { RealtimeClient } from './realtimeClient'

interface MockSubscription extends StompSubscription {
  id: string
  unsubscribe: () => void
}

class FakeStompClient {
  public connected = false
  public active = false
  public subscriptions = new Map<string, (msg: IMessage) => void>()
  public published: Array<{ destination: string; headers?: Record<string, string>; body: string }> =
    []

  public options: Record<string, unknown>

  constructor(options: Record<string, unknown>) {
    this.options = options
  }

  public activate(): void {
    this.active = true
    this.connected = true
    const onConnect = this.options.onConnect as (() => void) | undefined
    onConnect?.()
  }

  public deactivate(): Promise<void> {
    this.active = false
    this.connected = false
    return Promise.resolve()
  }

  public subscribe(destination: string, callback: (msg: IMessage) => void): MockSubscription {
    this.subscriptions.set(destination, callback)
    return {
      id: destination,
      unsubscribe: () => {
        this.subscriptions.delete(destination)
      },
    }
  }

  public publish(params: {
    destination: string
    headers?: Record<string, string>
    body: string
  }): void {
    this.published.push(params)
  }

  // Helper for tests to simulate incoming message
  public emitMessage(destination: string, envelope: EventEnvelope): void {
    const cb = this.subscriptions.get(destination)
    if (!cb) throw new Error(`No subscriber for ${destination}`)
    cb({
      body: JSON.stringify(envelope),
      headers: {},
      command: 'MESSAGE',
      isBinaryBody: false,
      binaryBody: new Uint8Array(),
      ack: () => {},
      nack: () => {},
    })
  }

  // Helper for tests to simulate close
  public emitClose(code = 1000, reason = ''): void {
    this.connected = false
    const onClose = this.options.onWebSocketClose as
      ((event: { code: number; reason: string }) => void) | undefined
    onClose?.({ code, reason })
  }
}

describe('RealtimeClient', () => {
  let fakeClient: FakeStompClient | null = null

  beforeEach(() => {
    fakeClient = null
    useConnectionStore.getState().reset()
    useRoomStore.getState().reset()
    vi.clearAllTimers()
  })

  function createTestClient(token = 'valid-token') {
    const client = new RealtimeClient({
      token,
      baseReconnectDelay: 50,
      maxReconnectDelay: 200,
      clientFactory: (cfg) => {
        const stub = new FakeStompClient(cfg)
        fakeClient = stub
        return stub as unknown as import('@stomp/stompjs').Client
      },
    })

    return client
  }

  it('subscribes to public room topic, private queue, and errors queue on connect', () => {
    const client = createTestClient()

    client.subscribeToRoom('AB3D7K')
    const fake = fakeClient!
    fake.activate()

    expect(fake.subscriptions.has('/topic/rooms/AB3D7K')).toBe(true)
    expect(fake.subscriptions.has('/user/queue/rooms/AB3D7K')).toBe(true)
    expect(fake.subscriptions.has('/user/queue/errors')).toBe(true)
  })

  it('publishes commands with actionId and json payload to /app/rooms/{CODE}/command', () => {
    const client = createTestClient()
    client.connect()
    const fake = fakeClient!
    fake.activate()
    client.subscribeToRoom('AB3D7K')

    // Reset published array to isolate the setReady command
    fake.published = []

    const actionId = client.setReady(true)
    expect(typeof actionId).toBe('string')
    expect(fake.published).toHaveLength(1)
    expect(fake.published[0].destination).toBe('/app/rooms/AB3D7K/command')

    const payload = JSON.parse(fake.published[0].body)
    expect(payload).toEqual({
      type: 'SET_READY',
      actionId,
      phaseId: null,
      payload: { ready: true },
    })
  })

  it('requests snapshot when a sequence gap is detected in incoming messages', () => {
    const client = createTestClient()
    client.connect()
    const fake = fakeClient!
    fake.activate()
    client.subscribeToRoom('AB3D7K')

    // Initial snapshot at seq 10
    useRoomStore.getState().applySnapshot({
      room: {
        id: 'r-1',
        code: 'AB3D7K',
        inviteUrl: '',
        status: 'WAITING',
        gameKey: 'draw-guess',
        hostGuestId: 'g-1',
        minPlayers: 2,
        maxPlayers: 8,
        settings: {},
        players: [],
        sessionId: null,
        createdAt: '',
      },
      serverNow: '',
      seq: { room: 10, private: 1 },
      game: null,
    })

    // Clear previous publishes (from setup)
    fake.published = []

    // Gap envelope arriving at seq 12
    fake.emitMessage('/topic/rooms/AB3D7K', {
      type: 'READY_UPDATED',
      roomId: 'r-1',
      roomCode: 'AB3D7K',
      ts: '2026-01-01T00:00:00.000Z',
      seq: 12,
      stream: 'ROOM',
      actionId: 'act-x',
      payload: { guestId: 'g-1', ready: true },
    })

    // Should have published REQUEST_SNAPSHOT command to resync
    expect(fake.published.some((p) => p.body.includes('"REQUEST_SNAPSHOT"'))).toBe(true)
  })

  it('handles terminal SESSION_REPLACED event without auto-reconnecting', () => {
    const client = createTestClient()
    client.connect()
    const fake = fakeClient!
    fake.activate()
    client.subscribeToRoom('AB3D7K')

    fake.emitMessage('/user/queue/rooms/AB3D7K', {
      type: 'SESSION_REPLACED',
      roomId: 'r-1',
      roomCode: 'AB3D7K',
      ts: '2026-01-01T00:00:00.000Z',
      seq: 2,
      stream: 'PRIVATE',
      actionId: null,
      payload: {},
    })

    expect(useConnectionStore.getState().status).toBe('TERMINATED')
    expect(useConnectionStore.getState().terminalReason).toBe('SESSION_REPLACED')
  })

  it('cleans up subscriptions on unsubscribe or disconnect', () => {
    const client = createTestClient()
    client.connect()
    const fake = fakeClient!
    fake.activate()
    client.subscribeToRoom('AB3D7K')

    expect(fake.subscriptions.size).toBe(3)

    client.unsubscribeFromRoom()
    expect(fake.subscriptions.size).toBe(0)
  })

  it('does not open a second socket when connect() is called while one is active', () => {
    const created: FakeStompClient[] = []
    const client = new RealtimeClient({
      token: 'valid-token',
      clientFactory: (cfg) => {
        const stub = new FakeStompClient(cfg)
        created.push(stub)
        return stub as unknown as import('@stomp/stompjs').Client
      },
    })
    client.connect()
    client.connect()
    client.setToken('valid-token')
    client.connect()
    expect(created).toHaveLength(1)
  })

  it('ignores a late close from a client that was already replaced', () => {
    const created: FakeStompClient[] = []
    const client = new RealtimeClient({
      token: 'valid-token',
      clientFactory: (cfg) => {
        const stub = new FakeStompClient(cfg)
        created.push(stub)
        return stub as unknown as import('@stomp/stompjs').Client
      },
    })
    client.connect()
    client.disconnect()
    client.connect()
    expect(created).toHaveLength(2)

    created[0].emitClose(1008, 'SESSION_REPLACED')

    expect(useConnectionStore.getState().terminalReason).toBeNull()
    expect(useConnectionStore.getState().status).toBe('CONNECTED')
  })
})
