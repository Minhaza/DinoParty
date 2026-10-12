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
      // Bright theme is the brand default; dark/system remain opt-in via the header toggle.
      theme: 'light',
      setTheme: (theme) => set({ theme }),
    }),
    {
      name: 'dinoparty-prefs',
      version: 1,
      // v0 defaulted to 'system'; move those users onto the new bright default.
      migrate: (persisted, version) => {
        const state = persisted as Partial<PrefsState> | undefined
        if (version === 0 && state?.theme === 'system') return { ...state, theme: 'light' }
        return state as PrefsState
      },
    },
  ),
)
