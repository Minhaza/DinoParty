import {
  Gamepad2,
  Trophy,
  Sparkles,
} from 'lucide-react'

import type { PlayerDto } from '@/api/types'
import { GAME_CONFIGS } from '@/features/rooms/gameConfig'
import { DinoAvatar } from '@/components/DinoAvatar'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import { GameStartingOverlay } from './GameStartingOverlay'
import { RematchControls } from './RematchControls'
import { Scoreboard } from './Scoreboard'
import { ServerCountdown } from './ServerCountdown'

export interface GameStageProps {
  roomStatus: string
  gameKey: string
  players: PlayerDto[]
  currentGuestId: string | null | undefined
  isHost: boolean
  onVoteRematch: (vote: boolean) => Promise<void> | void
  onReturnToLobby: () => void
}

export function GameStage({
  roomStatus,
  gameKey,
  players,
  currentGuestId,
  isHost,
  onVoteRematch,
  onReturnToLobby,
}: GameStageProps) {
  const session = useGameSessionStore()
  const config = GAME_CONFIGS[gameKey as keyof typeof GAME_CONFIGS] ?? {
    name: 'Game Session',
    description: 'Active party game session',
  }

  // Active turn player if order is established
  const currentTurnGuestId = session.order.length > 0 ? session.order[0] : null
  const currentTurnPlayer = players.find((p) => p.guestId === currentTurnGuestId)

  return (
    <div className="flex-1 flex flex-col gap-4 min-h-0">
      {/* 3-Second Starting Countdown Overlay */}
      {roomStatus === 'STARTING' && (
        <GameStartingOverlay
          startsAt={session.startsAt}
          gameTitle={config.name}
        />
      )}

      {/* RESULTS Phase View */}
      {roomStatus === 'RESULTS' ? (
        <div className="flex flex-col gap-4 max-w-2xl mx-auto w-full py-4 animate-in fade-in duration-300">
          <Card className="border-amber-500/30 shadow-md">
            <CardHeader className="text-center pb-2">
              <div className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-amber-500/10 text-amber-500 mb-2 ring-1 ring-amber-500/20">
                <Trophy className="size-8 animate-bounce" />
              </div>
              <CardTitle className="font-fredoka text-3xl">Game Finished!</CardTitle>
              <CardDescription>
                {session.endReason === 'COMPLETED'
                  ? 'All rounds completed! Here are the final scores.'
                  : `Game finished: ${session.endReason || 'Session ended'}`}
              </CardDescription>
            </CardHeader>
            <CardContent className="pt-2">
              <Scoreboard
                standings={session.results?.standings}
                players={players}
                currentGuestId={currentGuestId}
                title="Final Standings"
              />
            </CardContent>
          </Card>

          {/* Rematch Voting Card */}
          <RematchControls
            votes={session.rematch?.votes}
            playerCount={players.length}
            currentGuestId={currentGuestId}
            isHost={isHost}
            onVoteRematch={onVoteRematch}
            onReturnToLobby={onReturnToLobby}
          />
        </div>
      ) : (
        /* PLAYING Phase View */
        <Card className="flex-1 flex flex-col border-border/70 shadow-xs overflow-hidden">
          <CardHeader className="border-b border-border/50 bg-muted/20 py-3.5 px-4 sm:px-6">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <Gamepad2 className="size-5 text-primary" />
                <CardTitle className="text-base sm:text-lg">
                  {session.phase
                    ? session.phase
                        .replace(/_/g, ' ')
                        .toLowerCase()
                        .replace(/\b\w/g, (c) => c.toUpperCase())
                    : 'Game In Progress'}
                </CardTitle>
              </div>

              {session.deadlineAt && (
                <div className="flex items-center gap-2">
                  <span className="text-xs text-muted-foreground hidden sm:inline">Time left:</span>
                  <ServerCountdown deadlineAt={session.deadlineAt} size="sm" />
                </div>
              )}
            </div>
            <CardDescription className="text-xs sm:text-sm">
              Phase {session.phaseId ? `#${session.phaseId.slice(0, 8)}` : 'active'} · {config.name}
            </CardDescription>
          </CardHeader>

          <CardContent className="flex-1 flex flex-col items-center justify-center p-6 text-center gap-5 min-h-[300px]">
            {/* Active Turn Indicator */}
            {currentTurnPlayer && (
              <div className="inline-flex items-center gap-2.5 rounded-full border border-primary/30 bg-primary/5 px-3 py-1.5 text-xs font-medium text-foreground">
                <DinoAvatar avatarKey={currentTurnPlayer.avatarKey} size="xs" />
                <span>
                  Current Turn: <strong>{currentTurnPlayer.nickname}</strong>
                </span>
                {currentTurnPlayer.guestId === currentGuestId && (
                  <span className="text-primary font-bold">(Your Turn)</span>
                )}
              </div>
            )}

            {/* Game Canvas / Activity Placeholder Area */}
            <div
              tabIndex={0}
              role="region"
              aria-label={`${config.name} gameplay stage`}
              className="flex-1 flex flex-col items-center justify-center w-full max-w-lg rounded-2xl border-2 border-dashed border-border/80 bg-muted/20 p-8 focus:ring-2 focus:ring-primary focus:outline-hidden transition-all"
            >
              <div className="flex size-16 items-center justify-center rounded-2xl bg-primary/10 text-primary mb-3">
                <Sparkles className="size-8" />
              </div>
              <h3 className="font-fredoka text-xl text-foreground font-semibold">
                {session.phase || 'Gameplay Stage'}
              </h3>
              <p className="text-sm text-muted-foreground mt-1 max-w-sm">
                Interactive game controls and canvas will be active during specific game phases.
              </p>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
