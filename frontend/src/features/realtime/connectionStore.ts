import { create } from 'zustand'

export type ConnectionStatus =
  'IDLE' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING' | 'DISCONNECTED' | 'TERMINATED'

export type TerminalReason = 'SESSION_REPLACED' | 'SESSION_EXPIRED' | 'UNAUTHENTICATED' | null

interface ConnectionState {
  status: ConnectionStatus
  terminalReason: TerminalReason
  reconnectAttempt: number
  lastError: string | null
  isOnline: boolean

  setStatus: (status: ConnectionStatus) => void
  setTerminal: (reason: TerminalReason) => void
  setReconnectAttempt: (attempt: number) => void
  setLastError: (error: string | null) => void
  setIsOnline: (online: boolean) => void
  reset: () => void
}

export const useConnectionStore = create<ConnectionState>((set) => ({
  status: 'IDLE',
  terminalReason: null,
  reconnectAttempt: 0,
  lastError: null,
  isOnline: typeof navigator !== 'undefined' ? navigator.onLine : true,

  setStatus: (status) =>
    set(() => ({
      status,
      // If we transition to connected, clear terminal reason and reconnect count
      ...(status === 'CONNECTED'
        ? { terminalReason: null, reconnectAttempt: 0, lastError: null }
        : {}),
      // If idle, reset reconnect attempt and last error
      ...(status === 'IDLE' ? { reconnectAttempt: 0, lastError: null } : {}),
    })),

  setTerminal: (terminalReason) =>
    set({
      status: 'TERMINATED',
      terminalReason,
    }),

  setReconnectAttempt: (reconnectAttempt) => set({ reconnectAttempt }),

  setLastError: (lastError) => set({ lastError }),

  setIsOnline: (isOnline) => set({ isOnline }),

  reset: () =>
    set({
      status: 'IDLE',
      terminalReason: null,
      reconnectAttempt: 0,
      lastError: null,
    }),
}))
