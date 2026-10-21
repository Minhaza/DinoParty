import { assetUrl } from '@/assets'
import { cn } from '@/lib/utils'

import { gameVisual } from './gameModules'

/**
 * Game painting for a server-provided `iconKey`. Games without artwork get a calm fallback panel
 * built from the shared page texture and the game's badge, so new games never break a layout.
 */
export function GameBanner({ iconKey, className }: { iconKey: string; className?: string }) {
  const { bannerSlot, Badge } = gameVisual(iconKey)
  if (bannerSlot) {
    return (
      <img
        src={assetUrl(bannerSlot)}
        alt=""
        loading="lazy"
        decoding="async"
        className={cn('size-full object-cover', className)}
      />
    )
  }
  return (
    <div
      aria-hidden="true"
      className={cn('flex size-full items-center justify-center bg-secondary', className)}
      style={{ backgroundImage: `url(${assetUrl('backgrounds/page-soft')})` }}
    >
      <Badge className="size-20 drop-shadow-sm" />
    </div>
  )
}
