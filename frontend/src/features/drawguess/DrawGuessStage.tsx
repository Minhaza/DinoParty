import * as React from 'react'

import type { PlayerDto } from '@/api/types'
import { useRealtimeClient } from '@/features/realtime/realtimeContext'
import { useGameSessionStore } from '@/stores/gameSessionStore'
import { DrawCanvas } from './canvas/DrawCanvas'
import { DrawingToolbar } from './canvas/DrawingToolbar'
import { GuessFeed } from './components/GuessFeed'
import { GuessInput } from './components/GuessInput'
import { PlayerScoreboard } from './components/PlayerScoreboard'
import { RoundEndOverlay } from './components/RoundEndOverlay'
import { WordBanner } from './components/WordBanner'
import { WordSelectionModal } from './components/WordSelectionModal'
import { useDrawGuessStore } from './drawGuessStore'
import type { DrawStrokePayload, StrokeTool } from './types'

export interface DrawGuessStageProps {
  players: PlayerDto[]
  currentGuestId: string | null | undefined
}

export function DrawGuessStage({ players, currentGuestId }: DrawGuessStageProps) {
  const client = useRealtimeClient()

  // Game session store
  const phase = useGameSessionStore((s) => s.phase)
  const phaseId = useGameSessionStore((s) => s.phaseId)
  const deadlineAt = useGameSessionStore((s) => s.deadlineAt)
  const sessionScores = useGameSessionStore((s) => s.scores)

  // Draw & Guess module store
  const round = useDrawGuessStore((s) => s.round)
  const totalRounds = useDrawGuessStore((s) => s.totalRounds)
  // Only the server names the drawer; until it has, nobody gets drawer controls.
  const drawerGuestId = useDrawGuessStore((s) => s.drawerGuestId)
  const word = useDrawGuessStore((s) => s.word)
  const wordChoices = useDrawGuessStore((s) => s.wordChoices)
  const hint = useDrawGuessStore((s) => s.hint)
  const strokes = useDrawGuessStore((s) => s.strokes)
  const guessedGuestIds = useDrawGuessStore((s) => s.guessedGuestIds)
  const chat = useDrawGuessStore((s) => s.chat)
  const roundEnd = useDrawGuessStore((s) => s.roundEnd)
  const feedback = useDrawGuessStore((s) => s.feedback)
  const clearFeedback = useDrawGuessStore((s) => s.clearFeedback)
  const trackCommand = useDrawGuessStore((s) => s.trackCommand)

  // Drawer toolbar state
  const [tool, setTool] = React.useState<StrokeTool>('PENCIL')
  const [color, setColor] = React.useState<string>('#2B2B2B')
  const [size, setSize] = React.useState<number>(2)

  const isDrawer = Boolean(currentGuestId && drawerGuestId === currentGuestId)
  const isSolved = Boolean(currentGuestId && guessedGuestIds.includes(currentGuestId))
  const isDrawingPhase = phase === 'DRAWING'
  const isWordChoicePhase = phase === 'WORD_CHOICE'
  const isRoundEndPhase = phase === 'ROUND_END'

  const drawerPlayer = players.find((p) => p.guestId === drawerGuestId)
  const drawerNickname = drawerPlayer?.nickname ?? 'Drawer'

  /**
   * Sends a game command for the current phase. Returns `false` instead of throwing while the
   * socket is down: the reconnect snapshot restores the authoritative state afterwards.
   */
  const sendGameCommand = React.useCallback(
    (type: string, payload: object, strokeId?: string): boolean => {
      if (!phaseId) return false
      try {
        const actionId = client.sendCommand({ type, phaseId, payload })
        trackCommand(actionId, type, strokeId)
        return true
      } catch {
        return false
      }
    },
    [client, phaseId, trackCommand],
  )

  const handleChooseWord = React.useCallback(
    (wordIndex: number) => {
      sendGameCommand('CHOOSE_WORD', { wordIndex })
    },
    [sendGameCommand],
  )

  const handleSendStrokeBatch = React.useCallback(
    (batchPayload: DrawStrokePayload) =>
      sendGameCommand('DRAW_STROKE', batchPayload, batchPayload.strokeId),
    [sendGameCommand],
  )

  const handleUndo = React.useCallback(() => {
    if (!isDrawer || strokes.length === 0) return
    sendGameCommand('DRAW_UNDO', {})
  }, [sendGameCommand, isDrawer, strokes.length])

  const handleClear = React.useCallback(() => {
    if (!isDrawer || strokes.length === 0) return
    sendGameCommand('DRAW_CLEAR', {})
  }, [sendGameCommand, isDrawer, strokes.length])

  const handleSubmitGuess = React.useCallback(
    (text: string) => {
      if (isDrawer) return
      sendGameCommand('SUBMIT_GUESS', { text })
    },
    [sendGameCommand, isDrawer],
  )

  return (
    <div
      role="region"
      aria-label="Draw & Guess gameplay stage"
      className="flex-1 flex flex-col gap-3 min-h-0 w-full max-w-6xl mx-auto"
    >
      {/* Top Banner: Round indicator, Word/Hint, Drawer Info, and Countdown */}
      <WordBanner
        phase={phase}
        isDrawer={isDrawer}
        drawerNickname={drawerNickname}
        word={word}
        hint={hint}
        deadlineAt={deadlineAt}
        isSolved={isSolved}
      />

      {/* Main Gameplay Grid: Canvas + Sidebar */}
      <div className="flex-1 grid grid-cols-1 lg:grid-cols-12 gap-3 min-h-0">
        {/* Left / Center Column: Canvas and Drawer Toolbar (8 cols on large screens) */}
        <div className="lg:col-span-8 flex flex-col gap-2 min-h-0">
          <div className="flex-1 flex items-center justify-center p-2 rounded-2xl bg-card border border-border shadow-xs overflow-hidden min-h-[260px] sm:min-h-[380px]">
            <DrawCanvas
              isDrawer={isDrawer}
              isDrawingPhase={isDrawingPhase}
              tool={tool}
              color={color}
              size={size}
              onSendStrokeBatch={handleSendStrokeBatch}
            />
          </div>

          {/* Drawer Toolbar: Only interactive for authorized drawer during DRAWING */}
          {isDrawer && isDrawingPhase && (
            <DrawingToolbar
              tool={tool}
              color={color}
              size={size}
              canDraw={isDrawer && isDrawingPhase}
              hasStrokes={strokes.length > 0}
              onSelectTool={setTool}
              onSelectColor={setColor}
              onSelectSize={setSize}
              onUndo={handleUndo}
              onClear={handleClear}
            />
          )}
        </div>

        {/* Right Column: Scoreboard, Guesses Feed, and Guess Input (4 cols on large screens) */}
        <div className="lg:col-span-4 flex flex-col gap-2 min-h-0">
          {/* Player Scoreboard */}
          <div className="shrink-0">
            <PlayerScoreboard
              players={players}
              drawerGuestId={drawerGuestId}
              guessedGuestIds={guessedGuestIds}
              scores={sessionScores}
              scoreDeltas={roundEnd?.scoreDeltas}
              currentGuestId={currentGuestId}
            />
          </div>

          {/* Guess & Chat Feed */}
          <div className="flex-1 min-h-[160px] flex flex-col">
            <GuessFeed
              chat={chat}
              players={players}
              guessedGuestIds={guessedGuestIds}
              currentGuestId={currentGuestId}
              feedback={feedback}
              onClearFeedback={clearFeedback}
            />
          </div>

          {/* Guess Form Input */}
          <div className="shrink-0">
            <GuessInput
              isDrawer={isDrawer}
              isSolved={isSolved}
              isDrawingPhase={isDrawingPhase}
              onSubmitGuess={handleSubmitGuess}
            />
          </div>
        </div>
      </div>

      {/* Word Selection Modal (Drawer picks word / Guessers wait) */}
      {isWordChoicePhase && (
        <WordSelectionModal
          isDrawer={isDrawer}
          drawerNickname={drawerNickname}
          choices={wordChoices}
          deadlineAt={deadlineAt}
          onChooseWord={handleChooseWord}
        />
      )}

      {/* Round End Overlay */}
      {isRoundEndPhase && (
        <RoundEndOverlay
          round={round}
          totalRounds={totalRounds}
          word={word}
          roundEnd={roundEnd}
          players={players}
          deadlineAt={deadlineAt}
        />
      )}
    </div>
  )
}
