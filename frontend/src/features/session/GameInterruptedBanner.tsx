import { AlertTriangle, RotateCcw, X } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export interface GameInterruptedBannerProps {
  reason?: string | null
  isServerRestart?: boolean
  onDismiss: () => void
  className?: string
}

export function GameInterruptedBanner({
  reason,
  isServerRestart = false,
  onDismiss,
  className,
}: GameInterruptedBannerProps) {
  if (!reason && !isServerRestart) return null

  const title = isServerRestart
    ? 'Game interrupted by server restart'
    : 'Game session aborted'

  const message = isServerRestart
    ? 'The server was restarted while this game was running. The room has returned to the lobby.'
    : reason === 'INSUFFICIENT_PLAYERS'
      ? 'The game was aborted because too many players left the room.'
      : reason === 'ROOM_CLOSED'
        ? 'The game was closed by the host.'
        : `The game was interrupted (${reason || 'Unknown reason'}). Returning to lobby.`

  return (
    <div
      role="alert"
      className={cn(
        'flex flex-col sm:flex-row sm:items-center justify-between gap-3 rounded-2xl border border-amber-500/50 bg-amber-500/10 p-4 text-amber-900 dark:text-amber-200',
        className,
      )}
    >
      <div className="flex items-start gap-3">
        <AlertTriangle className="size-5 shrink-0 text-amber-600 dark:text-amber-400 mt-0.5" />
        <div>
          <p className="font-bold text-sm sm:text-base">{title}</p>
          <p className="text-xs sm:text-sm text-foreground/80 mt-0.5">{message}</p>
        </div>
      </div>

      <div className="flex items-center gap-2 self-start sm:self-auto shrink-0">
        <Button
          variant="outline"
          size="sm"
          onClick={onDismiss}
          className="gap-1.5 border-amber-500/40 hover:bg-amber-500/20"
        >
          <RotateCcw className="size-3.5" />
          Dismiss
        </Button>
        <Button
          variant="ghost"
          size="icon"
          onClick={onDismiss}
          className="size-8 text-amber-900 dark:text-amber-200 hover:bg-amber-500/20"
          aria-label="Close alert"
        >
          <X className="size-4" />
        </Button>
      </div>
    </div>
  )
}
