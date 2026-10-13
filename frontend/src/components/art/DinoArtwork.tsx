import { useId } from 'react'
import sheet from '@/assets/characters/dinosaurs.png'
import type { DinoAvatarKey } from '@/components/dinoAvatars'
import { cn } from '@/lib/utils'

// Explicit atlas regions leave each silhouette intact, including the wide wings and tails.
const REGIONS: Record<DinoAvatarKey, { viewBox: string; points: string }> = {
  'dino-rex': { viewBox: '0 0 460 512', points: '0,0 460,0 460,512 0,512' },
  'dino-tricera': {
    viewBox: '470 0 580 512',
    points: '470,0 960,0 960,280 1050,315 1050,512 470,512',
  },
  'dino-ptera': {
    viewBox: '960 0 576 512',
    points: '960,0 1536,0 1536,512 1050,512 1050,315 960,280',
  },
  'dino-stego': { viewBox: '0 512 545 512', points: '0,512 545,512 545,1024 0,1024' },
  'dino-mite': { viewBox: '545 512 490 512', points: '545,512 1035,512 1035,1024 545,1024' },
  'dino-lizard': { viewBox: '1035 512 501 512', points: '1035,512 1536,512 1536,1024 1035,1024' },
}

/** Shared transparent artwork keeps scene characters and player identities consistent. */
export function DinoArtwork({
  avatarKey,
  className,
}: {
  avatarKey: DinoAvatarKey
  className?: string
}) {
  const clipId = useId()
  const region = REGIONS[avatarKey]
  return (
    <svg
      aria-hidden="true"
      focusable="false"
      viewBox={region.viewBox}
      className={cn('pointer-events-none block aspect-square shrink-0', className)}
    >
      <defs>
        <clipPath id={clipId}>
          <polygon points={region.points} />
        </clipPath>
      </defs>
      <image href={sheet} width="1536" height="1024" clipPath={`url(#${clipId})`} />
    </svg>
  )
}
