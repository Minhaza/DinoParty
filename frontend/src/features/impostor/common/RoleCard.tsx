import { Feather } from 'lucide-react'

import { cn } from '@/lib/utils'

import { ROLE_COPY } from './copy'
import type { ImpostorRoleInfo } from './types'

export interface RoleCardProps {
  /** The viewer's role; `null` for spectators. */
  role: ImpostorRoleInfo | null
  /** Small reminder shown next to the canvas after the reveal. */
  compact?: boolean
  className?: string
}

/**
 * The viewer's secret ([DI-4]). Artists, the impostor and spectators get the very same markup, size,
 * colours, icon and (reduced-motion safe) fade-in: only the words differ, so a glance at someone's
 * screen tells no more than reading it would.
 */
export function RoleCard({ role, compact = false, className }: RoleCardProps) {
  const copy =
    role === null
      ? ROLE_COPY.spectator
      : role.role === 'ARTIST'
        ? ROLE_COPY.artist
        : ROLE_COPY.impostor
  const secret =
    role?.role === 'ARTIST'
      ? (role.word ?? '')
      : role === null
        ? ROLE_COPY.spectator.secret
        : ROLE_COPY.impostor.secret
  const category = role?.category.label ?? ROLE_COPY.hiddenCategory

  return (
    <section
      aria-label={ROLE_COPY.label}
      data-testid="role-card"
      className={cn(
        'w-full rounded-2xl border border-border bg-card text-card-foreground shadow-xs',
        'animate-in fade-in duration-300 motion-reduce:animate-none',
        compact ? 'p-3' : 'p-5 sm:p-6',
        className,
      )}
    >
      <div className={cn('flex items-center', compact ? 'gap-2' : 'gap-3')}>
        <span
          aria-hidden="true"
          className={cn(
            'flex shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary',
            compact ? 'size-8' : 'size-11',
          )}
        >
          <Feather className={compact ? 'size-4' : 'size-6'} />
        </span>
        <h2 className={cn('font-fredoka font-semibold', compact ? 'text-base' : 'text-xl')}>
          {copy.title}
        </h2>
      </div>

      <dl
        className={cn(
          'grid grid-cols-1 sm:grid-cols-2',
          compact ? 'mt-2 gap-2 text-sm' : 'mt-4 gap-3',
        )}
      >
        <div className="rounded-xl bg-muted/50 px-3 py-2">
          <dt className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {ROLE_COPY.categoryLabel}
          </dt>
          <dd className={cn('font-bold break-words', compact ? 'text-base' : 'text-lg')}>
            {category}
          </dd>
        </div>
        <div className="rounded-xl bg-muted/50 px-3 py-2">
          <dt className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {copy.secretLabel}
          </dt>
          <dd className={cn('font-bold break-words', compact ? 'text-base' : 'text-lg')}>
            {secret}
          </dd>
        </div>
      </dl>

      <p className={cn('text-muted-foreground', compact ? 'sr-only' : 'mt-4 text-sm')}>
        {copy.hint}
      </p>
    </section>
  )
}
