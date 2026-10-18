import { BookOpen } from 'lucide-react'

import type { PlayerDto } from '@/api/types'
import { DinoAvatar } from '@/components/DinoAvatar'
import { Button } from '@/components/ui/button'

import type { AlbumSummaryDto } from '../types'
import { resolveContributor } from './albumCopy'

export interface TelephoneAlbumOverviewProps {
  albums: readonly AlbumSummaryDto[]
  players: readonly PlayerDto[]
  onOpen: (chainIndex: number) => void
}

/** Every album of the game as a shelf of cards; no content until one is opened. */
export function TelephoneAlbumOverview({ albums, players, onOpen }: TelephoneAlbumOverviewProps) {
  return (
    <ul aria-label="Albums" className="grid grid-cols-1 gap-3 sm:grid-cols-2">
      {albums.map((album) => {
        const owner = resolveContributor(album.ownerGuestId, null, players)
        const title = `${owner.nickname}’s album`
        return (
          <li
            key={album.chainIndex}
            className="flex min-w-0 flex-col gap-3 rounded-2xl border border-border bg-card p-4 shadow-[var(--ds-card)]"
          >
            <div className="flex min-w-0 items-center gap-3">
              <DinoAvatar avatarKey={owner.avatarKey} size="lg" />
              <div className="min-w-0">
                <p className="truncate font-fredoka text-xl font-semibold text-foreground">
                  {title}
                </p>
                <p className="text-sm text-muted-foreground">
                  {album.entryCount} {album.entryCount === 1 ? 'page' : 'pages'}
                </p>
              </div>
            </div>
            {(!album.complete || album.hasPlaceholders) && (
              <ul className="flex flex-wrap gap-1.5" aria-label="Notes">
                {!album.complete && (
                  <li className="rounded-full bg-warning/20 px-2.5 py-1 text-xs font-semibold text-warning-foreground">
                    Unfinished
                  </li>
                )}
                {album.hasPlaceholders && (
                  <li className="rounded-full bg-muted px-2.5 py-1 text-xs font-semibold text-muted-foreground">
                    A blank page or two
                  </li>
                )}
              </ul>
            )}
            <Button
              variant="outline"
              className="mt-auto min-h-11"
              onClick={() => onOpen(album.chainIndex)}
              aria-label={`Open ${title}`}
            >
              <BookOpen className="size-4" aria-hidden="true" />
              Open album
            </Button>
          </li>
        )
      })}
    </ul>
  )
}
