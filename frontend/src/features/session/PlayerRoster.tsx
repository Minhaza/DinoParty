import * as React from 'react'
import { Check, Crown, UserMinus, WifiOff, Sparkles } from 'lucide-react'

import type { PlayerDto } from '@/api/types'
import { DinoAvatar } from '@/components/DinoAvatar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { cn } from '@/lib/utils'

export interface PlayerRosterProps {
  players: PlayerDto[]
  currentGuestId?: string | null
  hostGuestId?: string | null
  activeGuestId?: string | null
  guessedGuestIds?: string[]
  scores?: Record<string, number> | null
  isHost?: boolean
  onKickPlayer?: (guestId: string) => Promise<void> | void
  className?: string
}

export function PlayerRoster({
  players,
  currentGuestId,
  hostGuestId,
  activeGuestId,
  guessedGuestIds,
  scores,
  isHost = false,
  onKickPlayer,
  className,
}: PlayerRosterProps) {
  const [kickTarget, setKickTarget] = React.useState<PlayerDto | null>(null)
  const [isKicking, setIsKicking] = React.useState(false)

  const handleConfirmKick = async () => {
    if (!kickTarget || !onKickPlayer) return
    setIsKicking(true)
    try {
      await onKickPlayer(kickTarget.guestId)
      setKickTarget(null)
    } finally {
      setIsKicking(false)
    }
  }

  // One list doubles as the leaderboard: highest score first, ties keep join order.
  const ranked = players
    .map((player, index) => ({
      player,
      index,
      score: scores?.[player.guestId] ?? player.score ?? 0,
    }))
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .map(({ player }, rank) => ({ player, rank: rank + 1 }))

  return (
    <div className={cn('flex flex-col gap-2', className)}>
      <div className="flex items-center justify-between px-1">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          Players ({players.length})
        </h3>
      </div>

      <ul role="list" className="flex flex-col gap-1.5" aria-label="Game session players">
        {ranked.map(({ player, rank }) => {
          const isYou = player.guestId === currentGuestId
          const isPlayerHost = player.guestId === hostGuestId
          const isActiveTurn = player.guestId === activeGuestId
          const isDisconnected = player.connection === 'DISCONNECTED'
          const hasGuessed = !isActiveTurn && Boolean(guessedGuestIds?.includes(player.guestId))
          const playerScore = scores?.[player.guestId] ?? player.score

          return (
            <li
              key={player.guestId}
              role="listitem"
              className={cn(
                'group flex items-center justify-between gap-2.5 rounded-xl border p-2 transition-colors',
                isActiveTurn
                  ? 'border-primary/50 bg-primary/5 shadow-xs'
                  : 'border-border/60 bg-card/60 hover:bg-card',
                isDisconnected && 'opacity-65 bg-muted/30 border-dashed',
              )}
            >
              <div className="flex min-w-0 items-center gap-2.5">
                <span className="w-4 shrink-0 text-center font-mono text-xs font-bold text-muted-foreground">
                  {rank}
                </span>
                <div className="relative">
                  <DinoAvatar avatarKey={player.avatarKey} size="sm" />
                  {isDisconnected && (
                    <div
                      title="Player disconnected"
                      className="absolute -bottom-1 -right-1 flex size-4 items-center justify-center rounded-full bg-destructive text-destructive-foreground ring-2 ring-background"
                    >
                      <WifiOff className="size-2.5" />
                    </div>
                  )}
                </div>

                <div className="min-w-0 flex flex-col">
                  <div className="flex items-center gap-1.5 truncate">
                    <span className="truncate text-sm font-semibold text-foreground">
                      {player.nickname}
                    </span>

                    {isPlayerHost && (
                      <span title="Room Host">
                        <Crown className="size-3.5 shrink-0 text-amber-500" aria-label="Host" />
                      </span>
                    )}

                    {isYou && (
                      <Badge
                        variant="outline"
                        className="px-1.5 py-0 text-[10px] font-medium leading-4"
                      >
                        You
                      </Badge>
                    )}

                    {isActiveTurn && (
                      <span className="inline-flex items-center gap-0.5 text-[11px] font-semibold text-primary">
                        <Sparkles className="size-3" />
                        Turn
                      </span>
                    )}
                  </div>

                  {isDisconnected ? (
                    <span className="text-[11px] text-destructive font-medium">Disconnected</span>
                  ) : hasGuessed ? (
                    <span className="inline-flex items-center gap-0.5 text-[11px] font-medium text-success">
                      <Check className="size-3" />
                      Guessed
                    </span>
                  ) : null}
                </div>
              </div>

              <div className="flex items-center gap-1.5 shrink-0">
                {isHost && !isYou && onKickPlayer && (
                  <Button
                    variant="ghost"
                    size="icon"
                    className="size-7 opacity-0 group-hover:opacity-100 focus:opacity-100 text-muted-foreground hover:text-destructive transition-opacity"
                    onClick={() => setKickTarget(player)}
                    aria-label={`Kick ${player.nickname}`}
                    title={`Kick ${player.nickname}`}
                  >
                    <UserMinus className="size-3.5" />
                  </Button>
                )}
                {playerScore !== null && playerScore !== undefined && (
                  <Badge variant="secondary" className="font-mono text-xs tabular-nums">
                    {playerScore}{' '}
                    <span className="text-[10px] text-muted-foreground ml-0.5">pts</span>
                  </Badge>
                )}
              </div>
            </li>
          )
        })}
      </ul>

      {/* Confirmation Dialog for Kicking */}
      <Dialog open={!!kickTarget} onOpenChange={(open) => !open && setKickTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Kick {kickTarget?.nickname}?</DialogTitle>
            <DialogDescription>
              Are you sure you want to kick {kickTarget?.nickname} from the game? They will be
              removed immediately.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter className="gap-2 sm:gap-0">
            <Button variant="outline" onClick={() => setKickTarget(null)} disabled={isKicking}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={handleConfirmKick} disabled={isKicking}>
              {isKicking ? 'Kicking…' : 'Kick Player'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
