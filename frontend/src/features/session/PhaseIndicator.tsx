import * as React from 'react'

import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

export interface PhaseIndicatorProps {
  phase: string | null | undefined
  round?: number | null
  totalRounds?: number | null
  className?: string
}

function formatPhaseName(phase: string): string {
  // Convert SNAKE_CASE or camelCase to Title Case
  return phase
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/\b\w/g, (char) => char.toUpperCase())
}

export function PhaseIndicator({
  phase,
  round,
  totalRounds,
  className,
}: PhaseIndicatorProps) {
  const [announcedPhase, setAnnouncedPhase] = React.useState<string>('')
  const prevPhaseRef = React.useRef<string | null>(null)

  React.useEffect(() => {
    if (phase && phase !== prevPhaseRef.current) {
      prevPhaseRef.current = phase
      const formatted = formatPhaseName(phase)
      const roundInfo = round ? ` - Round ${round}` : ''
      setAnnouncedPhase(`Phase started: ${formatted}${roundInfo}`)
    }
  }, [phase, round])

  if (!phase) {
    return null
  }

  const phaseTitle = formatPhaseName(phase)

  return (
    <div className={cn('inline-flex items-center gap-2', className)}>
      <span className="sr-only" role="status" aria-live="polite">
        {announcedPhase}
      </span>

      <Badge
        variant="secondary"
        className="px-3 py-1 font-fredoka text-sm tracking-wide bg-secondary/80 text-secondary-foreground"
      >
        {phaseTitle}
      </Badge>

      {typeof round === 'number' && (
        <span className="text-xs font-semibold text-muted-foreground tabular-nums">
          Round {round}
          {typeof totalRounds === 'number' && totalRounds > 0 ? ` / ${totalRounds}` : ''}
        </span>
      )}
    </div>
  )
}
