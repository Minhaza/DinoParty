import { MessageSquareText, PencilLine, ScrollText } from 'lucide-react'

import { ServerCountdown } from '@/features/session/ServerCountdown'
import { useServerCountdown } from '@/features/session/useServerCountdown'

import { STEP_COPY, stepLabel } from '../copy'
import type { TelephoneKind } from '../types'

const KIND_ICONS = {
  PROMPT: ScrollText,
  DRAW: PencilLine,
  DESCRIBE: MessageSquareText,
} as const

export interface TelephonePhaseHeaderProps {
  kind: TelephoneKind
  step: number
  totalSteps: number
  deadlineAt: string | null
  submitted: boolean
  /** Called once when the local countdown reaches zero (display only; the server ends the step). */
  onExpire?: () => void
}

export function TelephonePhaseHeader({
  kind,
  step,
  totalSteps,
  deadlineAt,
  submitted,
  onExpire,
}: TelephonePhaseHeaderProps) {
  // The countdown only displays the server deadline; the phase changes when the server says so.
  const { isExpired } = useServerCountdown({ deadlineAt, onComplete: onExpire })
  const Icon = KIND_ICONS[kind]
  const syncing = Boolean(deadlineAt) && isExpired

  return (
    <header className="sticky top-0 z-10 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-border bg-card/95 px-4 py-3 shadow-xs backdrop-blur-sm">
      <div className="flex min-w-0 items-center gap-3">
        <div className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary">
          <Icon className="size-5" aria-hidden="true" />
        </div>
        <div className="min-w-0">
          <h2 className="font-fredoka text-lg leading-tight font-semibold text-foreground sm:text-xl">
            {stepLabel(step, totalSteps, kind)}
          </h2>
          <p className="text-xs text-muted-foreground sm:text-sm">
            {submitted ? 'Submitted — waiting for the other dinos' : STEP_COPY[kind].instruction}
          </p>
        </div>
      </div>

      <div className="flex items-center gap-2">
        {syncing ? (
          <span
            role="status"
            className="inline-flex items-center rounded-full border border-border bg-muted px-3 py-1.5 text-xs font-semibold text-muted-foreground"
          >
            Time's up — syncing…
          </span>
        ) : (
          <ServerCountdown deadlineAt={deadlineAt} size="md" />
        )}
      </div>
    </header>
  )
}
