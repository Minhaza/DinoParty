import * as React from 'react'
import { CheckCircle2, Loader2, Vote } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { DinoAvatar } from '@/components/DinoAvatar'
import { cn } from '@/lib/utils'

export interface VoteCandidate {
  guestId: string
  nickname: string
  avatarKey?: string | null
}

export interface VotePanelProps {
  /** Players who may receive a vote (not `LEFT`); the viewer is removed here. */
  candidates: VoteCandidate[]
  currentGuestId: string | null | undefined
  /** Server-confirmed vote of the viewer. */
  myVote: string | null
  /** Vote sent and not yet acknowledged. */
  pendingVote: { target: string | null } | null
  votedGuestIds: string[]
  /** Only in the voting phase, and only for players in the game. */
  enabled: boolean
  error?: string | null
  onVote: (targetGuestId: string | null) => void
}

/**
 * Secret vote ([DI-9]) as a native radio group, so arrow keys move between players and Space picks
 * one. Others only see who has voted. A vote can be changed or withdrawn until the phase ends.
 */
export function VotePanel({
  candidates,
  currentGuestId,
  myVote,
  pendingVote,
  votedGuestIds,
  enabled,
  error,
  onVote,
}: VotePanelProps) {
  const groupName = React.useId()
  const hintId = React.useId()
  const others = candidates.filter((c) => c.guestId !== currentGuestId)
  const selected = pendingVote ? pendingVote.target : myVote
  const voted = new Set(votedGuestIds)
  const selectedName = others.find((c) => c.guestId === myVote)?.nickname

  return (
    <fieldset
      disabled={!enabled}
      aria-describedby={hintId}
      className="flex flex-col gap-3 rounded-2xl border border-border bg-card p-4 shadow-xs disabled:opacity-70"
    >
      <legend className="flex items-center gap-2 px-1 font-fredoka text-lg font-semibold">
        <Vote aria-hidden="true" className="size-5 text-primary" />
        Who is the impostor?
      </legend>
      <p id={hintId} className="text-sm text-muted-foreground">
        {enabled
          ? 'Your vote is secret — others only see that you voted. You can change it until time runs out.'
          : 'Voting opens after the drawing laps.'}
      </p>

      <ul className="flex flex-col gap-1.5">
        {others.map((c) => {
          const checked = selected === c.guestId
          return (
            <li key={c.guestId}>
              <label
                className={cn(
                  'flex cursor-pointer items-center gap-3 rounded-xl border px-3 py-2 transition-colors',
                  'has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-primary',
                  checked ? 'border-primary bg-primary/10' : 'border-border/70 hover:bg-muted/50',
                  !enabled && 'cursor-not-allowed',
                )}
              >
                <input
                  type="radio"
                  name={groupName}
                  value={c.guestId}
                  checked={checked}
                  onChange={() => onVote(c.guestId)}
                  className="size-4 accent-primary"
                />
                <DinoAvatar avatarKey={c.avatarKey} size="xs" alt="" />
                <span className="flex-1 truncate font-semibold">{c.nickname}</span>
                {voted.has(c.guestId) && (
                  <span className="inline-flex items-center gap-1 text-xs font-semibold text-muted-foreground">
                    <CheckCircle2 aria-hidden="true" className="size-4" />
                    voted
                  </span>
                )}
              </label>
            </li>
          )
        })}
      </ul>

      <div className="flex flex-wrap items-center justify-between gap-2">
        <p aria-live="polite" className="text-sm">
          {pendingVote ? (
            <span className="inline-flex items-center gap-1.5 text-muted-foreground">
              <Loader2
                aria-hidden="true"
                className="size-4 animate-spin motion-reduce:animate-none"
              />
              Sending your vote…
            </span>
          ) : selectedName ? (
            <>
              You voted for <strong>{selectedName}</strong>.
            </>
          ) : (
            <span className="text-muted-foreground">You have not voted.</span>
          )}
        </p>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={!enabled || selected === null}
          onClick={() => onVote(null)}
        >
          Withdraw my vote
        </Button>
      </div>

      {error && (
        <p role="alert" className="text-sm font-medium text-destructive">
          {error}
        </p>
      )}
    </fieldset>
  )
}
