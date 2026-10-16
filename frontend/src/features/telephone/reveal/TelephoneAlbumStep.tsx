import * as React from 'react'
import { Clock, DoorOpen, PenLine, Pencil, ScrollText, Sparkles, Wand2 } from 'lucide-react'

import type { PlayerDto } from '@/api/types'
import { cn } from '@/lib/utils'

import type { AlbumEntryView } from '../types'
import { entryKindLabel, resolveContributor } from './albumCopy'
import { TelephoneContributorBadge } from './TelephoneContributorBadge'
import { TelephoneDrawingView } from './TelephoneDrawingView'

const KIND_ICON = { Prompt: ScrollText, Drawing: Pencil, Description: PenLine } as const

interface Badge {
  label: string
  icon: React.ComponentType<{ className?: string }>
}

/** The [TP-5] badges: how the server filled a cell nobody (fully) filled in. */
function badgesOf(entry: AlbumEntryView): Badge[] {
  const badges: Badge[] = []
  if (entry.fill === 'AUTO_PROMPT') badges.push({ label: 'Dino-picked prompt', icon: Wand2 })
  if (entry.fill === 'LEFT_PLAYER') badges.push({ label: 'Left the game', icon: DoorOpen })
  if (entry.placeholder) {
    badges.push({
      label: entry.kind === 'DRAWING' ? 'Blank page' : 'Nothing written',
      icon: Sparkles,
    })
  } else if (entry.timedOut) {
    badges.push({ label: 'Out of time', icon: Clock })
  }
  return badges
}

export interface TelephoneAlbumStepProps {
  entry: AlbumEntryView
  players: readonly PlayerDto[]
  /** The entry at the shown position. */
  current?: boolean
  /** Play the arrival effect (the entry was just revealed). */
  animate?: boolean
  /** Replay the drawing's strokes once. */
  replay?: boolean
}

/**
 * One contribution of an album. User text is untrusted: it is rendered as a text node only, with
 * the writing direction left to the text itself, and long unbroken strings wrap.
 */
export const TelephoneAlbumStep = React.forwardRef<HTMLElement, TelephoneAlbumStepProps>(
  function TelephoneAlbumStep(
    { entry, players, current = false, animate = false, replay = false },
    ref,
  ) {
    const kind = entryKindLabel(entry)
    const KindIcon = KIND_ICON[kind as keyof typeof KIND_ICON]
    const author = resolveContributor(entry.authorGuestId, entry.authorNickname, players)
    const badges = badgesOf(entry)

    return (
      <article
        ref={ref}
        aria-label={`Step ${entry.step + 1}, ${kind.toLowerCase()} by ${author.nickname}`}
        aria-current={current ? 'step' : undefined}
        tabIndex={-1}
        className={cn(
          'relative flex min-w-0 flex-col gap-3 rounded-2xl border bg-card p-4 shadow-[var(--ds-card)] outline-none sm:p-5',
          current ? 'border-game-phone/50 ring-2 ring-game-phone/25' : 'border-border',
          animate &&
            'motion-safe:animate-in motion-safe:fade-in motion-safe:zoom-in-95 motion-safe:slide-in-from-bottom-3 motion-safe:duration-300',
        )}
      >
        <header className="flex flex-wrap items-center justify-between gap-2">
          <span className="inline-flex items-center gap-2 text-xs font-bold tracking-wider text-muted-foreground uppercase">
            <span className="inline-flex size-6 items-center justify-center rounded-full bg-game-phone/12 text-[0.7rem] text-foreground tabular-nums">
              {entry.step + 1}
            </span>
            <KindIcon className="size-3.5" aria-hidden="true" />
            {kind}
          </span>
          <TelephoneContributorBadge
            guestId={entry.authorGuestId}
            nickname={entry.authorNickname}
            players={players}
            className="text-sm"
          />
        </header>

        {entry.kind === 'TEXT' ? (
          <p
            dir="auto"
            className={cn(
              'font-fredoka text-xl leading-snug font-semibold break-words whitespace-pre-wrap text-foreground [overflow-wrap:anywhere] sm:text-2xl',
              entry.placeholder && 'text-muted-foreground italic',
            )}
          >
            {entry.text}
          </p>
        ) : (
          <TelephoneDrawingView
            strokes={entry.strokes}
            label={`Drawing by ${author.nickname}`}
            replay={replay}
          />
        )}

        {badges.length > 0 && (
          <ul className="flex flex-wrap gap-1.5" aria-label="Notes">
            {badges.map(({ label, icon: Icon }) => (
              <li
                key={label}
                className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-1 text-xs font-semibold text-muted-foreground"
              >
                <Icon className="size-3.5" aria-hidden="true" />
                {label}
              </li>
            ))}
          </ul>
        )}
      </article>
    )
  },
)
