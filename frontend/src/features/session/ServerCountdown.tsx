import { Clock, Flame } from 'lucide-react'

import { cn } from '@/lib/utils'

import { useServerCountdown } from './useServerCountdown'

export interface ServerCountdownProps {
  deadlineAt: string | null | undefined
  totalDurationSeconds?: number
  onComplete?: () => void
  className?: string
  size?: 'sm' | 'md' | 'lg'
}

export function ServerCountdown({
  deadlineAt,
  totalDurationSeconds,
  onComplete,
  className,
  size = 'md',
}: ServerCountdownProps) {
  const { isUrgent, isExpired, formattedTime, announcement } = useServerCountdown({
    deadlineAt,
    totalDurationSeconds,
    onComplete,
  })

  if (!deadlineAt) {
    return null
  }

  const sizeClasses = {
    sm: 'px-2 py-1 text-xs gap-1',
    md: 'px-3 py-1.5 text-sm gap-1.5 font-semibold',
    lg: 'px-4 py-2 text-base gap-2 font-bold',
  }[size]

  const iconSizes = {
    sm: 'size-3.5',
    md: 'size-4',
    lg: 'size-5',
  }[size]

  return (
    <div className={cn('inline-flex items-center', className)}>
      {/* Screen reader announcement */}
      <span className="sr-only" role="status" aria-live="polite">
        {announcement}
      </span>

      <div
        className={cn(
          'inline-flex items-center rounded-full transition-colors font-mono tabular-nums',
          sizeClasses,
          isExpired
            ? 'bg-muted text-muted-foreground border border-border'
            : isUrgent
              ? 'bg-destructive/15 text-destructive border border-destructive/40 animate-pulse'
              : 'bg-primary/10 text-[#064e3b] dark:text-[#a7f3d0] border border-primary/20',
        )}
        aria-label={`Time remaining: ${formattedTime}`}
      >
        {isUrgent ? (
          <Flame className={cn(iconSizes, 'animate-bounce text-destructive')} aria-hidden="true" />
        ) : (
          <Clock className={iconSizes} aria-hidden="true" />
        )}
        <span>{formattedTime}</span>
        {isUrgent && <span className="sr-only">Hurry up!</span>}
      </div>
    </div>
  )
}
