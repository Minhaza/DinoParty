import { Timer } from 'lucide-react'

import { useServerCountdown } from '@/features/session/useServerCountdown'
import { cn } from '@/lib/utils'

export interface TelephoneRevealProgressProps {
  /** "Album 2 of 5 — Step 3 of 6" for what is on screen. */
  label: string
  chains: number
  entriesPerChain: number
  /** Revealed entries per album, from the furthest revealed position. */
  revealedPerAlbum: readonly number[]
  shownAlbum: number
  /** Display-only hint; the server moves the cursor when it is due ([TP-6]). */
  autoAdvanceAt: string | null
}

/** Where the reveal stands: one segment per album, filled as its pages are turned. */
export function TelephoneRevealProgress({
  label,
  chains,
  entriesPerChain,
  revealedPerAlbum,
  shownAlbum,
  autoAdvanceAt,
}: TelephoneRevealProgressProps) {
  const countdown = useServerCountdown({ deadlineAt: autoAdvanceAt })

  return (
    <div className="flex flex-col gap-2 rounded-2xl border border-border bg-card/90 px-4 py-3 shadow-xs">
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1">
        <p className="font-fredoka text-lg font-semibold text-foreground tabular-nums">{label}</p>
        {autoAdvanceAt && !countdown.isExpired && (
          <p className="inline-flex items-center gap-1.5 text-xs font-medium text-muted-foreground tabular-nums">
            <Timer className="size-3.5" aria-hidden="true" />
            Next page on its own in {countdown.formattedTime}
          </p>
        )}
      </div>
      <div className="flex gap-1" aria-hidden="true">
        {Array.from({ length: chains }, (_, chain) => {
          const revealed = revealedPerAlbum[chain] ?? 0
          const fill = entriesPerChain > 0 ? (revealed / entriesPerChain) * 100 : 0
          return (
            <span
              key={chain}
              className={cn(
                'h-1.5 flex-1 overflow-hidden rounded-full bg-muted',
                chain === shownAlbum && 'ring-2 ring-game-phone/30',
              )}
            >
              <span
                className="block h-full rounded-full bg-game-phone transition-[width] duration-300"
                style={{ width: `${fill}%` }}
              />
            </span>
          )
        })}
      </div>
    </div>
  )
}
