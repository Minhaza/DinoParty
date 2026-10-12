import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type ThemePreference = 'light' | 'dark' | 'system'

interface PrefsState {
  theme: ThemePreference
  setTheme: (theme: ThemePreference) => void
}

export const usePrefsStore = create<PrefsState>()(
  persist(
    (set) => ({
      theme: 'system',
      setTheme: (theme) => set({ theme }),
    }),
    { name: 'dinoparty-prefs' },
  ),
)
