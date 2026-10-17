import {
  ChevronLeft,
  ChevronRight,
  Flag,
  Pause,
  Play,
  Radio,
  SkipBack,
  SkipForward,
  Timer,
} from 'lucide-react'

import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import { AUTO_REVEAL_SPEEDS, type AutoRevealSpeed } from '../types'

export type { AutoRevealSpeed }

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
  /** Auto reveal option & speed for host */
  autoReveal?: boolean
  autoSpeed?: number
  autoSecondsLeft?: number
  speeds?: readonly number[]
  onToggleAutoReveal?: () => void
  onSpeedChange?: (speed: number) => void
}

/**
 * Two rows: the host turns the shared pages ([TP-6]; the server rejects anyone else), and every
 * player may leaf back and forth through what has already been revealed.
 */
export function TelephoneRevealControls(props: TelephoneRevealControlsProps) {
  const {
    isHost,
    canHostPrev,
    hostBusy,
    offline,
    atLastEntry,
    onHostPrev,
    onHostNext,
    browsing,
    autoReveal = false,
    autoSpeed = 5,
    autoSecondsLeft = 5,
    speeds = AUTO_REVEAL_SPEEDS,
    onToggleAutoReveal,
    onSpeedChange,
  } = props

  return (
    <div className="flex flex-col gap-2 rounded-2xl border border-border bg-card p-3 shadow-[var(--ds-card)]">
      {isHost ? (
        <div className="flex flex-col gap-2">
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
            {!atLastEntry && onToggleAutoReveal && (
              <Button
                variant={autoReveal ? 'default' : 'outline'}
                className={cn(
                  'min-h-11',
                  autoReveal &&
                    'border-game-phone bg-game-phone/15 text-foreground hover:bg-game-phone/25',
                )}
                onClick={onToggleAutoReveal}
                disabled={hostBusy || offline}
                aria-label={autoReveal ? 'Pause auto reveal' : 'Start auto reveal'}
              >
                {autoReveal ? (
                  <>
                    <Pause className="size-4 text-game-phone" aria-hidden="true" />
                    Auto ({autoSecondsLeft}s)
                  </>
                ) : (
                  <>
                    <Play className="size-4" aria-hidden="true" />
                    Auto reveal
                  </>
                )}
              </Button>
            )}
          </div>

          {!atLastEntry && onSpeedChange && speeds.length > 0 && (
            <div className="flex flex-wrap items-center justify-center gap-2 text-xs">
              <span className="flex items-center gap-1 font-medium text-muted-foreground">
                <Timer className="size-3.5" aria-hidden="true" />
                Speed:
              </span>
              <div
                className="inline-flex rounded-lg border border-border/60 bg-muted/50 p-0.5"
                role="group"
                aria-label="Auto reveal speed"
              >
                {speeds.map((s) => (
                  <button
                    key={s}
                    type="button"
                    onClick={() => onSpeedChange(s)}
                    className={cn(
                      'rounded-md px-2.5 py-1 font-semibold transition-all',
                      autoSpeed === s
                        ? 'bg-card text-foreground shadow-xs ring-1 ring-border/60'
                        : 'text-muted-foreground hover:text-foreground',
                    )}
                    aria-pressed={autoSpeed === s}
                  >
                    {s}s
                  </button>
                ))}
              </div>
            </div>
          )}
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
