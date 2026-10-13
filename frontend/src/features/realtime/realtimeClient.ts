import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'

import type { ClientCommand, EventEnvelope } from '@/api/types'
import { generateUUID } from '@/lib/uuid'
import { useRoomStore } from '@/stores/roomStore'

import {
  type ConnectionStatus,
  type TerminalReason,
  useConnectionStore,
} from './connectionStore'

export function getDefaultWsUrl(): string {
  if (typeof window === 'undefined') return 'ws://localhost:8080/ws'
  const envUrl = (import.meta as unknown as { env?: { VITE_WS_URL?: string } }).env?.VITE_WS_URL
  if (envUrl) return envUrl
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws`
}

export interface RealtimeClientOptions {
  wsUrl?: string
  token: string | null
  webSocketFactory?: () => WebSocket
  baseReconnectDelay?: number
  maxReconnectDelay?: number
  onStatusChange?: (status: ConnectionStatus) => void
  onTerminal?: (reason: TerminalReason) => void
  clientFactory?: (config: Record<string, unknown>) => Client
}

export class RealtimeClient {
  private client: Client | null = null
  private token: string | null = null
  private wsUrl: string
  private currentRoomCode: string | null = null
  private subscriptions: StompSubscription[] = []
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private intentionalDisconnect = false
  private terminal = false
  private lastStompErrorCode: string | null = null

  private baseDelay: number
  private maxDelay: number
  private webSocketFactory?: () => WebSocket
  private onStatusChange?: (status: ConnectionStatus) => void
  private onTerminal?: (reason: TerminalReason) => void
  private clientFactory?: (config: Record<string, unknown>) => Client

  constructor(options: RealtimeClientOptions) {
    this.token = options.token
    this.wsUrl = options.wsUrl || getDefaultWsUrl()
    this.webSocketFactory = options.webSocketFactory
    this.baseDelay = options.baseReconnectDelay ?? 1000
    this.maxDelay = options.maxReconnectDelay ?? 15000
    this.onStatusChange = options.onStatusChange
    this.onTerminal = options.onTerminal
    this.clientFactory = options.clientFactory
  }


  public setToken(token: string | null): void {
    if (this.token === token) return
    this.token = token
    if (this.client?.active) {
      this.disconnect()
      if (token && this.currentRoomCode) {
        this.connect()
      }
    }
  }

  public subscribeToRoom(roomCode: string): void {
    const normalized = roomCode.toUpperCase()
    if (this.currentRoomCode === normalized && this.subscriptions.length > 0) {
      return
    }

    this.currentRoomCode = normalized
    if (this.client?.connected) {
      this.setupRoomSubscriptions(normalized)
      this.requestSnapshot()
    } else if (!this.client?.active && this.token) {
      this.connect()
    }
  }

  public unsubscribeFromRoom(): void {
    this.clearSubscriptions()
    this.currentRoomCode = null
  }

  public connect(): void {
    if (!this.token) {
      this.updateStatus('IDLE')
      return
    }

    if (this.terminal) {
      return
    }

    this.intentionalDisconnect = false
    this.clearReconnectTimer()

    const isReconnecting = useConnectionStore.getState().reconnectAttempt > 0
    this.updateStatus(isReconnecting ? 'RECONNECTING' : 'CONNECTING')

    const config = {
      brokerURL: this.webSocketFactory ? undefined : this.wsUrl,
      webSocketFactory: this.webSocketFactory,
      connectHeaders: {
        'accept-version': '1.2',
        'heart-beat': '10000,10000',
        Authorization: `Bearer ${this.token}`,
      },
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      reconnectDelay: 0, // Managed manually with backoff and terminal check

      onConnect: () => {
        this.lastStompErrorCode = null
        this.updateStatus('CONNECTED')
        useConnectionStore.getState().setReconnectAttempt(0)
        useConnectionStore.getState().setLastError(null)

        if (this.currentRoomCode) {
          this.setupRoomSubscriptions(this.currentRoomCode)
          this.requestSnapshot()
        }
      },

      onStompError: (frame: { headers: Record<string, string> }) => {
        const code = frame.headers.code || ''
        this.lastStompErrorCode = code
        const message = frame.headers.message || 'STOMP error'
        useConnectionStore.getState().setLastError(message)

        if (code === 'UNAUTHENTICATED') {
          this.handleTerminal('UNAUTHENTICATED')
        }
      },

      onWebSocketClose: (event: { code: number; reason?: string }) => {
        this.clearSubscriptions()

        if (this.intentionalDisconnect) {
          this.updateStatus('DISCONNECTED')
          return
        }

        const reason = (event.reason || '').toUpperCase()

        if (event.code === 1008 || event.code === 1002) {
          if (reason.includes('SESSION_REPLACED')) {
            this.handleTerminal('SESSION_REPLACED')
            return
          }
          if (reason.includes('SESSION_EXPIRED')) {
            this.handleTerminal('SESSION_EXPIRED')
            return
          }
          if (reason.includes('UNAUTHENTICATED') || this.lastStompErrorCode === 'UNAUTHENTICATED') {
            this.handleTerminal('UNAUTHENTICATED')
            return
          }
        }

        if (this.terminal) {
          return
        }

        this.scheduleReconnect()
      },

      onWebSocketError: () => {
        useConnectionStore.getState().setLastError('WebSocket error occurred')
      },
    }

    this.client = this.clientFactory ? this.clientFactory(config) : new Client(config)


    this.client.activate()
  }

  public disconnect(): void {
    this.intentionalDisconnect = true
    this.clearReconnectTimer()
    this.clearSubscriptions()

    if (this.client) {
      void this.client.deactivate()
      this.client = null
    }

    this.updateStatus('DISCONNECTED')
  }

  public sendCommand<T = unknown>(command: ClientCommand<T>): string {
    if (!this.client?.connected || !this.currentRoomCode) {
      throw new Error('Cannot send command: not connected to room')
    }

    const actionId = command.actionId || generateUUID()
    const payloadToSend = {
      type: command.type,
      actionId,
      phaseId: command.phaseId ?? null,
      payload: command.payload ?? {},
    }

    this.client.publish({
      destination: `/app/rooms/${this.currentRoomCode}/command`,
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify(payloadToSend),
    })

    return actionId
  }

  public setReady(ready: boolean): string {
    return this.sendCommand({
      type: 'SET_READY',
      payload: { ready },
    })
  }

  public requestSnapshot(): string {
    return this.sendCommand({
      type: 'REQUEST_SNAPSHOT',
      payload: {},
    })
  }

  public ping(): string {
    return this.sendCommand({
      type: 'PING',
      payload: {},
    })
  }

  private setupRoomSubscriptions(roomCode: string): void {
    if (!this.client?.connected) return
    this.clearSubscriptions()

    // 1. Public room stream
    const subTopic = this.client.subscribe(
      `/topic/rooms/${roomCode}`,
      (msg) => this.handleMessage(msg),
    )
    this.subscriptions.push(subTopic)

    // 2. Private room stream
    const subPrivate = this.client.subscribe(
      `/user/queue/rooms/${roomCode}`,
      (msg) => this.handleMessage(msg),
    )
    this.subscriptions.push(subPrivate)

    // 3. User errors queue
    const subErrors = this.client.subscribe('/user/queue/errors', (msg) =>
      this.handleMessage(msg),
    )
    this.subscriptions.push(subErrors)
  }

  private handleMessage(message: IMessage): void {
    try {
      const envelope = JSON.parse(message.body) as EventEnvelope
      if (envelope.type === 'SESSION_REPLACED') {
        this.handleTerminal('SESSION_REPLACED')
        return
      }

      const outcome = useRoomStore.getState().applyEnvelope(envelope)
      if (outcome === 'gap') {
        // Gap detected: request snapshot to resync
        this.requestSnapshot()
      }
    } catch (err) {
      console.error('Failed to parse realtime message', err)
    }
  }

  private handleTerminal(reason: TerminalReason): void {
    this.terminal = true
    this.clearReconnectTimer()
    this.clearSubscriptions()

    if (this.client) {
      void this.client.deactivate()
      this.client = null
    }

    useConnectionStore.getState().setTerminal(reason)
    this.onTerminal?.(reason)
    this.onStatusChange?.('TERMINATED')
  }

  private scheduleReconnect(): void {
    this.clearReconnectTimer()

    const currentAttempt = useConnectionStore.getState().reconnectAttempt
    const nextAttempt = currentAttempt + 1
    useConnectionStore.getState().setReconnectAttempt(nextAttempt)
    this.updateStatus('RECONNECTING')

    // Exponential backoff with jitter
    const delay = Math.min(
      this.baseDelay * Math.pow(2, nextAttempt - 1),
      this.maxDelay,
    ) * (0.8 + Math.random() * 0.4)

    this.reconnectTimer = setTimeout(() => {
      if (!this.intentionalDisconnect && !this.terminal) {
        this.connect()
      }
    }, delay)
  }

  private clearSubscriptions(): void {
    for (const sub of this.subscriptions) {
      try {
        sub.unsubscribe()
      } catch {
        // Ignore unsubscribe error during shutdown
      }
    }
    this.subscriptions = []
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
  }

  private updateStatus(status: ConnectionStatus): void {
    useConnectionStore.getState().setStatus(status)
    this.onStatusChange?.(status)
  }
}
