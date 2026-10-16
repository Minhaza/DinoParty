import * as React from 'react'

import type { PlayerDto } from '@/api/types'
import { DinoAvatar } from '@/components/DinoAvatar'

import type { AlbumEntryView } from '../types'
import { TelephoneAlbumStep } from './TelephoneAlbumStep'
import { resolveContributor } from './albumCopy'
import { usePrefersReducedMotion } from './usePrefersReducedMotion'

export interface TelephoneAlbumViewerProps {
  ownerGuestId: string | null
  ownerNickname: string | null
  /** The entries to show, in step order; the caller passes only what may be seen. */
  entries: readonly AlbumEntryView[]
  players: readonly PlayerDto[]
  /** The step at the shown position: marked current and scrolled into view. */
  highlightStep?: number | null
  /** The step that just arrived: plays its entrance and drawing replay. */
  arrivingStep?: number | null
  /** Extra heading context, e.g. "Album 2 of 5". */
  subtitle?: string
  children?: React.ReactNode
}

/** One album as a vertical story: the prompt, then each drawing and description in order. */
export function TelephoneAlbumViewer({
  ownerGuestId,
  ownerNickname,
  entries,
  players,
  highlightStep = null,
  arrivingStep = null,
  subtitle,
  children,
}: TelephoneAlbumViewerProps) {
  const owner = resolveContributor(ownerGuestId, ownerNickname, players)
  const headingId = React.useId()
  const currentRef = React.useRef<HTMLElement | null>(null)
  const reducedMotion = usePrefersReducedMotion()

  React.useEffect(() => {
    currentRef.current?.scrollIntoView?.({
      block: 'nearest',
      behavior: reducedMotion ? 'auto' : 'smooth',
    })
  }, [highlightStep, arrivingStep, reducedMotion])

  return (
    <section aria-labelledby={headingId} className="flex min-w-0 flex-col gap-4">
      <header className="flex items-center gap-3">
        <DinoAvatar avatarKey={owner.avatarKey} size="md" />
        <div className="min-w-0">
          <h2
            id={headingId}
            className="truncate font-fredoka text-2xl font-semibold text-foreground sm:text-3xl"
          >
            {owner.nickname}&rsquo;s album
          </h2>
          {subtitle && <p className="text-sm font-medium text-muted-foreground">{subtitle}</p>}
        </div>
      </header>

      <ol className="flex flex-col gap-3">
        {entries.map((entry) => {
          const current = entry.step === highlightStep
          const arriving = entry.step === arrivingStep
          return (
            <li key={entry.step} className="min-w-0">
              <TelephoneAlbumStep
                ref={current ? currentRef : undefined}
                entry={entry}
                players={players}
                current={current}
                animate={arriving}
                replay={arriving}
              />
            </li>
          )
        })}
      </ol>
      {children}
    </section>
  )
}
