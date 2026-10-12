import { create } from 'zustand'
import { persist } from 'zustand/middleware'

import type { DinoAvatarKey } from '@/components/dinoAvatars'

export interface GuestState {
  nickname: string
  avatarKey: DinoAvatarKey
  avatarColor: string | null
  setProfile: (
    profile: Partial<{ nickname: string; avatarKey: DinoAvatarKey; avatarColor: string | null }>,
  ) => void
  resetProfile: () => void
}

const DEFAULT_PROFILE = {
  nickname: 'RexPlayer',
  avatarKey: 'dino-rex' as DinoAvatarKey,
  avatarColor: null,
}

export const useGuestStore = create<GuestState>()(
  persist(
    (set) => ({
      ...DEFAULT_PROFILE,
      setProfile: (profile) => set((state) => ({ ...state, ...profile })),
      resetProfile: () => set(DEFAULT_PROFILE),
    }),
    { name: 'dinoparty-guest' },
  ),
)
