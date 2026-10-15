import * as React from 'react'

import type { PlayerDto } from '@/api/types'
import { useGameSessionStore } from '@/stores/gameSessionStore'
import { useDrawGuessStore } from '../drawGuessStore'
import { useDrawGuessCommands } from '../useDrawGuessCommands'
import { GuessFeed } from './GuessFeed'
import { GuessInput } from './GuessInput'

export interface DrawGuessChatPanelProps {
  players: PlayerDto[]
  currentGuestId: string | null | undefined
}

/** Guess feed plus input, tall and narrow so many short guesses stay readable (lives in the sidebar). */
export function DrawGuessChatPanel({ players, currentGuestId }: DrawGuessChatPanelProps) {
  const phase = useGameSessionStore((s) => s.phase)
  const drawerGuestId = useDrawGuessStore((s) => s.drawerGuestId)
  const guessedGuestIds = useDrawGuessStore((s) => s.guessedGuestIds)
  const chat = useDrawGuessStore((s) => s.chat)
  const feedback = useDrawGuessStore((s) => s.feedback)
  const clearFeedback = useDrawGuessStore((s) => s.clearFeedback)
  const sendGameCommand = useDrawGuessCommands()

  const isDrawer = Boolean(currentGuestId && drawerGuestId === currentGuestId)
  const isSolved = Boolean(currentGuestId && guessedGuestIds.includes(currentGuestId))

  const handleSubmitGuess = React.useCallback(
    (text: string) => {
      if (isDrawer) return
      sendGameCommand('SUBMIT_GUESS', { text })
    },
    [sendGameCommand, isDrawer],
  )

  return (
    <div className="flex min-h-[200px] lg:min-h-0 flex-1 flex-col gap-2">
      <div className="min-h-0 flex-1">
        <GuessFeed
          chat={chat}
          players={players}
          guessedGuestIds={guessedGuestIds}
          currentGuestId={currentGuestId}
          feedback={feedback}
          onClearFeedback={clearFeedback}
        />
      </div>
      <GuessInput
        isDrawer={isDrawer}
        isSolved={isSolved}
        isDrawingPhase={phase === 'DRAWING'}
        onSubmitGuess={handleSubmitGuess}
      />
    </div>
  )
}
