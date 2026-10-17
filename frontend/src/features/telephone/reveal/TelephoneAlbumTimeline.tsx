import type { PlayerDto } from '@/api/types'
import { DinoAvatar } from '@/components/DinoAvatar'
import { cn } from '@/lib/utils'

import type { AlbumEntryView } from '../types'
import { entryKindLabel, resolveContributor } from './albumCopy'
import { TelephoneDrawingView } from './TelephoneDrawingView'

export interface TelephoneAlbumTimelineProps {
  /** Only contributions the viewer may see; nothing is listed for steps not revealed yet. */
  entries: readonly AlbumEntryView[]
  players: readonly PlayerDto[]
  selectedStep: number | null
  onSelect: (step: number) => void
}

/** A compact strip of an album's contributions to jump to any one of them. */
export function TelephoneAlbumTimeline({
  entries,
  players,
  selectedStep,
  onSelect,
}: TelephoneAlbumTimelineProps) {
  return (
    <nav aria-label="Album timeline" className="min-w-0">
      <ol className="flex snap-x gap-2 overflow-x-auto pb-2">
        {entries.map((entry) => {
          const kind = entryKindLabel(entry)
          const who = resolveContributor(entry.authorGuestId, entry.authorNickname, players)
          const selected = entry.step === selectedStep
          return (
            <li key={entry.step} className="w-36 shrink-0 snap-start sm:w-40">
              <button
                type="button"
                onClick={() => onSelect(entry.step)}
                aria-current={selected ? 'step' : undefined}
                aria-label={`Step ${entry.step + 1}: ${kind.toLowerCase()} by ${who.nickname}`}
                className={cn(
                  'flex min-h-11 w-full flex-col gap-1.5 rounded-xl border bg-card p-2 text-left transition-colors outline-none hover:border-game-phone/60 focus-visible:ring-[3px] focus-visible:ring-ring/60',
                  selected ? 'border-game-phone ring-2 ring-game-phone/25' : 'border-border',
                )}
              >
                <span className="flex items-center justify-between gap-1 text-[0.7rem] font-bold tracking-wide text-muted-foreground uppercase">
                  <span>
                    {entry.step + 1} · {kind}
                  </span>
                  <DinoAvatar avatarKey={who.avatarKey} size="xs" border={false} aria-hidden />
                </span>
                {entry.kind === 'DRAWING' ? (
                  <TelephoneDrawingView strokes={entry.strokes} label="" compact />
                ) : (
                  <span
                    dir="auto"
                    className="line-clamp-3 text-sm leading-snug font-semibold break-words text-foreground [overflow-wrap:anywhere]"
                  >
                    {entry.text}
                  </span>
                )}
              </button>
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
