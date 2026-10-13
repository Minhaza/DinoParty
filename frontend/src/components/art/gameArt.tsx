import type { GameKey } from '@/api/types'
import { assetUrl } from '@/assets'
import { cn } from '@/lib/utils'

const INK = '#10213d'

/** Compact palette icon (interface icon, not artwork) for the Draw & Guess card header. */
export function PaletteBadge({ className }: { className?: string }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 64 64" className={className} fill="none">
      <path
        d="M32 6 C16 6 5 18 5 32 c0 15 11 26 25 26 6 0 8-4 6-8 -2-4 0-8 5-8 h8 c6 0 10-4 10-10 C59 17 47 6 32 6 Z"
        fill="#ffd9a0"
        stroke={INK}
        strokeWidth="3"
        strokeLinejoin="round"
      />
      <circle cx="19" cy="28" r="5" fill="#f36b36" />
      <circle cx="30" cy="17" r="5" fill="#11956b" />
      <circle cx="44" cy="19" r="5" fill="#7565e8" />
      <circle cx="21" cy="43" r="5" fill="#3aa6d8" />
    </svg>
  )
}

/** Compact pencil icon (interface icon, not artwork) for the Dino Telephone card header. */
export function PencilBadge({ className }: { className?: string }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 64 64" className={className} fill="none">
      <g transform="rotate(40 32 32)">
        <rect
          x="24"
          y="4"
          width="16"
          height="42"
          rx="3"
          fill="#7565e8"
          stroke={INK}
          strokeWidth="3"
        />
        <rect
          x="24"
          y="4"
          width="16"
          height="9"
          rx="3"
          fill="#f36b36"
          stroke={INK}
          strokeWidth="3"
        />
        <path
          d="M24 46 L32 62 L40 46 Z"
          fill="#ffe0b0"
          stroke={INK}
          strokeWidth="3"
          strokeLinejoin="round"
        />
      </g>
    </svg>
  )
}

const BANNER_SLOT = { 'draw-guess': 'games/draw-guess', telephone: 'games/telephone' } as const

/**
 * Painted banner for a game card. Until the illustration file exists it shows a plain tinted
 * panel with the game's interface icon, never a drawn stand-in scene.
 */
export function GameBanner({ game, className }: { game: GameKey; className?: string }) {
  const url = assetUrl(BANNER_SLOT[game])
  if (url) {
    return (
      <img
        src={url}
        alt=""
        loading="lazy"
        decoding="async"
        className={cn('size-full object-cover', className)}
      />
    )
  }
  const Icon = game === 'draw-guess' ? PaletteBadge : PencilBadge
  return (
    <div
      className={cn(
        'flex size-full items-center justify-center',
        game === 'draw-guess' ? 'bg-game-draw/15' : 'bg-game-phone/15',
        className,
      )}
    >
      <Icon className="size-20 opacity-80" />
    </div>
  )
}

/** Small confetti burst for celebration moments. Pure CSS motion, hidden by reduced-motion. */
export function Confetti({ count = 24 }: { count?: number }) {
  const colors = ['#f36b36', '#11956b', '#7565e8', '#f5b335', '#3aa6d8']
  return (
    <div aria-hidden="true" className="pointer-events-none absolute inset-0 overflow-hidden">
      {Array.from({ length: count }, (_, i) => (
        <span
          key={i}
          className="absolute top-0 block h-3 w-2 animate-confetti rounded-[2px] motion-reduce:hidden"
          style={{
            left: `${(i * 37) % 100}%`,
            background: colors[i % colors.length],
            animationDelay: `${(i % 8) * 0.35}s`,
            animationDuration: `${3 + (i % 5) * 0.4}s`,
          }}
        />
      ))}
    </div>
  )
}
