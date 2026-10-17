import type { PlayerDto } from '@/api/types'

import type { AlbumEntryView } from '../types'

// Labels and attribution shared by the album components (live reveal and results).

/** "Prompt", "Drawing" or "Description" — step 0 is always the prompt ([TP-1]). */
export function entryKindLabel(entry: Pick<AlbumEntryView, 'step' | 'kind'>): string {
  if (entry.step === 0) return 'Prompt'
  return entry.kind === 'DRAWING' ? 'Drawing' : 'Description'
}

/** "wrote the prompt", "drew", "described it" for the live announcement. */
export function entryVerb(entry: Pick<AlbumEntryView, 'step' | 'kind'>): string {
  if (entry.step === 0) return 'wrote the prompt'
  return entry.kind === 'DRAWING' ? 'drew it' : 'described it'
}

export interface Contributor {
  nickname: string
  avatarKey: string | null
  /** No longer in the room: their entries stay attributed by the roster snapshot. */
  departed: boolean
}

export const DEPARTED = 'A departed dino'

/**
 * Who made an entry, from the room roster, else the server's roster snapshot of the session (a
 * player who left before the reveal). Guest ids are never shown.
 */
export function resolveContributor(
  guestId: string | null,
  snapshotNickname: string | null,
  players: readonly PlayerDto[],
): Contributor {
  const player = guestId ? players.find((p) => p.guestId === guestId) : undefined
  if (player && player.connection !== 'LEFT') {
    return { nickname: player.nickname, avatarKey: player.avatarKey, departed: false }
  }
  return {
    nickname: snapshotNickname ?? player?.nickname ?? DEPARTED,
    avatarKey: player?.avatarKey ?? null,
    departed: true,
  }
}
