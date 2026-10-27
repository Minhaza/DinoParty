import { CheckCircle2, Clock } from 'lucide-react'

import { DinoAvatar } from '@/components/DinoAvatar'
import { ServerCountdown } from '@/features/session/ServerCountdown'
import { TelephoneDrawingView } from '@/features/telephone/reveal/TelephoneDrawingView'
import type { Stroke } from '@/features/drawing/types'
import { cn } from '@/lib/utils'

export interface WaitingPlayer {
  guestId: string
  nickname: string
  avatarKey?: string | null
}

export interface SketchWaitingProps {
  strokes: Stroke[]
  submittedGuestIds: string[]
  players: WaitingPlayer[]
  deadlineAt: string | null
  /** A spectator has no sketch of their own: no "submitted" banner and no preview. */
  spectating?: boolean
}

export function SketchWaiting({
  strokes,
  submittedGuestIds,
  players,
  deadlineAt,
  spectating = false,
}: SketchWaitingProps) {
  const submittedSet = new Set(submittedGuestIds)
  const submittedCount = players.filter((p) => submittedSet.has(p.guestId)).length

  return (
    <div className="flex flex-col gap-4 max-w-2xl mx-auto w-full animate-in fade-in duration-200 motion-reduce:animate-none">
      {/* Status banner */}
      <div className="flex flex-wrap items-center justify-between gap-3 p-4 rounded-2xl border border-border bg-card shadow-xs">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-emerald-500/10 text-emerald-600">
            <CheckCircle2 className="size-6" aria-hidden="true" />
          </div>
          <div>
            <h2 className="font-fredoka text-xl font-bold">
              {spectating ? 'The dinos are sketching' : 'Sketch submitted!'}
            </h2>
            <p className="text-sm text-muted-foreground">
              {spectating
                ? 'Every drawing appears in the gallery once the time is up.'
                : 'Waiting for other dinos to finish their masterpieces...'}
            </p>
          </div>
        </div>
        <ServerCountdown deadlineAt={deadlineAt} size="md" />
      </div>

      {/* Drawing preview */}
      {!spectating && (
        <div className="flex flex-col gap-2 p-4 rounded-2xl border border-border bg-card shadow-xs">
          <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            Your Drawing Preview
          </span>
          <TelephoneDrawingView
            strokes={strokes}
            label="Preview of your submitted sketch"
            compact
          />
        </div>
      )}

      {/* Progress status */}
      <div className="flex flex-col gap-3 p-4 rounded-2xl border border-border bg-card shadow-xs">
        <div className="flex items-center justify-between">
          <span className="text-sm font-semibold text-muted-foreground">
            Progress ({submittedCount} of {players.length} ready)
          </span>
          <span className="text-xs font-bold text-primary">
            {Math.round((submittedCount / Math.max(players.length, 1)) * 100)}%
          </span>
        </div>

        {/* Progress bar */}
        <div className="w-full h-2 rounded-full bg-muted overflow-hidden">
          <div
            className="h-full bg-primary transition-all duration-300"
            style={{
              width: `${(submittedCount / Math.max(players.length, 1)) * 100}%`,
            }}
          />
        </div>

        {/* Player readiness list */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 mt-1">
          {players.map((p) => {
            const isReady = submittedSet.has(p.guestId)
            return (
              <div
                key={p.guestId}
                className={cn(
                  'flex items-center gap-2.5 p-2 rounded-xl border transition-colors',
                  isReady
                    ? 'border-emerald-500/30 bg-emerald-500/5'
                    : 'border-border/60 bg-muted/30 text-muted-foreground',
                )}
              >
                <DinoAvatar avatarKey={p.avatarKey} size="xs" alt="" />
                <span className="text-sm font-medium truncate flex-1">{p.nickname}</span>
                {isReady ? (
                  <span className="inline-flex items-center gap-1 text-xs font-semibold text-emerald-600">
                    <CheckCircle2 className="size-3.5" />
                    Ready
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                    <Clock className="size-3.5" />
                    Drawing...
                  </span>
                )}
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}
