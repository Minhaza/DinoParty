import type { ComponentType } from 'react'

import type { AssetSlot } from '@/assets'
import {
  DoodleSaurusBadge,
  EggBadge,
  PaletteBadge,
  PeekingEggBadge,
  PencilBadge,
} from '@/components/art/gameArt'

/**
 * Frontend-only knowledge about games: artwork, colour tone, and whether this client has screens
 * for a game. Names, bounds, settings and availability come from the server (`GET /games`) and
 * must not be added here.
 */

export type GameTone = 'draw' | 'phone' | 'impostor' | 'neutral'

export interface GameVisual {
  Badge: ComponentType<{ className?: string }>
  bannerSlot: AssetSlot | null
  tone: GameTone
}

/** Keyed by the server's `iconKey` (a stable asset id, api-contracts §2.6). */
const VISUALS: Record<string, GameVisual> = {
  'draw-guess': { Badge: PaletteBadge, bannerSlot: 'games/draw-guess', tone: 'draw' },
  telephone: { Badge: PencilBadge, bannerSlot: 'games/telephone', tone: 'phone' },
  // No painted banners yet: the banner falls back to the badge.
  'dino-impostor': { Badge: PeekingEggBadge, bannerSlot: null, tone: 'impostor' },
  'sketchy-saurus': { Badge: DoodleSaurusBadge, bannerSlot: null, tone: 'impostor' },
}

const FALLBACK_VISUAL: GameVisual = { Badge: EggBadge, bannerSlot: null, tone: 'neutral' }

export function gameVisual(iconKey: string): GameVisual {
  return VISUALS[iconKey] ?? FALLBACK_VISUAL
}

export interface GameModule {
  /** The session screens (features/session/GameStage) can render this game. */
  playable: true
  /**
   * Setting keys edited by the game's own lobby panel instead of the generic schema form; they keep
   * their server defaults when a room is created.
   */
  lobbyPanelFields: string[]
}

/** Keyed by game key. */
const MODULES: Record<string, GameModule> = {
  'draw-guess': {
    playable: true,
    lobbyPanelFields: ['difficulty', 'wordSource', 'wordCategories', 'wordPackIds'],
  },
  telephone: { playable: true, lobbyPanelFields: [] },
  'dino-impostor': { playable: true, lobbyPanelFields: [] },
  'sketchy-saurus': { playable: true, lobbyPanelFields: [] },
}

/** `null` for a game the server knows but this client cannot play yet. */
export function gameModule(key: string): GameModule | null {
  return MODULES[key] ?? null
}

/** Button/badge variants and the selected-tile ring per tone, from the existing design tokens. */
export const TONE_STYLES: Record<
  GameTone,
  { variant: 'default' | 'accent' | 'secondary'; selected: string }
> = {
  draw: { variant: 'default', selected: 'border-game-draw bg-game-draw/10 ring-game-draw/40' },
  phone: { variant: 'accent', selected: 'border-game-phone bg-game-phone/10 ring-game-phone/40' },
  impostor: {
    variant: 'secondary',
    selected: 'border-game-impostor bg-game-impostor/10 ring-game-impostor/40',
  },
  neutral: { variant: 'secondary', selected: 'border-primary bg-primary/10 ring-primary/40' },
}
