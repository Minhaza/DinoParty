import * as React from 'react'
import { Check, Copy, Globe, Lock, Palette, Pencil, Sparkles } from 'lucide-react'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'

export interface CreateRoomModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  initialGame?: 'draw-guess' | 'telephone'
}

function CreateRoomForm({
  initialGame,
  onClose,
}: {
  initialGame: 'draw-guess' | 'telephone'
  onClose: () => void
}) {
  const [selectedGame, setSelectedGame] = React.useState<'draw-guess' | 'telephone'>(initialGame)
  const [roomName, setRoomName] = React.useState('Jurassic Fun')
  const [isEditingName, setIsEditingName] = React.useState(false)
  const [rounds, setRounds] = React.useState('5')
  const [roundTime, setRoundTime] = React.useState('90')
  const [maxPlayers, setMaxPlayers] = React.useState('12')
  const [roomPrivacy, setRoomPrivacy] = React.useState<'public' | 'private'>('public')
  const [copied, setCopied] = React.useState(false)
  const [createdNotice, setCreatedNotice] = React.useState(false)

  const demoRoomCode = 'AB3D7'

  const handleCopyCode = () => {
    navigator.clipboard?.writeText(demoRoomCode)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  const handleCreate = () => {
    setCreatedNotice(true)
  }

  return (
    <>
      <DialogHeader>
        <div className="flex items-center gap-2 text-primary">
          <Sparkles className="size-5" />
          <span className="text-xs font-bold uppercase tracking-wider">Host A Game</span>
        </div>
        <DialogTitle>Create a Room</DialogTitle>
        <DialogDescription>Customize your game settings and invite your friends.</DialogDescription>
      </DialogHeader>

      {createdNotice ? (
        <div className="flex flex-col items-center gap-4 py-8 text-center animate-in zoom-in-95">
          <div className="flex size-16 items-center justify-center rounded-full bg-emerald-500/15 text-emerald-600 dark:text-emerald-400">
            <Check className="size-8 stroke-[2.5]" />
          </div>
          <div>
            <h3 className="text-xl font-bold text-foreground">Room "{roomName}" Ready!</h3>
            <p className="mt-1 text-sm text-muted-foreground">
              Demo code: <span className="font-mono font-bold text-primary">{demoRoomCode}</span>
            </p>
            <p className="mt-2 text-xs text-muted-foreground max-w-sm">
              Live multiplayer room sockets will connect in Phase 3. You can explore the design and
              demo layouts below!
            </p>
          </div>
          <div className="flex gap-2">
            <Button onClick={onClose}>Awesome, got it!</Button>
          </div>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-6 py-2 md:grid-cols-2">
          {/* Left Column: Settings */}
          <div className="flex flex-col gap-5">
            {/* Game selection */}
            <div className="flex flex-col gap-2">
              <label className="text-xs font-bold uppercase tracking-wider text-muted-foreground">
                Game
              </label>
              <div className="grid grid-cols-2 gap-2">
                <button
                  type="button"
                  onClick={() => setSelectedGame('draw-guess')}
                  className={cn(
                    'flex items-center gap-2.5 rounded-2xl border p-3 text-left transition-all cursor-pointer',
                    selectedGame === 'draw-guess'
                      ? 'border-primary bg-primary/10 ring-2 ring-primary/40 font-semibold'
                      : 'border-border/80 bg-background/80 hover:bg-muted/60',
                  )}
                >
                  <div className="flex size-8 shrink-0 items-center justify-center rounded-full bg-emerald-500/20 text-emerald-600">
                    <Palette className="size-4" />
                  </div>
                  <span className="text-xs font-bold leading-tight">Draw & Guess</span>
                </button>

                <button
                  type="button"
                  onClick={() => setSelectedGame('telephone')}
                  className={cn(
                    'flex items-center gap-2.5 rounded-2xl border p-3 text-left transition-all cursor-pointer',
                    selectedGame === 'telephone'
                      ? 'border-accent bg-accent/10 ring-2 ring-accent/40 font-semibold'
                      : 'border-border/80 bg-background/80 hover:bg-muted/60',
                  )}
                >
                  <div className="flex size-8 shrink-0 items-center justify-center rounded-full bg-orange-500/20 text-orange-600">
                    <Pencil className="size-4" />
                  </div>
                  <span className="text-xs font-bold leading-tight">Dino Telephone</span>
                </button>
              </div>
            </div>

            {/* Room Settings */}
            <div className="flex flex-col gap-3">
              <label className="text-xs font-bold uppercase tracking-wider text-muted-foreground">
                Room Settings
              </label>

              {/* Rounds */}
              <div className="flex items-center justify-between gap-4">
                <span className="text-sm font-medium text-foreground">Rounds</span>
                <select
                  value={rounds}
                  onChange={(e) => setRounds(e.target.value)}
                  className="h-9 rounded-xl border border-input bg-background px-3 text-sm font-semibold focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                >
                  <option value="3">3 rounds</option>
                  <option value="5">5 rounds (Standard)</option>
                  <option value="8">8 rounds</option>
                  <option value="10">10 rounds</option>
                </select>
              </div>

              {/* Round Time */}
              <div className="flex items-center justify-between gap-4">
                <span className="text-sm font-medium text-foreground">Round Time</span>
                <select
                  value={roundTime}
                  onChange={(e) => setRoundTime(e.target.value)}
                  className="h-9 rounded-xl border border-input bg-background px-3 text-sm font-semibold focus-visible:outline-none focus-visible:ring-2 focus-primary"
                >
                  <option value="60">60 seconds</option>
                  <option value="75">75 seconds</option>
                  <option value="90">90 seconds (Default)</option>
                  <option value="120">120 seconds</option>
                </select>
              </div>

              {/* Max Players */}
              <div className="flex items-center justify-between gap-4">
                <span className="text-sm font-medium text-foreground">Max Players</span>
                <select
                  value={maxPlayers}
                  onChange={(e) => setMaxPlayers(e.target.value)}
                  className="h-9 rounded-xl border border-input bg-background px-3 text-sm font-semibold focus-visible:outline-none focus-visible:ring-2 focus-primary"
                >
                  <option value="4">4 players</option>
                  <option value="8">8 players</option>
                  <option value="12">12 players (Party)</option>
                  <option value="16">16 players</option>
                </select>
              </div>

              {/* Room Privacy */}
              <div className="flex items-center justify-between gap-4">
                <span className="text-sm font-medium text-foreground">Room Privacy</span>
                <select
                  value={roomPrivacy}
                  onChange={(e) => setRoomPrivacy(e.target.value as 'public' | 'private')}
                  className="h-9 rounded-xl border border-input bg-background px-3 text-sm font-semibold focus-visible:outline-none focus-visible:ring-2 focus-primary"
                >
                  <option value="public">Public (anyone can join)</option>
                  <option value="private">Private (code only)</option>
                </select>
              </div>
            </div>
          </div>

          {/* Right Column: Room Preview Card */}
          <div className="flex flex-col justify-between rounded-3xl border border-border/80 bg-secondary/40 p-5">
            <div className="flex flex-col gap-4">
              <span className="text-xs font-bold uppercase tracking-wider text-muted-foreground">
                Room Preview
              </span>

              {/* Prehistoric Banner */}
              <div className="relative h-28 w-full overflow-hidden rounded-2xl bg-gradient-to-br from-emerald-400 via-teal-500 to-amber-300 p-3 shadow-inner">
                <div className="absolute inset-0 bg-radial from-transparent to-black/20" />
                <div className="relative flex h-full flex-col justify-between text-white">
                  <Badge
                    variant="secondary"
                    className="self-start bg-white/80 text-foreground backdrop-blur-xs"
                  >
                    {selectedGame === 'draw-guess' ? 'Draw & Guess' : 'Dino Telephone'}
                  </Badge>
                  <div className="flex items-center gap-1.5 text-xs font-bold drop-shadow-sm">
                    {roomPrivacy === 'public' ? (
                      <Globe className="size-3.5" />
                    ) : (
                      <Lock className="size-3.5" />
                    )}
                    <span>{roomPrivacy === 'public' ? 'Public Lobby' : 'Invite Only'}</span>
                  </div>
                </div>
              </div>

              {/* Editable Name */}
              <div className="flex items-center justify-between">
                {isEditingName ? (
                  <Input
                    value={roomName}
                    onChange={(e) => setRoomName(e.target.value)}
                    onBlur={() => setIsEditingName(false)}
                    onKeyDown={(e) => e.key === 'Enter' && setIsEditingName(false)}
                    autoFocus
                    className="h-9 text-base font-bold"
                  />
                ) : (
                  <button
                    type="button"
                    onClick={() => setIsEditingName(true)}
                    className="group flex items-center gap-2 text-left font-bold text-lg text-foreground hover:text-primary transition-colors cursor-pointer"
                  >
                    <span>{roomName}</span>
                    <Pencil className="size-3.5 text-muted-foreground group-hover:text-primary" />
                  </button>
                )}
              </div>

              {/* Room Code Pill */}
              <div className="flex items-center justify-between rounded-2xl border border-border/80 bg-card p-3 shadow-2xs">
                <div className="flex flex-col">
                  <span className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground">
                    Room Code
                  </span>
                  <span className="font-mono text-xl font-extrabold tracking-widest text-primary">
                    {demoRoomCode}
                  </span>
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleCopyCode}
                  aria-label="Copy demo room code"
                  className="gap-1.5 rounded-full"
                >
                  {copied ? (
                    <Check className="size-3.5 text-emerald-600" />
                  ) : (
                    <Copy className="size-3.5" />
                  )}
                  <span>{copied ? 'Copied!' : 'Copy'}</span>
                </Button>
              </div>
            </div>

            {/* Action Button */}
            <Button
              variant={selectedGame === 'draw-guess' ? 'default' : 'accent'}
              size="lg"
              onClick={handleCreate}
              className="mt-6 w-full text-base font-bold"
            >
              + Create Room
            </Button>
          </div>
        </div>
      )}

      <DialogFooter>
        {!createdNotice && (
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
        )}
      </DialogFooter>
    </>
  )
}

export function CreateRoomModal({
  open,
  onOpenChange,
  initialGame = 'draw-guess',
}: CreateRoomModalProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <CreateRoomForm
          key={`${initialGame}-${String(open)}`}
          initialGame={initialGame}
          onClose={() => onOpenChange(false)}
        />
      </DialogContent>
    </Dialog>
  )
}
