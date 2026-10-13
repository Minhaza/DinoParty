import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export interface AuthState {
  /** Opaque guest bearer token; the profile itself lives in TanStack Query (`/guests/me`). */
  token: string | null
  /**
   * Convenience hint for "back to my room" after a refresh. The server is authoritative: the hint
   * is dropped as soon as the room query says we are not a member (see LobbyPage).
   */
  lastRoomCode: string | null
  setToken: (token: string) => void
  setLastRoomCode: (code: string | null) => void
  clear: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      lastRoomCode: null,
      setToken: (token) => set({ token }),
      setLastRoomCode: (lastRoomCode) => set({ lastRoomCode }),
      clear: () => set({ token: null, lastRoomCode: null }),
    }),
    { name: 'dinoparty-auth' },
  ),
)
