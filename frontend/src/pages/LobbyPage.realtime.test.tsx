import type { IMessage, StompSubscription } from '@stomp/stompjs'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import type { EventEnvelope } from '@/api/types'
import { useConnectionStore } from '@/features/realtime/connectionStore'
import { RealtimeClient } from '@/features/realtime/realtimeClient'
import { setSharedRealtimeClient } from '@/features/realtime/realtimeContext'
import { LobbyPage } from '@/pages/LobbyPage'

import { useAuthStore } from '@/stores/authStore'
import { useRoomStore } from '@/stores/roomStore'
import { GUEST, mockApi, renderAt, roomFixture } from '@/test/mockApi'

const guestMe = { 'GET /guests/me': { status: 200, body: GUEST } }

const OTHER_PLAYER = {
  guestId: '22222222-2222-2222-2222-222222222222',
  nickname: 'Tricia',
  avatarKey: 'dino-tricera',
  avatarColor: null,
  ready: false,
  connection: 'CONNECTED' as const,
  isHost: false,
  score: null,
}

class ControlledStompClient {
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

  public subscribe(destination: string, callback: (msg: IMessage) => void): StompSubscription {
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

  public emitMessage(destination: string, envelope: EventEnvelope): void {
    const cb = this.subscriptions.get(destination)
    if (!cb) return
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
}

describe('LobbyPage Real-Time Integration', () => {
  let controlledStomp: ControlledStompClient | null = null

  beforeEach(() => {
    useAuthStore.setState({ token: 'test-token', lastRoomCode: null })
    useRoomStore.getState().reset()
    useConnectionStore.getState().reset()

    controlledStomp = null
    const client = new RealtimeClient({
      token: 'test-token',
      clientFactory: (cfg) => {
        controlledStomp = new ControlledStompClient(cfg)
        return controlledStomp as unknown as import('@stomp/stompjs').Client
      },
    })
    setSharedRealtimeClient(client)
  })

  afterEach(() => {
    setSharedRealtimeClient(null)
  })

  it('renders Live badge when connected and allows toggling ready', async () => {
    const room = roomFixture()
    mockApi({ ...guestMe, 'GET /rooms/AB3D7K': { status: 200, body: room } })

    renderAt(<LobbyPage />, '/rooms/AB3D7K', '/rooms/:code')

    // Expect Live badge once connected
    expect(await screen.findByText('Live')).toBeInTheDocument()

    // Expect Ready up button
    const readyBtn = screen.getByRole('button', { name: 'Ready up' })
    expect(readyBtn).toBeInTheDocument()
    expect(readyBtn).toHaveAttribute('aria-pressed', 'false')

    // Click Ready up
    const user = userEvent.setup()
    await user.click(readyBtn)

    // Verify STOMP command was published
    expect(
      controlledStomp?.published.some(
        (p) => p.destination === '/app/rooms/AB3D7K/command' && p.body.includes('"SET_READY"'),
      ),
    ).toBe(true)

    // Simulate server broadcast response
    act(() => {
      controlledStomp?.emitMessage('/topic/rooms/AB3D7K', {
        type: 'READY_UPDATED',
        roomId: room.id,
        roomCode: 'AB3D7K',
        ts: '2026-01-01T00:00:01.000Z',
        seq: 1,
        stream: 'ROOM',
        actionId: 'act-1',
        payload: { guestId: GUEST.id, ready: true },
      })
    })

    // Button should now be Cancel ready
    expect(await screen.findByRole('button', { name: 'Cancel ready' })).toBeInTheDocument()
    expect(screen.getByText('You are marked ready!')).toBeInTheDocument()
  })

  it('reflects real-time joins, leaves, ready toggles, and disconnects', async () => {
    const room = roomFixture()
    mockApi({ ...guestMe, 'GET /rooms/AB3D7K': { status: 200, body: room } })

    renderAt(<LobbyPage />, '/rooms/AB3D7K', '/rooms/:code')

    expect(await screen.findByText('Rexy')).toBeInTheDocument()
    let list = screen.getByRole('list', { name: 'Players' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(1)

    // 1. Simulate PLAYER_JOINED
    act(() => {
      controlledStomp?.emitMessage('/topic/rooms/AB3D7K', {
        type: 'PLAYER_JOINED',
        roomId: room.id,
        roomCode: 'AB3D7K',
        ts: '2026-01-01T00:00:01.000Z',
        seq: 1,
        stream: 'ROOM',
        actionId: null,
        payload: { player: OTHER_PLAYER },
      })
    })

    list = await screen.findByRole('list', { name: 'Players' })
    await waitFor(() => {
      expect(within(list).getAllByRole('listitem')).toHaveLength(2)
      expect(within(list).getByText('Tricia')).toBeInTheDocument()
    })

    // 2. Simulate other player READY_UPDATED
    act(() => {
      controlledStomp?.emitMessage('/topic/rooms/AB3D7K', {
        type: 'READY_UPDATED',
        roomId: room.id,
        roomCode: 'AB3D7K',
        ts: '2026-01-01T00:00:02.000Z',
        seq: 2,
        stream: 'ROOM',
        actionId: null,
        payload: { guestId: OTHER_PLAYER.guestId, ready: true },
      })
    })

    await waitFor(() => {
      const items = within(list).getAllByRole('listitem')
      expect(within(items[1]).getByText('Ready')).toBeInTheDocument()
    })

    // 3. Simulate other player PLAYER_DISCONNECTED
    act(() => {
      controlledStomp?.emitMessage('/topic/rooms/AB3D7K', {
        type: 'PLAYER_DISCONNECTED',
        roomId: room.id,
        roomCode: 'AB3D7K',
        ts: '2026-01-01T00:00:03.000Z',
        seq: 3,
        stream: 'ROOM',
        actionId: null,
        payload: { guestId: OTHER_PLAYER.guestId, graceEndsAt: '2026-01-01T00:00:33.000Z' },
      })
    })

    await waitFor(() => {
      const items = within(list).getAllByRole('listitem')
      expect(within(items[1]).getByText('Offline')).toBeInTheDocument()
      expect(within(items[1]).getByText(/Disconnected/)).toBeInTheDocument()
    })

    // 4. Simulate other player PLAYER_RECONNECTED
    act(() => {
      controlledStomp?.emitMessage('/topic/rooms/AB3D7K', {
        type: 'PLAYER_RECONNECTED',
        roomId: room.id,
        roomCode: 'AB3D7K',
        ts: '2026-01-01T00:00:04.000Z',
        seq: 4,
        stream: 'ROOM',
        actionId: null,
        payload: { guestId: OTHER_PLAYER.guestId },
      })
    })

    await waitFor(() => {
      const items = within(list).getAllByRole('listitem')
      expect(within(items[1]).queryByText('Offline')).not.toBeInTheDocument()
    })

    // 5. Simulate PLAYER_LEFT
    act(() => {
      controlledStomp?.emitMessage('/topic/rooms/AB3D7K', {
        type: 'PLAYER_LEFT',
        roomId: room.id,
        roomCode: 'AB3D7K',
        ts: '2026-01-01T00:00:05.000Z',
        seq: 5,
        stream: 'ROOM',
        actionId: null,
        payload: { guestId: OTHER_PLAYER.guestId, reason: 'LEFT' },
      })
    })

    await waitFor(() => {
      expect(within(list).getAllByRole('listitem')).toHaveLength(1)
      expect(within(list).queryByText('Tricia')).not.toBeInTheDocument()
    })
  })

  it('displays reconnecting banner properly', async () => {
    const room = roomFixture()
    mockApi({ ...guestMe, 'GET /rooms/AB3D7K': { status: 200, body: room } })

    renderAt(<LobbyPage />, '/rooms/AB3D7K', '/rooms/:code')
    expect(await screen.findByText('Rexy')).toBeInTheDocument()

    act(() => {
      useConnectionStore.setState({ status: 'RECONNECTING', reconnectAttempt: 3 })
    })

    expect(await screen.findByText(/Reconnecting to lobby… \(attempt 3\)/)).toBeInTheDocument()
  })

  it('displays session replaced alert when another tab connects', async () => {
    const room = roomFixture()
    mockApi({ ...guestMe, 'GET /rooms/AB3D7K': { status: 200, body: room } })

    renderAt(<LobbyPage />, '/rooms/AB3D7K', '/rooms/:code')
    expect(await screen.findByText('Rexy')).toBeInTheDocument()

    act(() => {
      useConnectionStore.setState({
        status: 'TERMINATED',
        terminalReason: 'SESSION_REPLACED',
      })
    })

    expect(await screen.findByText('Session opened in another window')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reopen here' })).toBeInTheDocument()
  })

  it('enables start game button for host when all players are ready and minimum player count is met', async () => {
    const room = roomFixture({
      players: [
        {
          guestId: GUEST.id,
          nickname: 'Rexy',
          avatarKey: 'dino-rex',
          avatarColor: null,
          ready: true,
          connection: 'CONNECTED',
          isHost: true,
          score: null,
        },
        {
          ...OTHER_PLAYER,
          ready: true,
        },
      ],
    })

    mockApi({
      ...guestMe,
      'GET /rooms/AB3D7K': { status: 200, body: room },
      'POST /rooms/AB3D7K/start': {
        status: 200,
        body: { ...room, status: 'STARTING' },
      },
    })

    renderAt(<LobbyPage />, '/rooms/AB3D7K', '/rooms/:code')

    expect(await screen.findByText('All players ready!')).toBeInTheDocument()
    const startBtn = screen.getByRole('button', { name: 'Start game' })
    expect(startBtn).not.toBeDisabled()

    const user = userEvent.setup()
    await user.click(startBtn)

    expect(await screen.findByRole('button', { name: 'Starting…' })).toBeInTheDocument()
  })

  it('allows the host to kick another player', async () => {
    const room = roomFixture({
      players: [
        {
          guestId: GUEST.id,
          nickname: 'Rexy',
          avatarKey: 'dino-rex',
          avatarColor: null,
          ready: false,
          connection: 'CONNECTED',
          isHost: true,
          score: null,
        },
        OTHER_PLAYER,
      ],
    })

    let kickedGuestId: string | null = null
    mockApi({
      ...guestMe,
      'GET /rooms/AB3D7K': { status: 200, body: room },
      'POST /rooms/AB3D7K/kick': (call) => {
        const body = call.body as { guestId: string }
        kickedGuestId = body.guestId
        return { status: 200, body: {} }
      },
    })

    renderAt(<LobbyPage />, '/rooms/AB3D7K', '/rooms/:code')

    const kickBtn = await screen.findByRole('button', { name: 'Kick Tricia' })
    expect(kickBtn).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(kickBtn)

    expect(kickedGuestId).toBe(OTHER_PLAYER.guestId)
  })
})
