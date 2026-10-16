import { ChevronLeft, ChevronRight, Flag, Radio, SkipBack, SkipForward } from 'lucide-react'

import { Button } from '@/components/ui/button'

export interface TelephoneRevealControlsProps {
  isHost: boolean
  /** Host: the shared cursor can step back / the move in flight is unanswered / no socket. */
  canHostPrev: boolean
  hostBusy: boolean
  offline: boolean
  /** NEXT on the last entry finishes the game. */
  atLastEntry: boolean
  onHostPrev: () => void
  onHostNext: () => void
  /** Local browsing of revealed entries (never sent to the server). */
  canBrowsePrev: boolean
  canBrowseNext: boolean
  canPrevAlbum: boolean
  canNextAlbum: boolean
  browsing: boolean
  onBrowsePrev: () => void
  onBrowseNext: () => void
  onPrevAlbum: () => void
  onNextAlbum: () => void
  onFollowLive: () => void
}

/**
 * Two rows: the host turns the shared pages ([TP-6]; the server rejects anyone else), and every
 * player may leaf back and forth through what has already been revealed.
 */
export function TelephoneRevealControls(props: TelephoneRevealControlsProps) {
  const { isHost, canHostPrev, hostBusy, offline, atLastEntry, onHostPrev, onHostNext, browsing } =
    props

  return (
    <div className="sticky bottom-2 z-10 flex flex-col gap-2 rounded-2xl border border-border bg-card/95 p-3 shadow-[var(--ds-card)] backdrop-blur sm:static sm:bg-card">
      {isHost ? (
        <div className="flex flex-wrap items-center justify-center gap-2">
          <Button
            variant="outline"
            className="min-h-11"
            onClick={onHostPrev}
            disabled={!canHostPrev || hostBusy || offline}
          >
            <SkipBack className="size-4" aria-hidden="true" />
            Previous
          </Button>
          <Button
            variant={atLastEntry ? 'default' : 'accent'}
            className="min-h-11 min-w-44"
            onClick={onHostNext}
            disabled={hostBusy || offline}
          >
            {atLastEntry ? (
              <>
                <Flag className="size-4" aria-hidden="true" />
                Finish &amp; see albums
              </>
            ) : (
              <>
                Reveal next
                <SkipForward className="size-4" aria-hidden="true" />
              </>
            )}
          </Button>
        </div>
      ) : (
        <p className="text-center text-sm font-medium text-muted-foreground">
          The host is turning the pages — sit back and enjoy.
        </p>
      )}

      <div className="flex flex-wrap items-center justify-center gap-1.5 border-t border-border/70 pt-2">
        <Button
          variant="ghost"
          size="sm"
          className="min-h-11 sm:min-h-9"
          onClick={props.onPrevAlbum}
          disabled={!props.canPrevAlbum}
        >
          Previous album
        </Button>
        <Button
          variant="ghost"
          size="sm"
          className="min-h-11 sm:min-h-9"
          onClick={props.onBrowsePrev}
          disabled={!props.canBrowsePrev}
          aria-label="Back"
        >
          <ChevronLeft className="size-4" aria-hidden="true" />
          Back
        </Button>
        <Button
          variant="ghost"
          size="sm"
          className="min-h-11 sm:min-h-9"
          onClick={props.onBrowseNext}
          disabled={!props.canBrowseNext}
          aria-label="Forward"
        >
          Forward
          <ChevronRight className="size-4" aria-hidden="true" />
        </Button>
        <Button
          variant="ghost"
          size="sm"
          className="min-h-11 sm:min-h-9"
          onClick={props.onNextAlbum}
          disabled={!props.canNextAlbum}
        >
          Next album
        </Button>
        {browsing && (
          <Button
            variant="secondary"
            size="sm"
            className="min-h-11 sm:min-h-9"
            onClick={props.onFollowLive}
          >
            <Radio className="size-4" aria-hidden="true" />
            Back to live
          </Button>
        )}
      </div>
    </div>
  )
}
