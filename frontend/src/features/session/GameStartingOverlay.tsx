import * as React from 'react'

import { DinoArtwork } from '@/components/art/DinoArtwork'
import { getRemainingSeconds } from '@/lib/serverClock'
import { cn } from '@/lib/utils'

export interface GameStartingOverlayProps {
  startsAt: string | null | undefined
  gameTitle?: string
  className?: string
}

export function GameStartingOverlay({
  startsAt,
  gameTitle = 'Game',
  className,
}: GameStartingOverlayProps) {
  const [seconds, setSeconds] = React.useState(() =>
    Math.max(1, getRemainingSeconds(startsAt)),
  )

  React.useEffect(() => {
    if (!startsAt) return

    const update = () => {
      const remaining = Math.max(0, getRemainingSeconds(startsAt))
      setSeconds(remaining)
    }

    update()
    const timer = setInterval(update, 100)
    return () => clearInterval(timer)
  }, [startsAt])

  if (!startsAt || seconds <= 0) {
    return null
  }

  return (
    <div
      role="alert"
      aria-live="assertive"
      className={cn(
        'fixed inset-0 z-50 flex flex-col items-center justify-center bg-background/85 backdrop-blur-md transition-all',
        className,
      )}
    >
      <div className="flex flex-col items-center gap-6 p-8 text-center animate-in fade-in zoom-in duration-300">
        <DinoArtwork avatarKey="dino-rex" className="size-28 sm:size-36 drop-shadow-lg animate-bounce" />

        <div className="space-y-2">
          <h2 className="font-fredoka text-2xl sm:text-4xl text-foreground font-bold">
            Get Ready for {gameTitle}!
          </h2>
          <p className="text-muted-foreground text-sm sm:text-base">
            Session starting in…
          </p>
        </div>

        <div
          key={seconds}
          className="flex size-24 sm:size-32 items-center justify-center rounded-full bg-primary text-primary-foreground font-fredoka text-5xl sm:text-7xl font-bold shadow-xl animate-in zoom-in-50 duration-200"
          aria-label={`${seconds} seconds`}
        >
          {seconds}
        </div>
      </div>
    </div>
  )
}
