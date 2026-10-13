import { ArrowRight, Clock, Users } from 'lucide-react'
import { useState } from 'react'

import type { GameKey } from '@/api/types'
import { GameBanner } from '@/components/art/gameArt'
import { CreateRoomModal } from '@/components/modals/CreateRoomModal'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { GAME_CONFIGS } from '@/features/rooms/gameConfig'

interface CatalogEntry {
  key: GameKey
  duration: string
  tags: string[]
  description: string
  tagline: string
  tone: 'draw' | 'phone'
}

const CATALOG: CatalogEntry[] = [
  {
    key: 'draw-guess',
    duration: '5 - 10 min',
    tags: ['Drawing', 'Guessing', 'Classic'],
    description:
      'Take turns drawing a word while others guess. The more people guess correctly, the more points you earn!',
    tagline: 'Easy to learn • High energy',
    tone: 'draw',
  },
  {
    key: 'telephone',
    duration: '10 - 15 min',
    tags: ['Drawing', 'Descriptions', 'Creative'],
    description:
      'A fun chain of drawings and descriptions. Watch how your original idea transforms into something completely different!',
    tagline: 'Zero drawing skill needed',
    tone: 'phone',
  },
]

export function GamesPage() {
  const [createModalOpen, setCreateModalOpen] = useState(false)
  const [selectedGame, setSelectedGame] = useState<GameKey>('draw-guess')

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

      <div className="grid grid-cols-1 gap-6 md:grid-cols-2 md:gap-8">
        {CATALOG.map((entry) => {
          const config = GAME_CONFIGS[entry.key]
          const isDraw = entry.tone === 'draw'
          return (
            <article
              key={entry.key}
              className="group flex flex-col overflow-hidden rounded-[22px] border-2 border-border bg-card shadow-card transition-all hover:-translate-y-1"
            >
              <div className="relative h-56 w-full overflow-hidden border-b-2 border-border">
                <GameBanner
                  game={entry.key}
                  className="transition-transform duration-500 group-hover:scale-105"
                />
              </div>

              <div className="flex flex-1 flex-col gap-4 p-6">
                <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
                  <h2 className="text-2xl font-semibold tracking-tight text-foreground">
                    {config.name}
                  </h2>
                  <div className="flex items-center gap-4 text-xs font-bold text-muted-foreground">
                    <span className="flex items-center gap-1.5">
                      <Users className="size-4 text-primary" aria-hidden />
                      {config.minPlayers} - {config.maxPlayers} players
                    </span>
                    <span className="flex items-center gap-1.5">
                      <Clock className="size-4 text-primary" aria-hidden />
                      {entry.duration}
                    </span>
                  </div>
                </div>

                <ul aria-label="Tags" className="flex flex-wrap gap-2">
                  {entry.tags.map((tag) => (
                    <li key={tag}>
                      <Badge variant={isDraw ? 'default' : 'accent'}>{tag}</Badge>
                    </li>
                  ))}
                </ul>

                <p className="text-sm font-semibold leading-relaxed text-muted-foreground">
                  {entry.description}
                </p>

                <div className="mt-auto flex items-center justify-between border-t-2 border-dashed border-border pt-4">
                  <span className="text-xs font-bold text-muted-foreground">{entry.tagline}</span>
                  <Button
                    size="icon"
                    variant={isDraw ? 'default' : 'accent'}
                    onClick={() => handleStartGame(entry.key)}
                    aria-label={`Play ${config.name}`}
                    className="size-11"
                  >
                    <ArrowRight className="size-5 stroke-[2.5]" aria-hidden />
                  </Button>
                </div>
              </div>
            </article>
          )
        })}
      </div>

      <CreateRoomModal
        open={createModalOpen}
        onOpenChange={setCreateModalOpen}
        initialGame={selectedGame}
      />
    </div>
  )
}
