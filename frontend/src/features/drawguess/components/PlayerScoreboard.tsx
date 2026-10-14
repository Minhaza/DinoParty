import { Check, Pencil, WifiOff } from 'lucide-react'

import type { PlayerDto } from '@/api/types'
import { DinoAvatar } from '@/components/DinoAvatar'

export interface PlayerScoreboardProps {
  players: PlayerDto[]
  drawerGuestId: string | null
  guessedGuestIds: string[]
  scores: Record<string, number> | null
  scoreDeltas?: Record<string, number> | null
  currentGuestId: string | null | undefined
}

export function PlayerScoreboard({
  players,
  drawerGuestId,
  guessedGuestIds,
  scores,
  scoreDeltas,
  currentGuestId,
}: PlayerScoreboardProps) {
  // Sort players by score descending
  const sortedPlayers = [...players].sort((a, b) => {
    const scoreA = scores?.[a.guestId] ?? a.score ?? 0
    const scoreB = scores?.[b.guestId] ?? b.score ?? 0
    return scoreB - scoreA
  })

  return (
    <div
      role="region"
      aria-label="Players and scores"
      className="flex flex-col bg-card rounded-xl border border-border overflow-hidden shadow-xs"
    >
      <div className="flex items-center justify-between px-3 py-2 border-b border-border/60 bg-muted/30">
        <span className="text-xs font-bold uppercase tracking-wider text-muted-foreground">
          Players ({players.length})
        </span>
      </div>

      <div className="flex flex-col divide-y divide-border/40 overflow-y-auto max-h-[300px]">
        {sortedPlayers.map((player, idx) => {
          const isDrawer = player.guestId === drawerGuestId
          const hasGuessed = guessedGuestIds.includes(player.guestId)
          const isMe = player.guestId === currentGuestId
          const score = scores?.[player.guestId] ?? player.score ?? 0
          const delta = scoreDeltas?.[player.guestId]
          const isDisconnected = player.connection === 'DISCONNECTED'

          return (
            <div
              key={player.guestId}
              className={`flex items-center justify-between gap-2 px-3 py-2 transition-colors ${
                isMe ? 'bg-primary/5' : ''
              } ${isDisconnected ? 'opacity-60' : ''}`}
            >
              <div className="flex items-center gap-2 min-w-0">
                <span className="text-xs font-mono font-bold text-muted-foreground w-4">
                  {idx + 1}
                </span>

                <div className="relative">
                  <DinoAvatar avatarKey={player.avatarKey} size="xs" />
                  {isDrawer && (
                    <div
                      className="absolute -bottom-1 -right-1 flex size-4 items-center justify-center rounded-full bg-primary text-primary-foreground shadow-xs ring-1 ring-background"
                      title="Drawer"
                    >
                      <Pencil className="size-2.5" />
                    </div>
                  )}
                  {hasGuessed && !isDrawer && (
                    <div
                      className="absolute -bottom-1 -right-1 flex size-4 items-center justify-center rounded-full bg-success text-success-foreground shadow-xs ring-1 ring-background"
                      title="Guessed correctly"
                    >
                      <Check className="size-2.5" />
                    </div>
                  )}
                </div>

                <div className="flex flex-col min-w-0">
                  <div className="flex items-center gap-1">
                    <span className="text-xs font-bold truncate text-foreground">
                      {player.nickname}
                    </span>
                    {isMe && (
                      <span className="text-[10px] text-muted-foreground font-semibold">(You)</span>
                    )}
                    {isDisconnected && (
                      <span title="Disconnected" className="inline-flex shrink-0">
                        <WifiOff className="size-3 text-destructive" />
                      </span>
                    )}
                  </div>
                  <span className="text-[11px] text-muted-foreground">
                    {isDrawer
                      ? 'Drawing'
                      : hasGuessed
                        ? 'Guessed'
                        : isDisconnected
                          ? 'Offline'
                          : 'Guessing'}
                  </span>
                </div>
              </div>

              <div className="flex items-center gap-1.5 shrink-0">
                {delta !== undefined && delta > 0 && (
                  <span className="text-[11px] font-bold text-success animate-in fade-in">
                    +{delta}
                  </span>
                )}
                <span className="font-mono text-sm font-bold text-foreground">{score}</span>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
