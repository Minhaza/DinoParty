import pageSoft from './backgrounds/page-soft.svg'
import drawGuess from './games/draw-guess.png'
import telephone from './games/telephone.png'

// Only reusable page/game artwork belongs here. HeroScene and DinoArtwork import their assets directly.
const ASSETS = {
  'backgrounds/page-soft': pageSoft,
  'games/draw-guess': drawGuess,
  'games/telephone': telephone,
} as const

export type AssetSlot = keyof typeof ASSETS

export function assetUrl(slot: AssetSlot): string {
  return ASSETS[slot]
}
