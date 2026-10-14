import * as React from 'react'
import { RotateCcw, Check, Sparkles } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { cn } from '@/lib/utils'

export interface RematchControlsProps {
  votes: Record<string, boolean> | null | undefined
  playerCount: number
  currentGuestId: string | null | undefined
  isHost: boolean
  onVoteRematch: (vote: boolean) => Promise<void> | void
  onReturnToLobby?: () => void
  className?: string
}

export function RematchControls({
  votes,
  playerCount,
  currentGuestId,
  isHost,
  onVoteRematch,
  onReturnToLobby,
  className,
}: RematchControlsProps) {
  const [isSubmitting, setIsSubmitting] = React.useState(false)

  const activeVotes = votes || {}
  const yesVotesCount = Object.values(activeVotes).filter(Boolean).length
  const hasVotedYes = currentGuestId ? !!activeVotes[currentGuestId] : false

  const handleVoteToggle = async () => {
    setIsSubmitting(true)
    try {
      await onVoteRematch(!hasVotedYes)
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <Card className={cn('overflow-hidden border-primary/20 shadow-md', className)}>
      <CardHeader className="bg-primary/5 pb-3">
        <div className="flex items-center gap-2">
          <Sparkles className="size-5 text-primary" />
          <CardTitle className="text-lg">Play Again?</CardTitle>
        </div>
        <CardDescription>
          {isHost
            ? 'As the host, voting for a rematch will return everyone to the lobby.'
            : 'Vote to let the host know you want another round!'}
        </CardDescription>
      </CardHeader>

      <CardContent className="flex flex-col gap-4 pt-4">
        <div className="flex items-center justify-between text-sm">
          <span className="text-muted-foreground font-medium">Rematch Votes</span>
          <span className="font-mono font-bold tabular-nums">
            {yesVotesCount} / {playerCount} players ready
          </span>
        </div>

        {/* Progress Bar */}
        <div
          className="h-2 w-full overflow-hidden rounded-full bg-secondary/50"
          role="progressbar"
          aria-valuenow={yesVotesCount}
          aria-valuemin={0}
          aria-valuemax={playerCount}
          aria-label="Rematch vote progress"
        >
          <div
            className="h-full bg-primary transition-all duration-300 rounded-full"
            style={{
              width: `${playerCount > 0 ? (yesVotesCount / playerCount) * 100 : 0}%`,
            }}
          />
        </div>

        <div className="flex flex-col sm:flex-row items-center gap-3 pt-1">
          <Button
            variant={hasVotedYes ? 'secondary' : 'default'}
            className={cn('w-full sm:flex-1 gap-2 font-semibold', hasVotedYes && 'border border-primary/30')}
            onClick={handleVoteToggle}
            disabled={isSubmitting}
          >
            {hasVotedYes ? (
              <>
                <Check className="size-4 text-primary" />
                Voted for Rematch (Undo)
              </>
            ) : (
              <>
                <RotateCcw className="size-4" />
                {isHost ? 'Start Rematch' : 'Vote for Rematch'}
              </>
            )}
          </Button>

          {onReturnToLobby && (
            <Button
              variant="outline"
              className="w-full sm:w-auto"
              onClick={onReturnToLobby}
            >
              Exit to Lobby
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
