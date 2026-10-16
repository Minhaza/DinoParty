import * as React from 'react'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, BookOpenCheck, Loader2, PlayCircle, RotateCw, StopCircle } from 'lucide-react'

import { ApiError } from '@/api/client'
import {
  ALBUM_RETRY_DELAY_MS,
  albumEntryView,
  getAlbum,
  getAlbums,
  shouldRetryAlbums,
  telephoneQueryKeys,
} from '@/api/telephone'
import type { PlayerDto } from '@/api/types'
import { DinoAvatar } from '@/components/DinoAvatar'
import { Button } from '@/components/ui/button'

import { TelephoneAlbumOverview } from './TelephoneAlbumOverview'
import { TelephoneAlbumTimeline } from './TelephoneAlbumTimeline'
import { TelephoneAlbumViewer } from './TelephoneAlbumViewer'
import { usePrefersReducedMotion } from './usePrefersReducedMotion'

/** Pace of a local album replay; a visual effect only, nothing is sent anywhere. */
const REPLAY_PAGE_MS = 1200

export interface TelephoneResultsScreenProps {
  sessionId: string | null
  endReason: string | null
  players: PlayerDto[]
  /** Pause between `RESULTS_NOT_READY` retries (tests shorten it). */
  retryDelayMs?: number
}

/**
 * The end of a Telephone game ([TP-11]): no scores, just the albums, read from the album endpoints
 * of this session. Opening one shows every page with a timeline; a replay turns the pages again.
 */
export function TelephoneResultsScreen({
  sessionId,
  endReason,
  players,
  retryDelayMs = ALBUM_RETRY_DELAY_MS,
}: TelephoneResultsScreenProps) {
  const [openChain, setOpenChain] = React.useState<number | null>(null)
  // A different session (rematch, another room) starts from the shelf again.
  const [prevSession, setPrevSession] = React.useState(sessionId)
  if (prevSession !== sessionId) {
    setPrevSession(sessionId)
    setOpenChain(null)
  }

  const albums = useQuery({
    queryKey: telephoneQueryKeys.albums(sessionId ?? ''),
    queryFn: ({ signal }) => getAlbums(sessionId ?? '', signal),
    enabled: sessionId !== null,
    retry: shouldRetryAlbums,
    retryDelay: retryDelayMs,
  })

  const early = endReason === 'INSUFFICIENT_PLAYERS'

  return (
    <section aria-labelledby="telephone-results-title" className="flex min-w-0 flex-col gap-5">
      <header className="flex flex-col items-center gap-3 rounded-3xl border border-border bg-card px-5 py-6 text-center shadow-[var(--ds-card)]">
        <span className="flex size-14 items-center justify-center rounded-2xl bg-game-phone/12 text-game-phone">
          <BookOpenCheck className="size-8" aria-hidden="true" />
        </span>
        <h2
          id="telephone-results-title"
          className="font-fredoka text-3xl font-semibold text-foreground"
        >
          {early ? 'The game ended early' : 'The albums are in!'}
        </h2>
        <p className="max-w-md text-sm text-muted-foreground">
          {early
            ? 'Too many dinos left for a reveal, so here is how far each album got.'
            : 'Flip through every chain and see how each message travelled.'}
        </p>
        {players.length > 0 && (
          <div className="flex flex-wrap justify-center -space-x-2" aria-label="Players">
            {players.map((p) => (
              <DinoAvatar
                key={p.guestId}
                avatarKey={p.avatarKey}
                size="sm"
                alt={p.nickname}
                title={p.nickname}
              />
            ))}
          </div>
        )}
      </header>

      {albums.isPending || (albums.isError && albums.failureReason && albums.isFetching) ? (
        <p
          role="status"
          className="flex items-center justify-center gap-2 py-6 text-sm text-muted-foreground"
        >
          <Loader2 className="size-4 motion-safe:animate-spin" aria-hidden="true" />
          Gathering the albums…
        </p>
      ) : albums.isError ? (
        <AlbumError error={albums.error} onRetry={() => void albums.refetch()} />
      ) : albums.data.albums.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-border bg-card/70 px-4 py-8 text-center text-sm text-muted-foreground">
          No albums were kept from this game.
        </p>
      ) : openChain === null ? (
        <TelephoneAlbumOverview
          albums={albums.data.albums}
          players={players}
          onOpen={setOpenChain}
        />
      ) : (
        <OpenAlbum
          key={`${sessionId}:${openChain}`}
          sessionId={sessionId ?? ''}
          chainIndex={openChain}
          players={players}
          retryDelayMs={retryDelayMs}
          onBack={() => setOpenChain(null)}
        />
      )}
    </section>
  )
}

function AlbumError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const forbidden = error instanceof ApiError && error.status === 403
  return (
    <div
      role="alert"
      className="flex flex-col items-center gap-3 rounded-2xl border border-destructive/30 bg-destructive/5 px-4 py-6 text-center text-sm"
    >
      <p className="font-medium text-foreground">
        {forbidden
          ? 'You are no longer part of this room, so its albums are closed to you.'
          : 'The albums could not be loaded.'}
      </p>
      {!forbidden && (
        <Button variant="outline" size="sm" className="min-h-11" onClick={onRetry}>
          <RotateCw className="size-4" aria-hidden="true" />
          Try again
        </Button>
      )}
    </div>
  )
}

interface OpenAlbumProps {
  sessionId: string
  chainIndex: number
  players: PlayerDto[]
  retryDelayMs: number
  onBack: () => void
}

function OpenAlbum({ sessionId, chainIndex, players, retryDelayMs, onBack }: OpenAlbumProps) {
  const reducedMotion = usePrefersReducedMotion()
  const album = useQuery({
    queryKey: telephoneQueryKeys.album(sessionId, chainIndex),
    queryFn: ({ signal }) => getAlbum(sessionId, chainIndex, signal),
    retry: shouldRetryAlbums,
    retryDelay: retryDelayMs,
  })
  const entries = React.useMemo(
    () => (album.data ? album.data.entries.map(albumEntryView) : []),
    [album.data],
  )
  /** Pages shown during a replay; null shows the whole album. */
  const [replayCount, setReplayCount] = React.useState<number | null>(null)
  const [selected, setSelected] = React.useState<number | null>(null)

  // The replay is over once every page is shown; nothing needs resetting for that.
  const replaying = replayCount !== null && replayCount < entries.length

  React.useEffect(() => {
    if (!replaying) return
    const timer = setTimeout(
      () => setReplayCount((n) => (n === null ? null : n + 1)),
      REPLAY_PAGE_MS,
    )
    return () => clearTimeout(timer)
  }, [replaying, replayCount])

  const startReplay = () => {
    setSelected(null)
    setReplayCount(reducedMotion ? null : 1)
  }

  const shown = replaying ? entries.slice(0, replayCount ?? 0) : entries
  const arriving = replaying && replayCount !== null ? replayCount - 1 : null

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Button variant="ghost" className="min-h-11" onClick={onBack}>
          <ArrowLeft className="size-4" aria-hidden="true" />
          All albums
        </Button>
        {album.data &&
          (!replaying ? (
            <Button variant="secondary" className="min-h-11" onClick={startReplay}>
              <PlayCircle className="size-4" aria-hidden="true" />
              Replay album
            </Button>
          ) : (
            <Button variant="secondary" className="min-h-11" onClick={() => setReplayCount(null)}>
              <StopCircle className="size-4" aria-hidden="true" />
              Show whole album
            </Button>
          ))}
      </div>

      {album.isPending ? (
        <p
          role="status"
          className="flex items-center justify-center gap-2 py-6 text-sm text-muted-foreground"
        >
          <Loader2 className="size-4 motion-safe:animate-spin" aria-hidden="true" />
          Opening the album…
        </p>
      ) : album.isError ? (
        <AlbumError error={album.error} onRetry={() => void album.refetch()} />
      ) : (
        <TelephoneAlbumViewer
          ownerGuestId={album.data.ownerGuestId}
          ownerNickname={entries[0]?.step === 0 ? entries[0].authorNickname : null}
          entries={shown}
          players={players}
          highlightStep={arriving ?? selected}
          arrivingStep={arriving}
          subtitle={album.data.complete ? undefined : 'Unfinished: the game ended early.'}
        >
          {!replaying && (
            <TelephoneAlbumTimeline
              entries={entries}
              players={players}
              selectedStep={selected}
              onSelect={setSelected}
            />
          )}
        </TelephoneAlbumViewer>
      )}
    </div>
  )
}
