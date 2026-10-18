import { ArrowRight, Heart, Monitor, Plus, Users } from 'lucide-react'
import { useState } from 'react'

import type { GameKey, GameSummaryDto } from '@/api/types'
import { HeroScene } from '@/components/art/HeroScene'
import { CreateRoomModal } from '@/components/modals/CreateRoomModal'
import { JoinRoomModal } from '@/components/modals/JoinRoomModal'
import { Button } from '@/components/ui/button'
import { gameModule, gameVisual, TONE_STYLES } from '@/features/games/gameModules'
import { useEnabledGames } from '@/features/games/useGames'
import { SystemStatus } from '@/features/status/SystemStatus'

export function HomePage() {
  const [createModalOpen, setCreateModalOpen] = useState(false)
  const [joinModalOpen, setJoinModalOpen] = useState(false)
  const [initialGame, setInitialGame] = useState<GameKey | undefined>(undefined)
  // Featured games are the ones a room can be created for from this client.
  const featured = (useEnabledGames().data ?? []).filter((g) => gameModule(g.key))
  const playerRange =
    featured.length > 0
      ? `${Math.min(...featured.map((g) => g.minPlayers))} - ${Math.max(...featured.map((g) => g.maxPlayers))} players`
      : null

  const openCreateFor = (game: GameKey | undefined) => {
    setInitialGame(game)
    setCreateModalOpen(true)
  }

  return (
    <>
      <section className="relative isolate flex min-h-[calc(100svh-4.25rem)] flex-col items-center overflow-hidden px-4 pb-8 pt-10 sm:pt-14">
        <HeroScene />

        <div className="relative z-10 flex w-full max-w-3xl flex-col items-center gap-4 text-center">
          <h1 className="text-[clamp(3.5rem,11vw,7.5rem)] leading-none font-bold tracking-tight drop-shadow-[0_4px_0_rgb(255_255_255_/_0.55)]">
            <span className="text-foreground">Dino</span>
            <span className="text-accent">Party</span>
          </h1>
          <p className="font-display text-2xl font-semibold text-foreground sm:text-3xl">
            Draw. Guess. Imagine. Together.
          </p>
          <p className="max-w-lg text-balance text-base font-semibold leading-relaxed text-foreground/80 sm:text-lg">
            Play fun drawing and creative games with your friends. No accounts, no downloads — just
            good times.
          </p>

          <div className="flex flex-wrap items-center justify-center gap-4 pt-2">
            <Button size="xl" onClick={() => openCreateFor(undefined)}>
              <Plus className="size-5 stroke-[3]" aria-hidden />
              <span>Create a Room</span>
            </Button>
            <Button variant="outline" size="xl" onClick={() => setJoinModalOpen(true)}>
              <span>Join a Room</span>
              <ArrowRight className="size-5 stroke-[2.5]" aria-hidden />
            </Button>
          </div>
        </div>

        {/* Featured games */}
        <div className="relative z-10 mt-10 grid w-full max-w-4xl grid-cols-1 gap-4 sm:grid-cols-2 lg:mt-auto lg:pt-16">
          {featured.map((game) => (
            <FeaturedGame key={game.key} game={game} onStart={() => openCreateFor(game.key)} />
          ))}
        </div>

        <ul className="relative z-10 mt-6 flex flex-wrap items-center justify-center gap-x-6 gap-y-2 rounded-full bg-card/80 px-6 py-2 text-xs font-bold text-foreground backdrop-blur-sm">
          {playerRange && (
            <li className="flex items-center gap-2">
              <Users className="size-4 text-primary" aria-hidden />
              {playerRange}
            </li>
          )}
          <li className="flex items-center gap-2">
            <Monitor className="size-4 text-primary" aria-hidden />
            Play in your browser
          </li>
          <li className="flex items-center gap-2">
            <Heart className="size-4 text-accent" aria-hidden />
            Free to play
          </li>
        </ul>
      </section>

      {/* Backend connectivity (observability) */}
      <section className="mx-auto w-full max-w-md px-4 py-6">
        <SystemStatus />
      </section>

      <CreateRoomModal
        open={createModalOpen}
        onOpenChange={setCreateModalOpen}
        initialGame={initialGame}
      />
      <JoinRoomModal open={joinModalOpen} onOpenChange={setJoinModalOpen} />
    </>
  )
}

function FeaturedGame({ game, onStart }: { game: GameSummaryDto; onStart: () => void }) {
  const { Badge, tone } = gameVisual(game.iconKey)
  return (
    <div className="group flex items-center gap-4 rounded-[22px] border-2 border-border bg-card/95 p-4 shadow-card backdrop-blur-sm transition-all hover:-translate-y-1 motion-reduce:hover:translate-y-0">
      <div className="shrink-0 transition-transform group-hover:-rotate-6 group-hover:scale-105 motion-reduce:group-hover:transform-none">
        <Badge className="size-14" />
      </div>
      <div className="min-w-0 flex-1 text-left">
        <h2 className="text-xl font-semibold text-foreground">{game.name}</h2>
        <p className="text-sm font-semibold leading-snug text-muted-foreground">{game.tagline}</p>
      </div>
      <Button
        size="icon"
        variant={TONE_STYLES[tone].variant}
        onClick={onStart}
        aria-label={`Start ${game.name}`}
      >
        <ArrowRight className="size-5 stroke-[2.5]" aria-hidden />
      </Button>
    </div>
  )
}
