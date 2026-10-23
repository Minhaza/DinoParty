import * as React from 'react'
import { CheckCircle2, ImageOff, Loader2, UserCheck, Vote } from 'lucide-react'

import { DinoAvatar } from '@/components/DinoAvatar'
import { Button } from '@/components/ui/button'
import { ServerCountdown } from '@/features/session/ServerCountdown'
import { TelephoneDrawingView } from '@/features/telephone/reveal/TelephoneDrawingView'
import { cn } from '@/lib/utils'

import type { SketchyDrawing } from './types'

export interface GalleryPlayer {
  guestId: string
  nickname: string
  avatarKey?: string | null
}

export interface GalleryGridProps {
  drawings: SketchyDrawing[]
  players: GalleryPlayer[]
  currentGuestId: string | null | undefined
  myVote: string | null
  pendingVote: { target: string | null } | null
  votedGuestIds: string[]
  enabled: boolean
  error?: string | null
  onVote: (targetGuestId: string | null) => void
  deadlineAt: string | null
}

export function GalleryGrid({
  drawings,
  players,
  currentGuestId,
  myVote,
  pendingVote,
  votedGuestIds,
  enabled,
  error,
  onVote,
  deadlineAt,
}: GalleryGridProps) {
  const groupName = React.useId()
  const hintId = React.useId()

  const playerMap = React.useMemo(() => new Map(players.map((p) => [p.guestId, p])), [players])

  const selected = pendingVote ? pendingVote.target : myVote
  const votedSet = new Set(votedGuestIds)
  const selectedPlayer = selected ? playerMap.get(selected) : null

  return (
    <fieldset
      disabled={!enabled}
      aria-describedby={hintId}
      className="flex flex-col gap-4 w-full animate-in fade-in duration-200"
    >
      {/* Header with Title and Countdown */}
      <div className="flex flex-wrap items-center justify-between gap-3 p-4 rounded-2xl border border-border bg-card shadow-xs">
        <div>
          <legend className="flex items-center gap-2 font-fredoka text-xl font-bold">
            <Vote aria-hidden="true" className="size-5 text-primary" />
            The Gallery · Spot the Impostor!
          </legend>
          <p id={hintId} className="text-sm text-muted-foreground mt-0.5">
            {enabled
              ? 'Vote for the drawing that looks like the artist did not know the secret word.'
              : 'Only active players can vote. Watch the votes come in!'}
          </p>
        </div>
        <ServerCountdown deadlineAt={deadlineAt} size="md" />
      </div>

      {/* Gallery Cards Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        {drawings.map((d) => {
          const author = playerMap.get(d.guestId)
          const isMe = d.guestId === currentGuestId
          const isSelected = selected === d.guestId
          const hasVoted = votedSet.has(d.guestId)

          return (
            <label
              key={d.guestId}
              className={cn(
                'relative flex min-w-0 flex-col rounded-2xl border-2 overflow-hidden transition-all bg-card',
                isMe
                  ? 'border-border/60 opacity-85 cursor-not-allowed bg-muted/20'
                  : 'cursor-pointer hover:border-primary/50 focus-within:ring-2 focus-within:ring-primary',
                isSelected && 'border-primary ring-2 ring-primary bg-primary/5',
                !enabled && !isMe && 'cursor-not-allowed opacity-80',
              )}
            >
              {/* Card Header: Author info + Radio input / indicators */}
              <div className="flex items-center justify-between gap-2 p-3 border-b border-border/60 bg-muted/40">
                <div className="flex items-center gap-2 truncate">
                  <DinoAvatar avatarKey={author?.avatarKey} size="xs" alt="" />
                  <span className="font-fredoka font-semibold truncate text-sm">
                    {author?.nickname ?? 'A dino'}
                    {isMe && (
                      <span className="ml-1 text-xs text-muted-foreground font-normal">(You)</span>
                    )}
                  </span>
                </div>

                <div className="flex items-center gap-2">
                  {hasVoted && (
                    <span
                      title="Voted"
                      className="inline-flex items-center gap-1 text-xs font-semibold text-emerald-600 bg-emerald-500/10 px-2 py-0.5 rounded-full"
                    >
                      <UserCheck className="size-3.5" />
                      Voted
                    </span>
                  )}

                  {!isMe && (
                    <input
                      type="radio"
                      name={groupName}
                      value={d.guestId}
                      checked={isSelected}
                      disabled={!enabled}
                      onChange={() => onVote(d.guestId)}
                      className="size-4 accent-primary"
                    />
                  )}
                </div>
              </div>

              {/* Drawing Preview or Empty placeholder: a fixed 16:10 view that scales with the card */}
              {d.empty || d.strokes.length === 0 ? (
                <div className="flex aspect-[16/10] w-full flex-col items-center justify-center gap-1.5 bg-white p-4 text-center text-muted-foreground">
                  <ImageOff className="size-8 stroke-1" aria-hidden="true" />
                  <span className="text-xs font-medium">No drawing submitted</span>
                </div>
              ) : (
                <TelephoneDrawingView
                  strokes={d.strokes}
                  label={`Drawing by ${author?.nickname ?? 'player'}`}
                  compact
                  className="rounded-none ring-0"
                />
              )}
            </label>
          )
        })}
      </div>

      {/* Voting status bar and withdraw button */}
      <div className="flex flex-wrap items-center justify-between gap-3 p-4 rounded-2xl border border-border bg-card shadow-xs">
        <p aria-live="polite" className="text-sm font-medium">
          {pendingVote ? (
            <span className="inline-flex items-center gap-1.5 text-muted-foreground">
              <Loader2 className="size-4 animate-spin motion-reduce:animate-none" />
              Sending your vote...
            </span>
          ) : selectedPlayer ? (
            <span className="inline-flex items-center gap-1.5 text-primary font-semibold">
              <CheckCircle2 className="size-4" />
              You voted for {selectedPlayer.nickname}.
            </span>
          ) : enabled ? (
            <span className="text-muted-foreground">
              You have not voted yet. Select a drawing above!
            </span>
          ) : null}
        </p>

        {enabled && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={selected === null}
            onClick={() => onVote(null)}
          >
            Withdraw my vote
          </Button>
        )}
      </div>

      {error && (
        <p role="alert" className="text-sm font-medium text-destructive text-center">
          {error}
        </p>
      )}
    </fieldset>
  )
}
