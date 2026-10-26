import * as React from 'react'

import type { PlayerDto } from '@/api/types'
import { ServerCountdown } from '@/features/session/ServerCountdown'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import { playerName } from '../common/copy'
import { DiscussionChat } from '../common/DiscussionChat'
import { canGuess } from '../common/impostorState'
import { ImpostorGuessInput } from '../common/ImpostorGuessInput'
import { rosterNames } from '../common/nicknames'
import { RoleCard } from '../common/RoleCard'
import { RoundReveal } from '../common/RoundReveal'
import { GalleryGrid } from './GalleryGrid'
import { SketchCanvas } from './SketchCanvas'
import { SketchWaiting } from './SketchWaiting'
import { UnawareRoleReveal } from './UnawareRoleReveal'
import { useSketchySaurusStore } from './sketchySaurusStore'
import type { SketchySaurusPhase } from './types'
import { useSketchyCommands } from './useSketchyCommands'

export interface SketchySaurusStageProps {
  players: PlayerDto[]
  currentGuestId: string | null | undefined
}

const PHASE_TITLES: Record<SketchySaurusPhase, string> = {
  ROLE_REVEAL: 'Check your secret',
  SKETCH: 'Draw your sketch',
  GALLERY: 'Spot the impostor',
  GUESS: 'Impostor guess',
  ROUND_END: 'Round reveal',
}

function isSketchyPhase(phase: string | null): phase is SketchySaurusPhase {
  return phase !== null && phase in PHASE_TITLES
}

/** Sketchy Saurus in play: coordinates phase screens (api-contracts §3.12). */
export function SketchySaurusStage({ players, currentGuestId }: SketchySaurusStageProps) {
  const phase = useGameSessionStore((s) => s.phase)
  const deadlineAt = useGameSessionStore((s) => s.deadlineAt)
  const roster = useGameSessionStore((s) => s.players)

  const game = useSketchySaurusStore()
  const commands = useSketchyCommands(game.trackCommand)

  const rememberNicknames = game.rememberNicknames
  React.useEffect(() => {
    rememberNicknames(players)
  }, [players, rememberNicknames])

  const playersById = React.useMemo(
    () => rosterNames(players, game.nicknames),
    [players, game.nicknames],
  )

  const inGame = Boolean(
    currentGuestId && roster.some((p) => p.guestId === currentGuestId && p.status !== 'LEFT'),
  )

  if (!isSketchyPhase(phase)) return null

  // An unaware impostor's role stays ARTIST until the caught-guess moment ([SS-3]).
  const guesser = canGuess(game, phase, currentGuestId)
  const guessCategory = game.guessOpen?.category ?? game.role?.category ?? null

  const activePlayers = players.filter((p) =>
    roster.some((rp) => rp.guestId === p.guestId && rp.status !== 'LEFT'),
  )

  // Artists who left are still in the gallery: name them from the snapshot nicknames (G2).
  const galleryPlayers = (game.gallery ?? []).map((d) => ({
    guestId: d.guestId,
    nickname: playerName(playersById, d.guestId),
    avatarKey: players.find((p) => p.guestId === d.guestId)?.avatarKey,
  }))

  // Gallery (seat) order first so an impostor or voter who left keeps their name in the reveal.
  const roundRevealPlayers = [
    ...galleryPlayers,
    ...activePlayers
      .filter((p) => !galleryPlayers.some((g) => g.guestId === p.guestId))
      .map((p) => ({
        guestId: p.guestId,
        nickname: playerName(playersById, p.guestId),
        avatarKey: p.avatarKey,
      })),
  ]

  return (
    <div
      role="region"
      aria-label="Sketchy Saurus gameplay stage"
      className="flex flex-col gap-4 w-full"
    >
      {!inGame && (
        <aside
          role="status"
          className="rounded-xl border border-dashed border-border/80 bg-muted/30 p-2.5 text-center text-xs text-muted-foreground"
        >
          You are spectating this round. You will join when the next game starts.
        </aside>
      )}

      {phase === 'ROLE_REVEAL' && (
        <div className="flex flex-col items-center gap-4 max-w-lg mx-auto w-full">
          <RoleCard role={game.role} />
          <ServerCountdown deadlineAt={deadlineAt} size="md" />
        </div>
      )}

      {phase === 'SKETCH' && inGame && (
        <>
          {game.submitted ? (
            <SketchWaiting
              strokes={game.strokes}
              submittedGuestIds={game.submittedGuestIds}
              players={activePlayers}
              deadlineAt={deadlineAt}
            />
          ) : (
            <SketchCanvas
              role={game.role}
              strokes={game.strokes}
              canvasFull={game.canvasFull}
              strokeRejection={game.strokeRejection}
              submitted={game.submitted}
              submitError={game.submitError}
              deadlineAt={deadlineAt}
              commands={commands}
              onLocalStrokeStart={game.addLocalStroke}
              onLocalPoints={game.appendLocalPoints}
              onUndoLocalStroke={game.undoLocalStroke}
              onClearLocalStrokes={game.clearLocalStrokes}
            />
          )}
        </>
      )}

      {phase === 'SKETCH' && !inGame && (
        <SketchWaiting
          strokes={[]}
          submittedGuestIds={game.submittedGuestIds}
          players={activePlayers}
          deadlineAt={deadlineAt}
          spectating
        />
      )}

      {phase === 'GALLERY' && (
        <div className="flex flex-col gap-4">
          <GalleryGrid
            drawings={game.gallery ?? []}
            players={galleryPlayers}
            currentGuestId={currentGuestId}
            myVote={game.myVote}
            pendingVote={game.pendingVote}
            votedGuestIds={game.votedGuestIds}
            enabled={inGame}
            error={game.voteError}
            onVote={commands.vote}
            deadlineAt={deadlineAt}
          />
          <DiscussionChat
            messages={game.chat}
            currentGuestId={currentGuestId}
            playersById={playersById}
            enabled={inGame}
            onSend={commands.chat}
            error={game.chatError}
          />
        </div>
      )}

      {phase === 'GUESS' && (
        <div className="flex flex-col gap-4 max-w-lg mx-auto w-full">
          {guesser && game.role?.role === 'ARTIST' && (
            <UnawareRoleReveal category={guessCategory} />
          )}
          <ImpostorGuessInput
            isGuesser={guesser}
            impostorName={playerName(playersById, game.voteClosed?.impostorGuestId)}
            category={guessCategory}
            deadlineAt={deadlineAt}
            submitted={game.guessSubmitted}
            error={game.guessError}
            onGuess={commands.guess}
          />

          <DiscussionChat
            messages={game.chat}
            currentGuestId={currentGuestId}
            playersById={playersById}
            enabled={inGame}
            onSend={commands.chat}
            error={game.chatError}
          />
        </div>
      )}

      {phase === 'ROUND_END' && game.roundEnd && (
        <RoundReveal
          roundEnd={game.roundEnd}
          players={roundRevealPlayers}
          currentGuestId={currentGuestId}
          deadlineAt={deadlineAt}
          totalRounds={game.totalRounds}
        />
      )}
    </div>
  )
}
