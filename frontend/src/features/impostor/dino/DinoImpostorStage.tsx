import * as React from 'react'

import type { PlayerDto } from '@/api/types'
import { ServerCountdown } from '@/features/session/ServerCountdown'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import { playerName } from '../common/copy'
import { canGuess } from '../common/impostorState'
import { DiscussionChat } from '../common/DiscussionChat'
import { ImpostorGuessInput } from '../common/ImpostorGuessInput'
import { RoleCard } from '../common/RoleCard'
import { RoundReveal } from '../common/RoundReveal'
import type { DinoImpostorPhase } from '../common/types'
import { useImpostorCommands } from '../common/useImpostorCommands'
import { VotePanel, type VoteCandidate } from '../common/VotePanel'
import { useDinoImpostorStore } from './dinoImpostorStore'
import { SharedCanvas } from './SharedCanvas'
import { StrokeLegend } from './StrokeLegend'
import { TurnStrip } from './TurnStrip'

export interface DinoImpostorStageProps {
  players: PlayerDto[]
  currentGuestId: string | null | undefined
}

const PHASE_TITLES: Record<DinoImpostorPhase, string> = {
  ROLE_REVEAL: 'Check your secret',
  DRAWING: 'One stroke each',
  VOTE: 'Find the impostor',
  GUESS: 'Last chance for the impostor',
  ROUND_END: 'The big reveal',
}

function isDinoPhase(phase: string | null): phase is DinoImpostorPhase {
  return phase !== null && phase in PHASE_TITLES
}

/** Dino Impostor in play: picks the screen by phase (§3.11). Results and rematch are GameStage's. */
export function DinoImpostorStage({ players, currentGuestId }: DinoImpostorStageProps) {
  const phase = useGameSessionStore((s) => s.phase)
  const phaseId = useGameSessionStore((s) => s.phaseId)
  const deadlineAt = useGameSessionStore((s) => s.deadlineAt)
  const roster = useGameSessionStore((s) => s.players)

  const game = useDinoImpostorStore()
  const commands = useImpostorCommands(game.trackCommand)

  const playersById = React.useMemo(() => new Map(players.map((p) => [p.guestId, p])), [players])
  const leftIds = React.useMemo(
    () => new Set(roster.filter((p) => p.status === 'LEFT').map((p) => p.guestId)),
    [roster],
  )
  const inGame = Boolean(
    currentGuestId && roster.some((p) => p.guestId === currentGuestId && p.status !== 'LEFT'),
  )

  if (!isDinoPhase(phase)) return null

  const name = (id: string | null | undefined) =>
    id && id === currentGuestId ? 'You' : playerName(playersById, id)
  const isDrawing = phase === 'DRAWING'
  const isMyTurn = isDrawing && Boolean(currentGuestId) && game.drawerGuestId === currentGuestId
  const seatPlayers = game.seatOrder.map((id) => ({
    guestId: id,
    nickname: name(id),
    avatarKey: playersById.get(id)?.avatarKey ?? null,
  }))
  const candidates: VoteCandidate[] = seatPlayers.filter((p) => !leftIds.has(p.guestId))

  const canvas = (
    <div className="rounded-2xl border border-border bg-card p-2 shadow-xs">
      <SharedCanvas
        strokes={game.strokes}
        colors={game.colors}
        currentGuestId={currentGuestId}
        drawingPhase={isDrawing}
        isMyTurn={isMyTurn && inGame}
        turnPhaseId={isDrawing ? phaseId : null}
        locked={game.lockedTurnPhaseId !== null && game.lockedTurnPhaseId === phaseId}
        canvasFull={game.canvasFull}
        strokeRejection={game.strokeRejection}
        drawerName={game.drawerGuestId ? name(game.drawerGuestId) : null}
        onSend={commands.drawStroke}
        onLocalStrokeStart={game.addLocalStroke}
        onLocalPoints={game.appendLocalPoints}
      />
    </div>
  )
  const legend = (
    <StrokeLegend
      seatOrder={game.seatOrder}
      playersById={playersById}
      colors={game.colors}
      currentGuestId={currentGuestId}
    />
  )

  return (
    <div
      role="region"
      aria-label="Dino Impostor gameplay stage"
      className="flex w-full flex-1 min-h-0 flex-col gap-3"
    >
      <header className="flex flex-wrap items-center justify-between gap-2 rounded-2xl border border-border bg-card px-4 py-2.5 shadow-xs">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            Round {game.round} of {game.totalRounds}
          </p>
          <h2 className="font-fredoka text-lg font-semibold" aria-live="polite">
            {PHASE_TITLES[phase]}
          </h2>
        </div>
        {!isDrawing && phase !== 'ROUND_END' && (
          <ServerCountdown deadlineAt={deadlineAt} size="sm" />
        )}
      </header>

      {phase === 'ROLE_REVEAL' ? (
        <div className="mx-auto flex w-full max-w-xl flex-col gap-3 py-2">
          <RoleCard role={game.role} />
          {game.seatOrder.length > 0 && (
            <p className="text-center text-sm text-muted-foreground">
              Drawing order: {seatPlayers.map((p) => p.nickname).join(' → ')}
            </p>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-1 items-start gap-3 lg:grid-cols-[minmax(0,1fr)_20rem]">
          <div className="flex min-h-0 flex-col gap-3">
            {isDrawing && (
              <TurnStrip
                seatOrder={game.seatOrder}
                playersById={playersById}
                colors={game.colors}
                drawerGuestId={game.drawerGuestId}
                currentGuestId={currentGuestId}
                lap={game.lap}
                laps={game.laps}
                turn={game.turn}
                totalTurns={game.totalTurns}
                deadlineAt={deadlineAt}
                leftGuestIds={leftIds}
              />
            )}
            {phase === 'ROUND_END' && game.roundEnd && (
              <RoundReveal
                roundEnd={game.roundEnd}
                players={seatPlayers}
                currentGuestId={currentGuestId}
                deadlineAt={deadlineAt}
                totalRounds={game.totalRounds}
              />
            )}
            {canvas}
          </div>

          <aside className="flex min-h-0 flex-col gap-3" aria-label="Round details">
            {phase !== 'ROUND_END' && <RoleCard role={game.role} compact />}

            {phase === 'VOTE' && (
              <>
                <VotePanel
                  candidates={candidates}
                  currentGuestId={currentGuestId}
                  myVote={game.myVote}
                  pendingVote={game.pendingVote}
                  votedGuestIds={game.votedGuestIds}
                  enabled={inGame}
                  error={game.voteError}
                  onVote={commands.vote}
                />
                <DiscussionChat
                  messages={game.chat}
                  playersById={playersById}
                  currentGuestId={currentGuestId}
                  enabled={inGame}
                  error={game.chatError}
                  onSend={commands.chat}
                />
              </>
            )}

            {phase === 'GUESS' && (
              <>
                {game.voteClosed && (
                  <p
                    className="rounded-2xl border border-border bg-card p-3 text-sm"
                    aria-live="polite"
                  >
                    The dinos pointed at <strong>{name(game.voteClosed.accusedGuestId)}</strong>
                    {game.voteClosed.caught ? ' — and caught the impostor!' : '.'}
                  </p>
                )}
                <ImpostorGuessInput
                  isGuesser={canGuess(game, phase, currentGuestId)}
                  impostorName={name(game.voteClosed?.impostorGuestId)}
                  category={game.guessOpen?.category ?? game.role?.category ?? null}
                  deadlineAt={deadlineAt}
                  submitted={game.guessSubmitted}
                  error={game.guessError}
                  onGuess={commands.guess}
                />
              </>
            )}

            {legend}
          </aside>
        </div>
      )}
    </div>
  )
}
