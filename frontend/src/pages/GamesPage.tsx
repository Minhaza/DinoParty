import { ArrowRight, Users } from 'lucide-react'
import { useId, useState } from 'react'

import type { GameKey, GameSummaryDto } from '@/api/types'
import { CreateRoomModal } from '@/components/modals/CreateRoomModal'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { GameBanner } from '@/features/games/GameBanner'
import { gameModule, gameVisual, TONE_STYLES } from '@/features/games/gameModules'
import { LoadError } from '@/features/games/LoadError'
import { useEnabledGames, useGameDetail } from '@/features/games/useGames'

export function GamesPage() {
  const [createModalOpen, setCreateModalOpen] = useState(false)
  const [selectedGame, setSelectedGame] = useState<GameKey | undefined>(undefined)
  const games = useEnabledGames()

  const handleStartGame = (game: GameKey) => {
    setSelectedGame(game)
    setCreateModalOpen(true)
  }

  return (
    <div className="flex flex-col gap-8 py-2">
      <header className="flex flex-col gap-1">
        <h1 className="text-4xl font-bold tracking-tight text-foreground sm:text-5xl">Our Games</h1>
        <p className="text-base font-semibold text-foreground/75 sm:text-lg">
          Choose a game and start a room with your friends!
        </p>
      </header>

      {games.isError ? (
        <LoadError error={games.error} onRetry={() => void games.refetch()} />
      ) : (
        <div
          className="grid grid-cols-1 gap-6 md:grid-cols-2 md:gap-8"
          aria-busy={games.isPending || undefined}
        >
          {games.isPending
            ? [0, 1].map((i) => (
                <div
                  key={i}
                  className="aspect-[1.25/1] animate-pulse rounded-[22px] border-2 border-border bg-card motion-reduce:animate-none"
                />
              ))
            : games.data.map((game) => (
                <GameCard key={game.key} game={game} onPlay={() => handleStartGame(game.key)} />
              ))}
          {games.isSuccess && games.data.length === 0 && (
            <p role="status" className="text-sm font-semibold text-muted-foreground">
              No games are available right now. Please check back soon.
            </p>
          )}
        </div>
      )}

      <CreateRoomModal
        open={createModalOpen}
        onOpenChange={setCreateModalOpen}
        initialGame={selectedGame}
      />
    </div>
  )
}

function GameCard({ game, onPlay }: { game: GameSummaryDto; onPlay: () => void }) {
  const titleId = useId()
  const { tone } = gameVisual(game.iconKey)
  const playable = gameModule(game.key) !== null
  // The summary has no description; the detail is cached and reused when a room is created.
  const description = useGameDetail(game.key).data?.description
  return (
    <article
      aria-labelledby={titleId}
      className="group flex flex-col overflow-hidden rounded-[22px] border-2 border-border bg-card shadow-card transition-all hover:-translate-y-1 motion-reduce:hover:translate-y-0"
    >
      <div className="relative aspect-[2.3/1] w-full overflow-hidden border-b-2 border-border">
        <GameBanner
          iconKey={game.iconKey}
          className="transition-transform duration-500 group-hover:scale-105 motion-reduce:group-hover:scale-100"
        />
      </div>

      <div className="flex flex-1 flex-col gap-4 p-6">
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
          <h2 id={titleId} className="text-2xl font-semibold tracking-tight text-foreground">
            {game.name}
          </h2>
          <span className="flex items-center gap-1.5 text-xs font-bold text-muted-foreground">
            <Users className="size-4 text-primary" aria-hidden />
            {game.minPlayers} - {game.maxPlayers} players
          </span>
        </div>

        {description && (
          <p className="text-sm font-semibold leading-relaxed text-muted-foreground">
            {description}
          </p>
        )}

        <div className="mt-auto flex items-center justify-between gap-3 border-t-2 border-dashed border-border pt-4">
          <span className="text-sm font-bold text-muted-foreground">{game.tagline}</span>
          {playable ? (
            <Button
              size="icon"
              variant={TONE_STYLES[tone].variant}
              onClick={onPlay}
              aria-label={`Play ${game.name}`}
              className="size-11"
            >
              <ArrowRight className="size-5 stroke-[2.5]" aria-hidden />
            </Button>
          ) : (
            <Badge variant="muted">Not available in this version</Badge>
          )}
        </div>
      </div>
    </article>
  )
}
