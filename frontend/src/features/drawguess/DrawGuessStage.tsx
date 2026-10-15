import * as React from 'react'

import type { PlayerDto } from '@/api/types'
import { useGameSessionStore } from '@/stores/gameSessionStore'
import { DrawCanvas } from '@/features/drawing/DrawCanvas'
import { DrawingToolbar } from '@/features/drawing/DrawingToolbar'
import { RoundEndOverlay } from './components/RoundEndOverlay'
import { WordBanner } from './components/WordBanner'
import { WordSelectionModal } from './components/WordSelectionModal'
import { useDrawGuessStore } from './drawGuessStore'
import { useDrawGuessCommands } from './useDrawGuessCommands'
import type { DrawStrokePayload, StrokeTool } from './types'

export interface DrawGuessStageProps {
  players: PlayerDto[]
  currentGuestId: string | null | undefined
}

export function DrawGuessStage({ players, currentGuestId }: DrawGuessStageProps) {
  const sendGameCommand = useDrawGuessCommands()

  // Game session store
  const phase = useGameSessionStore((s) => s.phase)
  const deadlineAt = useGameSessionStore((s) => s.deadlineAt)

  // Draw & Guess module store
  const round = useDrawGuessStore((s) => s.round)
  const totalRounds = useDrawGuessStore((s) => s.totalRounds)
  // Only the server names the drawer; until it has, nobody gets drawer controls.
  const drawerGuestId = useDrawGuessStore((s) => s.drawerGuestId)
  const word = useDrawGuessStore((s) => s.word)
  const wordChoices = useDrawGuessStore((s) => s.wordChoices)
  const hint = useDrawGuessStore((s) => s.hint)
  const strokes = useDrawGuessStore((s) => s.strokes)
  const canvasFull = useDrawGuessStore((s) => s.canvasFull)
  const strokeRejection = useDrawGuessStore((s) => s.strokeRejection)
  const addLocalStroke = useDrawGuessStore((s) => s.addLocalStroke)
  const appendLocalPoints = useDrawGuessStore((s) => s.appendLocalPoints)
  const guessedGuestIds = useDrawGuessStore((s) => s.guessedGuestIds)
  const roundEnd = useDrawGuessStore((s) => s.roundEnd)

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

  return (
    <div
      role="region"
      aria-label="Draw & Guess gameplay stage"
      className="flex-1 flex flex-col gap-3 min-h-0 w-full mx-auto"
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

      {/* Canvas, drawer toolbar, then guess feed and input stacked below */}
      <div className="flex-1 flex flex-col gap-2 min-h-0">
        <div className="flex-1 flex items-center justify-center p-2 rounded-2xl bg-card border border-border shadow-xs overflow-hidden min-h-[260px] lg:min-h-0">
          <DrawCanvas
            isDrawer={isDrawer}
            isDrawingPhase={isDrawingPhase}
            tool={tool}
            color={color}
            size={size}
            strokes={strokes}
            canvasFull={canvasFull}
            strokeRejection={strokeRejection}
            onSendStrokeBatch={handleSendStrokeBatch}
            onLocalStrokeStart={addLocalStroke}
            onLocalPoints={appendLocalPoints}
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
