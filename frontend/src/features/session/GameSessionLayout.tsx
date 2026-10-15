import * as React from 'react'
import { AlertCircle, RotateCcw, X, Users } from 'lucide-react'

import type { PlayerDto } from '@/api/types'
import type { ConnectionStatus } from '@/features/realtime/connectionStore'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import { GameInterruptedBanner } from './GameInterruptedBanner'
import { PlayerRoster } from './PlayerRoster'
import { SessionHeader } from './SessionHeader'

export interface GameSessionLayoutProps {
  roomCode: string
  gameTitle: string
  players: PlayerDto[]
  currentGuestId?: string | null
  hostGuestId?: string | null
  activeGuestId?: string | null
  guessedGuestIds?: string[]
  scores?: Record<string, number> | null
  connectionStatus: ConnectionStatus
  terminalReason?: string | null
  reconnectAttempt?: number
  isOnline?: boolean
  isHost?: boolean
  lastError?: string | null
  interruptedReason?: string | null
  isServerRestart?: boolean
  onClearError?: () => void
  onDismissInterrupted?: () => void
  onLeaveRoom: () => Promise<void> | void
  onCloseRoom?: () => Promise<void> | void
  onKickPlayer?: (guestId: string) => Promise<void> | void
  children: React.ReactNode
  sidebarFooter?: React.ReactNode
  className?: string
}

export function GameSessionLayout({
  roomCode,
  gameTitle,
  players,
  currentGuestId,
  hostGuestId,
  activeGuestId,
  guessedGuestIds,
  scores,
  connectionStatus,
  terminalReason,
  reconnectAttempt = 0,
  isOnline = true,
  isHost = false,
  lastError,
  interruptedReason,
  isServerRestart = false,
  onClearError,
  onDismissInterrupted,
  onLeaveRoom,
  onCloseRoom,
  onKickPlayer,
  children,
  sidebarFooter,
  className,
}: GameSessionLayoutProps) {
  const [mobileTab, setMobileTab] = React.useState<'game' | 'players'>('game')

  return (
    <div
      className={cn(
        'min-h-screen flex flex-col bg-background text-foreground lg:h-dvh lg:min-h-0 lg:overflow-hidden',
        className,
      )}
    >
      {/* Top Session Header */}
      <SessionHeader
        roomCode={roomCode}
        gameTitle={gameTitle}
        connectionStatus={connectionStatus}
        reconnectAttempt={reconnectAttempt}
        isOnline={isOnline}
        isHost={isHost}
        onLeaveRoom={onLeaveRoom}
        onCloseRoom={onCloseRoom}
        showRosterToggle={true}
        onToggleRoster={() => setMobileTab((current) => (current === 'game' ? 'players' : 'game'))}
      />

      {/* Main Container & Alerts */}
      <div className="flex-1 flex flex-col min-h-0 p-3 sm:p-4 mx-auto w-full gap-3 sm:gap-4">
        {/* Terminal Alert (Single-tab restriction) */}
        {terminalReason === 'SESSION_REPLACED' && (
          <div
            role="alert"
            className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 rounded-2xl border border-destructive/50 bg-destructive/10 p-4 text-destructive"
          >
            <div className="flex items-start gap-3">
              <AlertCircle className="size-5 shrink-0 mt-0.5" />
              <div>
                <p className="font-bold">Session opened in another window</p>
                <p className="text-sm text-foreground/80">
                  DinoParty allows one active tab per player. This tab has been disconnected.
                </p>
              </div>
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={() => window.location.reload()}
              className="self-start sm:self-auto shrink-0 gap-1.5"
            >
              <RotateCcw className="size-3.5" />
              Reopen here
            </Button>
          </div>
        )}

        {/* Server Interrupted / Aborted Banner */}
        {interruptedReason && onDismissInterrupted && (
          <GameInterruptedBanner
            reason={interruptedReason}
            isServerRestart={isServerRestart}
            onDismiss={onDismissInterrupted}
          />
        )}

        {/* Transient Game Error Alert */}
        {lastError && (
          <div
            role="alert"
            className="flex items-center justify-between gap-3 rounded-xl border border-destructive/40 bg-destructive/10 px-4 py-2.5 text-sm text-destructive"
          >
            <span>{lastError}</span>
            {onClearError && (
              <Button
                variant="ghost"
                size="icon"
                className="size-6 text-destructive hover:bg-destructive/20"
                onClick={onClearError}
                aria-label="Dismiss error"
              >
                <X className="size-3.5" />
              </Button>
            )}
          </div>
        )}

        {/* Mobile Navigation Tabs (visible below lg) */}
        <div className="flex lg:hidden items-center rounded-xl bg-muted/60 p-1 text-xs font-semibold">
          <button
            type="button"
            className={cn(
              'flex-1 py-1.5 rounded-lg transition-all text-center',
              mobileTab === 'game'
                ? 'bg-background text-foreground shadow-xs'
                : 'text-muted-foreground hover:text-foreground',
            )}
            onClick={() => setMobileTab('game')}
          >
            Game Area
          </button>
          <button
            type="button"
            className={cn(
              'flex-1 py-1.5 rounded-lg transition-all text-center flex items-center justify-center gap-1.5',
              mobileTab === 'players'
                ? 'bg-background text-foreground shadow-xs'
                : 'text-muted-foreground hover:text-foreground',
            )}
            onClick={() => setMobileTab('players')}
          >
            <Users className="size-3.5" />
            Players ({players.length})
          </button>
        </div>

        {/* Content & Sidebar Grid */}
        <div className="flex-1 grid grid-cols-1 lg:grid-cols-12 lg:grid-rows-[minmax(0,1fr)] gap-4 sm:gap-6 min-h-0">
          {/* Main Game Stage */}
          <main
            className={cn(
              'lg:col-span-8 xl:col-span-9 flex flex-col min-h-0 lg:overflow-y-auto',
              mobileTab !== 'game' && 'hidden lg:flex',
            )}
          >
            {children}
          </main>

          {/* Desktop & Mobile Sidebar */}
          <aside
            className={cn(
              'lg:col-span-4 xl:col-span-3 flex flex-col gap-4 lg:min-h-0 lg:overflow-hidden',
              mobileTab === 'game' && 'hidden lg:flex',
            )}
          >
            <div className="rounded-2xl border border-border/70 bg-card/60 p-3 sm:p-4 shadow-xs lg:max-h-[50%] lg:shrink-0 lg:overflow-y-auto">
              <PlayerRoster
                players={players}
                currentGuestId={currentGuestId}
                hostGuestId={hostGuestId}
                activeGuestId={activeGuestId}
                guessedGuestIds={guessedGuestIds}
                scores={scores}
                isHost={isHost}
                onKickPlayer={onKickPlayer}
              />
            </div>

            {sidebarFooter && <div className="flex min-h-0 flex-1 flex-col">{sidebarFooter}</div>}
          </aside>
        </div>
      </div>
    </div>
  )
}
