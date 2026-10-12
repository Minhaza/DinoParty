import { ArrowRight, Clock, Palette, Trophy, Users } from 'lucide-react'
import { useState } from 'react'

import { DinoAvatar } from '@/components/DinoAvatar'
import { CreateRoomModal } from '@/components/modals/CreateRoomModal'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { cn } from '@/lib/utils'

export function GamesPage() {
  const [createModalOpen, setCreateModalOpen] = useState(false)
  const [selectedGameForModal, setSelectedGameForModal] = useState<'draw-guess' | 'telephone'>(
    'draw-guess',
  )
  const [activePreviewTab, setActivePreviewTab] = useState<
    'catalog' | 'lobby' | 'game' | 'album' | 'results'
  >('catalog')

  const handleStartGame = (game: 'draw-guess' | 'telephone') => {
    setSelectedGameForModal(game)
    setCreateModalOpen(true)
  }

  return (
    <div className="flex flex-col gap-8 py-2 sm:gap-10">
      {/* Top Navigation Mode Tabs (Catalog vs Layout Previews) */}
      <div className="flex flex-col items-center justify-between gap-4 sm:flex-row">
        <div className="flex flex-col gap-1 text-center sm:text-left">
          <h1 className="text-4xl font-black tracking-tight text-foreground sm:text-5xl">
            Our Games
          </h1>
          <p className="text-base font-medium text-stone-600 dark:text-stone-300 sm:text-lg">
            Choose a game and start a room with your friends!
          </p>
        </div>

        {/* View Switcher: Catalog vs Reference Mockup Previews */}
        <div className="flex items-center gap-1 rounded-full border border-stone-200 bg-white/90 p-1 shadow-2xs dark:border-stone-800 dark:bg-card">
          <button
            type="button"
            onClick={() => setActivePreviewTab('catalog')}
            className={cn(
              'rounded-full px-3.5 py-1.5 text-xs font-bold transition-all cursor-pointer',
              activePreviewTab === 'catalog'
                ? 'bg-emerald-500 text-white shadow-xs'
                : 'text-stone-600 hover:text-foreground dark:text-stone-300',
            )}
          >
            Catalog
          </button>
          <button
            type="button"
            onClick={() => setActivePreviewTab('lobby')}
            className={cn(
              'rounded-full px-3.5 py-1.5 text-xs font-bold transition-all cursor-pointer',
              activePreviewTab === 'lobby'
                ? 'bg-emerald-500 text-white shadow-xs'
                : 'text-stone-600 hover:text-foreground dark:text-stone-300',
            )}
          >
            Lobby UI
          </button>
          <button
            type="button"
            onClick={() => setActivePreviewTab('game')}
            className={cn(
              'rounded-full px-3.5 py-1.5 text-xs font-bold transition-all cursor-pointer',
              activePreviewTab === 'game'
                ? 'bg-emerald-500 text-white shadow-xs'
                : 'text-stone-600 hover:text-foreground dark:text-stone-300',
            )}
          >
            Canvas UI
          </button>
          <button
            type="button"
            onClick={() => setActivePreviewTab('album')}
            className={cn(
              'rounded-full px-3.5 py-1.5 text-xs font-bold transition-all cursor-pointer',
              activePreviewTab === 'album'
                ? 'bg-emerald-500 text-white shadow-xs'
                : 'text-stone-600 hover:text-foreground dark:text-stone-300',
            )}
          >
            Album UI
          </button>
          <button
            type="button"
            onClick={() => setActivePreviewTab('results')}
            className={cn(
              'rounded-full px-3.5 py-1.5 text-xs font-bold transition-all cursor-pointer',
              activePreviewTab === 'results'
                ? 'bg-emerald-500 text-white shadow-xs'
                : 'text-stone-600 hover:text-foreground dark:text-stone-300',
            )}
          >
            Results UI
          </button>
        </div>
      </div>

      {/* Main Catalog View */}
      {activePreviewTab === 'catalog' && (
        <div className="grid grid-cols-1 gap-8 md:grid-cols-2">
          {/* Card 1: Draw & Guess */}
          <Card className="group flex flex-col justify-between overflow-hidden rounded-3xl border border-stone-200/90 bg-white shadow-sm transition-all hover:border-emerald-500/60 hover:shadow-lg dark:border-stone-800 dark:bg-card">
            <div>
              {/* Scene Banner Illustration */}
              <div className="relative h-52 w-full overflow-hidden bg-gradient-to-br from-cyan-100 via-teal-100 to-amber-100 p-6 dark:from-cyan-950/40 dark:via-teal-950/30 dark:to-amber-950/30">
                <div className="flex h-full items-center justify-between">
                  <div className="flex items-center gap-3">
                    <DinoAvatar
                      avatarKey="dino-rex"
                      size="xl"
                      className="shadow-md ring-4 ring-white dark:ring-stone-800"
                    />
                    {/* Easel */}
                    <div className="flex flex-col items-center rounded-2xl border border-amber-300 bg-white/95 p-2.5 shadow-sm dark:bg-stone-900">
                      <span className="text-2xl animate-pulse">💡</span>
                      <span className="text-[11px] font-black text-emerald-600 dark:text-emerald-400">
                        Volcano!
                      </span>
                    </div>
                  </div>
                  <div className="flex flex-col items-end gap-1">
                    <span className="rounded-full bg-emerald-500/20 px-3 py-1 text-xs font-bold text-emerald-800 dark:text-emerald-300">
                      Classic
                    </span>
                  </div>
                </div>
              </div>

              {/* Content */}
              <CardContent className="flex flex-col gap-4 pt-6">
                <div className="flex items-center justify-between">
                  <h2 className="text-2xl font-black tracking-tight text-foreground">
                    Draw & Guess
                  </h2>
                  <div className="flex items-center gap-3 text-xs font-bold text-stone-500 dark:text-stone-400">
                    <span className="flex items-center gap-1">
                      <Users className="size-3.5 text-emerald-600" /> 3 - 12 players
                    </span>
                    <span className="flex items-center gap-1">
                      <Clock className="size-3.5 text-emerald-600" /> 5 - 10 min
                    </span>
                  </div>
                </div>

                {/* Category Tags */}
                <div className="flex flex-wrap gap-2">
                  <Badge
                    variant="secondary"
                    className="bg-stone-100 text-stone-700 dark:bg-stone-800 dark:text-stone-300 border-none font-semibold"
                  >
                    Drawing
                  </Badge>
                  <Badge
                    variant="secondary"
                    className="bg-stone-100 text-stone-700 dark:bg-stone-800 dark:text-stone-300 border-none font-semibold"
                  >
                    Guessing
                  </Badge>
                  <Badge
                    variant="secondary"
                    className="bg-stone-100 text-stone-700 dark:bg-stone-800 dark:text-stone-300 border-none font-semibold"
                  >
                    Classic
                  </Badge>
                </div>

                {/* Description */}
                <p className="text-sm text-muted-foreground leading-relaxed">
                  Take turns drawing a word while others guess. The more people guess correctly, the
                  more points you earn!
                </p>
              </CardContent>
            </div>

            {/* Footer Action */}
            <div className="flex items-center justify-between border-t border-border/50 p-6 pt-4">
              <span className="text-xs font-semibold text-muted-foreground">
                Easy to learn • High energy
              </span>
              <Button
                size="icon"
                onClick={() => handleStartGame('draw-guess')}
                aria-label="Play Draw & Guess"
                className="size-11 rounded-full bg-emerald-500 text-white shadow-md shadow-emerald-500/25 hover:bg-emerald-600 cursor-pointer"
              >
                <ArrowRight className="size-5 stroke-[2.5]" />
              </Button>
            </div>
          </Card>

          {/* Card 2: Dino Telephone */}
          <Card className="group flex flex-col justify-between overflow-hidden rounded-3xl border border-stone-200/90 bg-white shadow-sm transition-all hover:border-orange-500/60 hover:shadow-lg dark:border-stone-800 dark:bg-card">
            <div>
              {/* Scene Banner Illustration */}
              <div className="relative h-52 w-full overflow-hidden bg-gradient-to-br from-orange-100 via-amber-100 to-purple-100 p-6 dark:from-orange-950/40 dark:via-amber-950/30 dark:to-purple-950/30">
                <div className="flex h-full items-center justify-between">
                  <div className="flex items-center -space-x-3">
                    <DinoAvatar
                      avatarKey="dino-tricera"
                      size="xl"
                      className="shadow-md ring-4 ring-white dark:ring-stone-800"
                    />
                    <DinoAvatar
                      avatarKey="dino-ptera"
                      size="xl"
                      className="shadow-md ring-4 ring-white dark:ring-stone-800"
                    />
                  </div>
                  <div className="flex flex-col items-end gap-1">
                    <span className="rounded-full bg-orange-500/20 px-3 py-1 text-xs font-bold text-orange-800 dark:text-orange-300">
                      Creative
                    </span>
                  </div>
                </div>
              </div>

              {/* Content */}
              <CardContent className="flex flex-col gap-4 pt-6">
                <div className="flex items-center justify-between">
                  <h2 className="text-2xl font-black tracking-tight text-foreground">
                    Dino Telephone
                  </h2>
                  <div className="flex items-center gap-3 text-xs font-bold text-stone-500 dark:text-stone-400">
                    <span className="flex items-center gap-1">
                      <Users className="size-3.5 text-orange-600" /> 4 - 10 players
                    </span>
                    <span className="flex items-center gap-1">
                      <Clock className="size-3.5 text-orange-600" /> 10 - 15 min
                    </span>
                  </div>
                </div>

                {/* Category Tags */}
                <div className="flex flex-wrap gap-2">
                  <Badge
                    variant="secondary"
                    className="bg-stone-100 text-stone-700 dark:bg-stone-800 dark:text-stone-300 border-none font-semibold"
                  >
                    Drawing
                  </Badge>
                  <Badge
                    variant="secondary"
                    className="bg-stone-100 text-stone-700 dark:bg-stone-800 dark:text-stone-300 border-none font-semibold"
                  >
                    Descriptions
                  </Badge>
                  <Badge
                    variant="secondary"
                    className="bg-stone-100 text-stone-700 dark:bg-stone-800 dark:text-stone-300 border-none font-semibold"
                  >
                    Creative
                  </Badge>
                </div>

                {/* Description */}
                <p className="text-sm text-muted-foreground leading-relaxed">
                  A fun chain of drawings and descriptions. Watch how your original idea transforms
                  into something completely different!
                </p>
              </CardContent>
            </div>

            {/* Footer Action */}
            <div className="flex items-center justify-between border-t border-border/50 p-6 pt-4">
              <span className="text-xs font-semibold text-muted-foreground">
                Zero drawing skill needed
              </span>
              <Button
                size="icon"
                onClick={() => handleStartGame('telephone')}
                aria-label="Play Dino Telephone"
                className="size-11 rounded-full bg-orange-500 text-white shadow-md shadow-orange-500/25 hover:bg-orange-600 cursor-pointer"
              >
                <ArrowRight className="size-5 stroke-[2.5]" />
              </Button>
            </div>
          </Card>
        </div>
      )}

      {/* Demo Layout Previews for Review (Reflecting the bottom panels in the reference image) */}
      {activePreviewTab === 'lobby' && <DemoLobbyPreview />}
      {activePreviewTab === 'game' && <DemoCanvasPreview />}
      {activePreviewTab === 'album' && <DemoAlbumPreview />}
      {activePreviewTab === 'results' && <DemoResultsPreview />}

      {/* Create Room Modal */}
      <CreateRoomModal
        open={createModalOpen}
        onOpenChange={setCreateModalOpen}
        initialGame={selectedGameForModal}
      />
    </div>
  )
}

/** Demo Lobby Preview component reflecting Bottom-Center in reference mockup */
function DemoLobbyPreview() {
  const players = [
    { name: 'RexPlayer', role: 'Host', ready: true, avatar: 'dino-rex' as const },
    { name: 'TriceraTop', role: 'Player', ready: true, avatar: 'dino-tricera' as const },
    { name: 'PteraFly', role: 'Player', ready: true, avatar: 'dino-ptera' as const },
    { name: 'StegoStar', role: 'Player', ready: false, avatar: 'dino-stego' as const },
    { name: 'DinoMite', role: 'Player', ready: true, avatar: 'dino-mite' as const },
    { name: 'LavaLizard', role: 'Player', ready: false, avatar: 'dino-lizard' as const },
  ]

  return (
    <div className="flex flex-col gap-6 rounded-3xl border border-stone-200/90 bg-white p-6 shadow-sm dark:border-stone-800 dark:bg-card">
      <div className="flex items-center justify-between border-b border-border/60 pb-4">
        <div className="flex items-center gap-3">
          <Badge
            variant="outline"
            className="font-mono text-sm font-bold tracking-widest text-emerald-600 border-emerald-500/30 bg-emerald-50 dark:bg-emerald-950/40"
          >
            AB3D7
          </Badge>
          <span className="text-lg font-bold text-foreground">Jurassic Fun</span>
        </div>
        <Badge variant="secondary">Demo Lobby Layout Preview</Badge>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        {/* Players List */}
        <div className="flex flex-col gap-3 lg:col-span-2">
          <div className="flex items-center justify-between">
            <h3 className="text-xs font-bold uppercase tracking-wider text-muted-foreground">
              Players (6/12)
            </h3>
            <span className="text-xs text-muted-foreground">Waiting for 2 more...</span>
          </div>

          <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
            {players.map((p) => (
              <div
                key={p.name}
                className="flex items-center justify-between rounded-2xl border border-stone-200/80 bg-stone-50/60 p-3 shadow-2xs dark:border-stone-800 dark:bg-stone-900/60"
              >
                <div className="flex items-center gap-3">
                  <DinoAvatar avatarKey={p.avatar} size="md" />
                  <div className="flex flex-col">
                    <span className="text-sm font-bold text-foreground">{p.name}</span>
                    <span className="text-[11px] text-muted-foreground">{p.role}</span>
                  </div>
                </div>

                <span
                  className={cn(
                    'rounded-full px-2.5 py-1 text-xs font-bold',
                    p.ready
                      ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950/80 dark:text-emerald-300'
                      : 'bg-stone-100 text-stone-500 dark:bg-stone-800 dark:text-stone-400',
                  )}
                >
                  {p.ready ? 'Ready' : 'Not ready'}
                </span>
              </div>
            ))}
          </div>

          {/* Chat Preview */}
          <div className="mt-4 flex flex-col gap-2 rounded-2xl border border-border/60 bg-stone-50/80 p-3.5 dark:bg-stone-900/50">
            <span className="text-xs font-bold text-muted-foreground">Room Chat</span>
            <div className="flex flex-col gap-1.5 text-xs">
              <p>
                <span className="font-bold text-orange-600">TriceraTop:</span> Can't wait!
              </p>
              <p>
                <span className="font-bold text-purple-600">PteraFly:</span> This is going to be
                fun! 🦕
              </p>
              <p>
                <span className="font-bold text-emerald-600">RexPlayer:</span> Let's go!
              </p>
            </div>
          </div>
        </div>

        {/* Room Info & Controls */}
        <div className="flex flex-col justify-between rounded-2xl border border-stone-200/80 bg-stone-50/80 p-5 dark:border-stone-800 dark:bg-stone-900/50">
          <div className="flex flex-col gap-4">
            <span className="text-xs font-bold uppercase tracking-wider text-muted-foreground">
              Game
            </span>
            <div className="flex items-center gap-2 rounded-xl bg-white p-3 shadow-2xs dark:bg-card">
              <Palette className="size-5 text-emerald-600" />
              <span className="font-bold text-sm">Draw & Guess</span>
            </div>

            <div className="flex flex-col gap-2 text-xs">
              <div className="flex justify-between text-muted-foreground">
                <span>Rounds</span>
                <span className="font-bold text-foreground">5</span>
              </div>
              <div className="flex justify-between text-muted-foreground">
                <span>Round Time</span>
                <span className="font-bold text-foreground">90 seconds</span>
              </div>
              <div className="flex justify-between text-muted-foreground">
                <span>Max Players</span>
                <span className="font-bold text-foreground">12</span>
              </div>
            </div>
          </div>

          <Button
            size="lg"
            className="mt-6 w-full font-bold bg-emerald-500 hover:bg-emerald-600 text-white rounded-full"
          >
            Start Game
          </Button>
        </div>
      </div>
    </div>
  )
}

/** Demo Canvas Preview component reflecting Bottom-Right in reference mockup */
function DemoCanvasPreview() {
  return (
    <div className="flex flex-col gap-4 rounded-3xl border border-stone-200/90 bg-white p-6 shadow-sm dark:border-stone-800 dark:bg-card">
      <div className="flex items-center justify-between border-b border-border/60 pb-3">
        <div className="flex items-center gap-3">
          <span className="font-mono text-sm font-bold text-emerald-600">Room: AB3D7</span>
          <span className="text-sm font-bold text-foreground">Round 2/5</span>
        </div>
        <div className="flex items-center gap-2 rounded-full bg-emerald-100 px-3.5 py-1 text-sm font-extrabold text-emerald-700 dark:bg-emerald-950/80 dark:text-emerald-300">
          <Clock className="size-4" />
          <span>00:48</span>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-4">
        {/* Left Toolbar */}
        <div className="flex flex-col gap-4 rounded-2xl bg-stone-50 p-4 dark:bg-stone-900/50">
          <div className="flex flex-col gap-1 rounded-2xl bg-orange-100 p-3.5 text-orange-900 dark:bg-orange-950/80 dark:text-orange-200">
            <span className="text-[10px] font-bold uppercase tracking-wider text-orange-700 dark:text-orange-300">
              Your Word
            </span>
            <span className="text-xl font-black">Volcano</span>
            <span className="text-[10px] opacity-80">Don't show this to other players!</span>
          </div>

          {/* Palette */}
          <div className="flex flex-col gap-2">
            <span className="text-xs font-bold text-muted-foreground">Colors</span>
            <div className="flex flex-wrap gap-2">
              {[
                '#EF4444',
                '#F97316',
                '#FACC15',
                '#22C55E',
                '#06B6D4',
                '#3B82F6',
                '#A855F7',
                '#1E293B',
              ].map((c) => (
                <div
                  key={c}
                  className="size-6 rounded-full border-2 border-white shadow-xs cursor-pointer hover:scale-110 transition-transform"
                  style={{ backgroundColor: c }}
                />
              ))}
            </div>
          </div>
        </div>

        {/* Center Canvas Preview with volcano artwork */}
        <div className="relative flex min-h-[320px] flex-col items-center justify-center rounded-2xl border-2 border-dashed border-stone-200 bg-amber-50/40 p-6 dark:border-stone-800 dark:bg-stone-900/30 lg:col-span-2">
          {/* Volcano vector drawing preview */}
          <svg viewBox="0 0 200 160" className="w-48 h-40">
            {/* Magma bursts */}
            <path
              d="M100 50 Q100 20 85 10"
              stroke="#EF4444"
              strokeWidth="4"
              strokeLinecap="round"
              fill="none"
            />
            <path
              d="M100 50 Q105 15 115 12"
              stroke="#F97316"
              strokeWidth="4"
              strokeLinecap="round"
              fill="none"
            />
            <path
              d="M100 50 Q120 25 130 30"
              stroke="#FACC15"
              strokeWidth="3"
              strokeLinecap="round"
              fill="none"
            />
            <path
              d="M100 50 Q80 25 70 30"
              stroke="#EF4444"
              strokeWidth="3"
              strokeLinecap="round"
              fill="none"
            />
            {/* Volcano mountain */}
            <polygon points="50,140 90,60 110,60 150,140" fill="#CA8A04" opacity="0.8" />
            <polygon points="50,140 90,60 100,60 70,140" fill="#A16207" opacity="0.6" />
            <path d="M90 60 Q100 68 110 60 L108 72 Q100 80 92 72 Z" fill="#EF4444" />
            {/* Ground bushes */}
            <path
              d="M30 140 Q45 125 60 140 Q75 125 90 140"
              stroke="#16A34A"
              strokeWidth="4"
              fill="none"
              strokeLinecap="round"
            />
            <path
              d="M120 140 Q135 125 150 140 Q165 125 180 140"
              stroke="#16A34A"
              strokeWidth="4"
              fill="none"
              strokeLinecap="round"
            />
          </svg>
          <span className="mt-2 text-xs font-semibold text-stone-500 dark:text-stone-400">
            HTML5 Drawing Board (Phase 4 Canvas Engine)
          </span>
        </div>

        {/* Right Scoreboard */}
        <div className="flex flex-col gap-3 rounded-2xl bg-stone-50 p-4 text-xs dark:bg-stone-900/50">
          <span className="font-bold uppercase tracking-wider text-muted-foreground">Players</span>
          <div className="flex flex-col gap-2">
            <div className="flex items-center justify-between font-bold rounded-xl bg-emerald-50 p-2 dark:bg-emerald-950/40">
              <span className="text-emerald-700 dark:text-emerald-300">1. TriceraTop</span>
              <span>420 pts</span>
            </div>
            <div className="flex items-center justify-between p-2">
              <span>2. PteraFly</span>
              <span>310 pts</span>
            </div>
            <div className="flex items-center justify-between p-2">
              <span>3. StegoStar</span>
              <span>280 pts</span>
            </div>
            <div className="flex items-center justify-between p-2">
              <span>4. DinoMite</span>
              <span>250 pts</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

/** Demo Telephone Album Preview component reflecting Album in reference mockup */
function DemoAlbumPreview() {
  return (
    <div className="flex flex-col gap-6 rounded-3xl border border-stone-200/90 bg-white p-6 shadow-sm dark:border-stone-800 dark:bg-card text-center">
      <div className="flex flex-col items-center gap-1">
        <h3 className="text-2xl font-black text-foreground">The Dino Telephone Album</h3>
        <p className="text-xs text-muted-foreground">Watch how the original idea evolved!</p>
      </div>

      <div className="flex flex-wrap items-center justify-center gap-4 py-4">
        {/* Step 1 */}
        <div className="flex flex-col items-center gap-2 rounded-2xl border border-stone-200/80 bg-stone-50 p-4 shadow-sm w-52 dark:border-stone-800 dark:bg-stone-900/50">
          <span className="rounded-full bg-emerald-100 px-2.5 py-0.5 text-[10px] font-bold uppercase text-emerald-700 dark:bg-emerald-950/80 dark:text-emerald-300">
            Original
          </span>
          <p className="text-xs font-bold text-foreground">"A dinosaur surfing on a huge wave"</p>
          <div className="flex items-center gap-1.5 mt-2">
            <DinoAvatar avatarKey="dino-rex" size="xs" />
            <span className="text-[11px] text-muted-foreground">RexPlayer</span>
          </div>
        </div>

        <ArrowRight className="size-5 text-stone-400" />

        {/* Step 2 */}
        <div className="flex flex-col items-center gap-2 rounded-2xl border border-stone-200/80 bg-stone-50 p-4 shadow-sm w-52 dark:border-stone-800 dark:bg-stone-900/50">
          <span className="rounded-full bg-orange-100 px-2.5 py-0.5 text-[10px] font-bold uppercase text-orange-700 dark:bg-orange-950/80 dark:text-orange-300">
            Drawing 1
          </span>
          <span className="text-4xl py-1">🏄‍♂️🦖🌊</span>
          <div className="flex items-center gap-1.5 mt-2">
            <DinoAvatar avatarKey="dino-tricera" size="xs" />
            <span className="text-[11px] text-muted-foreground">TriceraTop</span>
          </div>
        </div>

        <ArrowRight className="size-5 text-stone-400" />

        {/* Step 3 */}
        <div className="flex flex-col items-center gap-2 rounded-2xl border border-stone-200/80 bg-stone-50 p-4 shadow-sm w-52 dark:border-stone-800 dark:bg-stone-900/50">
          <span className="rounded-full bg-purple-100 px-2.5 py-0.5 text-[10px] font-bold uppercase text-purple-700 dark:bg-purple-950/80 dark:text-purple-300">
            Description 1
          </span>
          <p className="text-xs font-bold text-foreground">"A cool dino riding the ocean"</p>
          <div className="flex items-center gap-1.5 mt-2">
            <DinoAvatar avatarKey="dino-ptera" size="xs" />
            <span className="text-[11px] text-muted-foreground">PteraFly</span>
          </div>
        </div>
      </div>
    </div>
  )
}

/** Demo Results Preview component reflecting Results in reference mockup */
function DemoResultsPreview() {
  return (
    <div className="flex flex-col items-center gap-6 rounded-3xl border border-stone-200/90 bg-white p-6 shadow-sm dark:border-stone-800 dark:bg-card text-center">
      <div className="flex items-center gap-2 text-amber-500">
        <Trophy className="size-7" />
        <h3 className="text-3xl font-black text-foreground">Game Results</h3>
      </div>

      <div className="grid grid-cols-1 gap-6 w-full max-w-xl sm:grid-cols-3">
        {/* 2nd Place */}
        <div className="flex flex-col items-center gap-2 rounded-2xl bg-stone-50 p-4 order-2 sm:order-1 border border-stone-200/80 dark:bg-stone-900/50 dark:border-stone-800">
          <Badge variant="outline">🥈 2nd Place</Badge>
          <DinoAvatar avatarKey="dino-rex" size="lg" />
          <span className="font-bold text-sm">RexPlayer</span>
          <span className="text-xs text-muted-foreground">1,120 pts</span>
        </div>

        {/* 1st Place */}
        <div className="flex flex-col items-center gap-2 rounded-2xl bg-amber-50 border-2 border-amber-400 p-5 order-1 sm:order-2 shadow-md dark:bg-amber-950/40">
          <Badge variant="warning">👑 1st Place</Badge>
          <DinoAvatar avatarKey="dino-tricera" size="xl" />
          <span className="font-extrabold text-base text-foreground">TriceraTop</span>
          <span className="text-sm font-bold text-emerald-600 dark:text-emerald-400">
            1,240 pts
          </span>
        </div>

        {/* 3rd Place */}
        <div className="flex flex-col items-center gap-2 rounded-2xl bg-stone-50 p-4 order-3 border border-stone-200/80 dark:bg-stone-900/50 dark:border-stone-800">
          <Badge variant="outline">🥉 3rd Place</Badge>
          <DinoAvatar avatarKey="dino-ptera" size="lg" />
          <span className="font-bold text-sm">PteraFly</span>
          <span className="text-xs text-muted-foreground">980 pts</span>
        </div>
      </div>

      <div className="flex gap-3">
        <Button variant="outline" className="rounded-full">
          Back to Lobby
        </Button>
        <Button className="rounded-full bg-emerald-500 hover:bg-emerald-600 text-white">
          Play Again
        </Button>
      </div>
    </div>
  )
}
