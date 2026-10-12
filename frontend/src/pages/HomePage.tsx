import { ArrowRight, Computer, Heart, Palette, Pencil, Plus, Users } from 'lucide-react'
import { useState } from 'react'
import { NavLink } from 'react-router'

import { DinoHeroIllustration } from '@/components/DinoHeroIllustration'
import { CreateRoomModal } from '@/components/modals/CreateRoomModal'
import { JoinRoomModal } from '@/components/modals/JoinRoomModal'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { SystemStatus } from '@/features/status/SystemStatus'

export function HomePage() {
  const [createModalOpen, setCreateModalOpen] = useState(false)
  const [joinModalOpen, setJoinModalOpen] = useState(false)
  const [initialGame, setInitialGame] = useState<'draw-guess' | 'telephone'>('draw-guess')

  const openCreateFor = (game: 'draw-guess' | 'telephone') => {
    setInitialGame(game)
    setCreateModalOpen(true)
  }

  return (
    <div className="flex flex-col gap-10 py-2 sm:gap-12">
      {/* Hero Section */}
      <section className="flex flex-col items-center gap-6 text-center">
        {/* Main Title & Subtitle */}
        <div className="flex flex-col items-center gap-2">
          <h1 className="text-5xl font-black tracking-tight sm:text-6xl md:text-7xl">
            <span className="text-foreground">Dino</span>
            <span className="text-orange-500 dark:text-orange-400">Party</span>
          </h1>
          <p className="text-xl font-bold tracking-tight text-foreground sm:text-2xl md:text-3xl">
            Draw. Guess. Imagine. Together.
          </p>
        </div>

        {/* Value Proposition */}
        <p className="max-w-xl text-base text-muted-foreground leading-relaxed sm:text-lg">
          Play fun drawing and creative games with your friends. No accounts, no downloads — just
          good times.
        </p>

        {/* Hero CTAs */}
        <div className="flex flex-wrap items-center justify-center gap-4 pt-1">
          <Button
            size="xl"
            onClick={() => openCreateFor('draw-guess')}
            className="rounded-full bg-emerald-500 text-white font-bold text-lg h-14 px-8 shadow-lg shadow-emerald-500/25 hover:bg-emerald-600 cursor-pointer"
          >
            <Plus className="size-5 stroke-[3]" />
            <span>Create a Room</span>
          </Button>

          <Button
            variant="outline"
            size="xl"
            onClick={() => setJoinModalOpen(true)}
            className="rounded-full bg-white dark:bg-card border-2 border-stone-200 dark:border-stone-700 text-foreground font-bold text-lg h-14 px-8 shadow-xs hover:bg-stone-50 hover:border-stone-300 cursor-pointer"
          >
            <span>Join a Room</span>
            <ArrowRight className="size-5 stroke-[2.5]" />
          </Button>
        </div>

        {/* Prehistoric Hero Graphic */}
        <div className="w-full pt-4">
          <DinoHeroIllustration />
        </div>

        {/* Quick Game Cards */}
        <div className="grid w-full max-w-4xl grid-cols-1 gap-5 text-left sm:grid-cols-2">
          {/* Draw & Guess Card */}
          <Card className="group relative flex flex-col justify-between overflow-hidden rounded-3xl border border-stone-200/90 bg-white p-6 shadow-sm transition-all hover:border-emerald-500/50 hover:shadow-md dark:border-stone-800 dark:bg-card">
            <div>
              <div className="flex items-start justify-between gap-4">
                <div className="flex size-14 shrink-0 items-center justify-center rounded-2xl bg-amber-100/80 text-amber-600 transition-transform group-hover:scale-105 dark:bg-amber-950/60 dark:text-amber-400">
                  <Palette className="size-7" />
                </div>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => openCreateFor('draw-guess')}
                  aria-label="Start Draw & Guess"
                  className="size-10 rounded-full bg-stone-100 text-stone-700 transition-colors group-hover:bg-emerald-500 group-hover:text-white dark:bg-stone-800 dark:text-stone-300"
                >
                  <ArrowRight className="size-4 stroke-[2.5]" />
                </Button>
              </div>

              <div className="mt-4 flex flex-col gap-1.5">
                <h3 className="text-xl font-black text-foreground">Draw & Guess</h3>
                <p className="text-sm text-muted-foreground leading-relaxed">
                  Take turns drawing and guessing fun words with your friends.
                </p>
              </div>
            </div>

            <div className="mt-5 flex items-center pt-3 border-t border-border/50">
              <NavLink
                to="/games"
                className="text-xs font-bold text-emerald-600 hover:text-emerald-700 dark:text-emerald-400 hover:underline flex items-center gap-1"
              >
                <span>Game info & rules</span>
                <span>&rarr;</span>
              </NavLink>
            </div>
          </Card>

          {/* Dino Telephone Card */}
          <Card className="group relative flex flex-col justify-between overflow-hidden rounded-3xl border border-stone-200/90 bg-white p-6 shadow-sm transition-all hover:border-orange-500/50 hover:shadow-md dark:border-stone-800 dark:bg-card">
            <div>
              <div className="flex items-start justify-between gap-4">
                <div className="flex size-14 shrink-0 items-center justify-center rounded-2xl bg-orange-100/80 text-orange-600 transition-transform group-hover:scale-105 dark:bg-orange-950/60 dark:text-orange-400">
                  <Pencil className="size-7" />
                </div>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => openCreateFor('telephone')}
                  aria-label="Start Dino Telephone"
                  className="size-10 rounded-full bg-stone-100 text-stone-700 transition-colors group-hover:bg-orange-500 group-hover:text-white dark:bg-stone-800 dark:text-stone-300"
                >
                  <ArrowRight className="size-4 stroke-[2.5]" />
                </Button>
              </div>

              <div className="mt-4 flex flex-col gap-1.5">
                <h3 className="text-xl font-black text-foreground">Dino Telephone</h3>
                <p className="text-sm text-muted-foreground leading-relaxed">
                  A creative chain of drawings and descriptions. See how it evolves!
                </p>
              </div>
            </div>

            <div className="mt-5 flex items-center pt-3 border-t border-border/50">
              <NavLink
                to="/games"
                className="text-xs font-bold text-orange-600 hover:text-orange-700 dark:text-orange-400 hover:underline flex items-center gap-1"
              >
                <span>Game info & rules</span>
                <span>&rarr;</span>
              </NavLink>
            </div>
          </Card>
        </div>

        {/* Trust & Social Proof Badges */}
        <div className="flex flex-wrap items-center justify-center gap-6 pt-2 text-xs font-bold text-stone-600 dark:text-stone-400">
          <div className="flex items-center gap-2">
            <Users className="size-4 text-emerald-600 dark:text-emerald-400" />
            <span>2 - 12 players</span>
          </div>
          <div className="flex items-center gap-2">
            <Computer className="size-4 text-emerald-600 dark:text-emerald-400" />
            <span>Play in your browser</span>
          </div>
          <div className="flex items-center gap-2">
            <Heart className="size-4 text-rose-500" />
            <span>Free to play</span>
          </div>
        </div>
      </section>

      {/* Backend Connectivity Status Drawer / Card (Preserves observability & tests) */}
      <section className="mx-auto w-full max-w-md pt-6 border-t border-border/60">
        <SystemStatus />
      </section>

      {/* Modals */}
      <CreateRoomModal
        open={createModalOpen}
        onOpenChange={setCreateModalOpen}
        initialGame={initialGame}
      />
      <JoinRoomModal open={joinModalOpen} onOpenChange={setJoinModalOpen} />
    </div>
  )
}
