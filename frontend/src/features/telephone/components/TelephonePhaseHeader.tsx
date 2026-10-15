import { MessageSquareText, PencilLine, ScrollText } from 'lucide-react'

import { ServerCountdown } from '@/features/session/ServerCountdown'
import { useServerCountdown } from '@/features/session/useServerCountdown'

import { STEP_COPY, stepCounter } from '../copy'
import type { TelephoneKind, TelephoneSource } from '../types'

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
  /** DRAW step only: the sentence to draw (`undefined` while the private task is in flight). */
  drawSource?: TelephoneSource | null
  showDrawSource?: boolean
  /** Called once when the local countdown reaches zero (display only; the server ends the step). */
  onExpire?: () => void
}

export function TelephonePhaseHeader({
  kind,
  step,
  totalSteps,
  deadlineAt,
  drawSource,
  showDrawSource = false,
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
        <h2 className="font-fredoka text-lg leading-tight font-semibold text-foreground sm:text-xl">
          {stepCounter(step, totalSteps)}
        </h2>
      </div>

      {showDrawSource && (
        <section
          aria-label="Your assignment"
          className="min-w-0 flex-1 basis-full text-center sm:basis-0"
        >
          <p className="text-xs font-bold tracking-wider text-muted-foreground uppercase">
            {STEP_COPY[kind].title}
          </p>
          {drawSource === undefined || drawSource === null ? (
            <p className="text-sm text-muted-foreground">Getting your assignment…</p>
          ) : drawSource.placeholder ? (
            <p className="text-sm font-medium text-foreground">Nothing to go on — go wild!</p>
          ) : drawSource.kind === 'TEXT' ? (
            <p className="font-fredoka text-xl leading-snug font-semibold break-words text-foreground sm:text-2xl">
              “{drawSource.text}”
            </p>
          ) : null}
        </section>
      )}

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
