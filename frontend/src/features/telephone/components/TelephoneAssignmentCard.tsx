import { Loader2, Sparkles } from 'lucide-react'

import { DrawCanvas } from '@/features/drawing/DrawCanvas'

import type { TelephoneSource } from '../types'

export interface TelephoneAssignmentCardProps {
  /** `undefined` while the private task has not arrived yet. */
  source: TelephoneSource | null | undefined
}

/**
 * Shows the one entry the viewer must work from: the previous entry of the chain they hold
 * ([TP-8]). It never names an author and never shows anything earlier in the chain.
 */
export function TelephoneAssignmentCard({ source }: TelephoneAssignmentCardProps) {
  if (source === undefined || source === null) {
    return (
      <section
        aria-label="Your assignment"
        className="flex items-center justify-center gap-2 rounded-2xl border border-border bg-card p-6 text-sm text-muted-foreground shadow-xs"
      >
        <Loader2 className="size-4 motion-safe:animate-spin" aria-hidden="true" />
        Getting your assignment…
      </section>
    )
  }

  return (
    <section
      aria-label="Your assignment"
      className="flex min-w-0 flex-col gap-3 rounded-2xl border border-border bg-card p-4 shadow-xs sm:p-5"
    >
      <h3 className="text-xs font-bold tracking-wider text-muted-foreground uppercase">
        {source.kind === 'TEXT' ? 'Draw this' : 'Describe this'}
      </h3>

      {source.placeholder && (
        <p className="flex items-center gap-2 rounded-xl bg-accent/30 px-3 py-2 text-sm font-medium text-foreground">
          <Sparkles className="size-4 shrink-0 text-primary" aria-hidden="true" />
          Nothing to go on — go wild!
        </p>
      )}

      {source.kind === 'TEXT' ? (
        !source.placeholder && (
          <p className="font-fredoka text-xl leading-snug font-semibold break-words text-foreground sm:text-2xl">
            “{source.text}”
          </p>
        )
      ) : (
        <div className="aspect-[16/10] w-full">
          <DrawCanvas
            isDrawer={false}
            isDrawingPhase={false}
            tool="PENCIL"
            color="#2B2B2B"
            size={2}
            strokes={source.strokes}
            ariaLabel="The drawing you need to describe"
          />
        </div>
      )}
    </section>
  )
}
