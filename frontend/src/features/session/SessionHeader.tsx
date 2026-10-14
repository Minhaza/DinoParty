import * as React from 'react'
import {
  Copy,
  Check,
  LogOut,
  Radio,
  Loader2,
  WifiOff,
  XOctagon,
  Users,
} from 'lucide-react'

import type { ConnectionStatus } from '@/features/realtime/connectionStore'
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
import { cn } from '@/lib/utils'

import { PhaseIndicator } from './PhaseIndicator'
import { ServerCountdown } from './ServerCountdown'

export interface SessionHeaderProps {
  roomCode: string
  gameTitle: string
  phase?: string | null
  round?: number | null
  totalRounds?: number | null
  deadlineAt?: string | null
  connectionStatus: ConnectionStatus
  reconnectAttempt?: number
  isOnline?: boolean
  isHost?: boolean
  onLeaveRoom: () => Promise<void> | void
  onCloseRoom?: () => Promise<void> | void
  onToggleRoster?: () => void
  showRosterToggle?: boolean
  className?: string
}

export function SessionHeader({
  roomCode,
  gameTitle,
  phase,
  round,
  totalRounds,
  deadlineAt,
  connectionStatus,
  reconnectAttempt = 0,
  isOnline = true,
  isHost = false,
  onLeaveRoom,
  onCloseRoom,
  onToggleRoster,
  showRosterToggle = false,
  className,
}: SessionHeaderProps) {
  const [copied, setCopied] = React.useState(false)
  const [showLeaveDialog, setShowLeaveDialog] = React.useState(false)
  const [showCloseDialog, setShowCloseDialog] = React.useState(false)
  const [isLeaving, setIsLeaving] = React.useState(false)
  const [isClosing, setIsClosing] = React.useState(false)

  const copyRoomCode = async () => {
    try {
      await navigator.clipboard.writeText(roomCode)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      setCopied(false)
    }
  }

  const handleLeave = async () => {
    setIsLeaving(true)
    try {
      await onLeaveRoom()
    } finally {
      setIsLeaving(false)
      setShowLeaveDialog(false)
    }
  }

  const handleClose = async () => {
    if (!onCloseRoom) return
    setIsClosing(true)
    try {
      await onCloseRoom()
    } finally {
      setIsClosing(false)
      setShowCloseDialog(false)
    }
  }

  return (
    <header
      className={cn(
        'sticky top-0 z-30 flex flex-wrap items-center justify-between gap-3 border-b border-border/60 bg-background/90 px-4 py-2.5 backdrop-blur-md',
        className,
      )}
    >
      {/* Left: Game Title & Room Code */}
      <div className="flex items-center gap-2 sm:gap-3">
        <span className="font-fredoka text-lg sm:text-xl font-bold tracking-tight text-foreground">
          {gameTitle}
        </span>

        <button
          type="button"
          onClick={copyRoomCode}
          className="group inline-flex items-center gap-1.5 rounded-lg border border-border/60 bg-muted/40 px-2 py-1 text-xs font-mono font-medium hover:bg-muted/80 transition-colors"
          title="Click to copy room code"
          aria-label={`Room code ${roomCode}. Click to copy`}
        >
          <span>{roomCode}</span>
          {copied ? (
            <Check className="size-3 text-primary" />
          ) : (
            <Copy className="size-3 text-muted-foreground group-hover:text-foreground" />
          )}
        </button>
      </div>

      {/* Middle: Phase & Countdown */}
      <div className="flex items-center gap-2.5 order-3 sm:order-2 w-full sm:w-auto justify-center">
        <PhaseIndicator phase={phase} round={round} totalRounds={totalRounds} />
        <ServerCountdown deadlineAt={deadlineAt} />
      </div>

      {/* Right: Connection & Actions */}
      <div className="flex items-center gap-2 order-2 sm:order-3">
        {/* Connection Status Badge */}
        {!isOnline || connectionStatus === 'DISCONNECTED' ? (
          <Badge variant="outline" className="gap-1 border-muted text-muted-foreground text-xs py-0.5">
            <WifiOff className="size-3" />
            <span className="hidden xs:inline">Offline</span>
          </Badge>
        ) : connectionStatus === 'RECONNECTING' ? (
          <Badge
            variant="outline"
            className="gap-1 border-amber-500/50 bg-amber-500/10 text-amber-700 dark:text-amber-300 text-xs py-0.5"
          >
            <Loader2 className="size-3 animate-spin text-amber-500" />
            <span>Reconnecting{reconnectAttempt > 0 ? ` (${reconnectAttempt})` : '…'}</span>
          </Badge>
        ) : connectionStatus === 'CONNECTED' ? (
          <Badge
            variant="outline"
            className="gap-1 border-emerald-500/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300 text-xs py-0.5"
          >
            <Radio className="size-3 text-emerald-500 animate-pulse" />
            <span className="hidden xs:inline">Live</span>
          </Badge>
        ) : null}

        {/* Mobile Roster Toggle Button */}
        {showRosterToggle && onToggleRoster && (
          <Button
            variant="outline"
            size="sm"
            className="lg:hidden size-8 p-0"
            onClick={onToggleRoster}
            aria-label="Toggle players and scores"
            title="Toggle players and scores"
          >
            <Users className="size-4" />
          </Button>
        )}

        {/* Host Close Room Button */}
        {isHost && onCloseRoom && (
          <Button
            variant="ghost"
            size="sm"
            className="text-xs text-muted-foreground hover:text-destructive gap-1 px-2.5"
            onClick={() => setShowCloseDialog(true)}
            title="Close room"
          >
            <XOctagon className="size-3.5" />
            <span className="hidden md:inline">Close</span>
          </Button>
        )}

        {/* Leave Room Button */}
        <Button
          variant="outline"
          size="sm"
          className="text-xs gap-1 px-2.5"
          onClick={() => setShowLeaveDialog(true)}
          title="Leave game session"
        >
          <LogOut className="size-3.5" />
          <span className="hidden md:inline">Leave</span>
        </Button>
      </div>

      {/* Leave Confirmation Dialog */}
      <Dialog open={showLeaveDialog} onOpenChange={setShowLeaveDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Leave Game Session?</DialogTitle>
            <DialogDescription>
              Are you sure you want to leave this game? Your progress and scores may be lost.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter className="gap-2 sm:gap-0">
            <Button variant="outline" onClick={() => setShowLeaveDialog(false)} disabled={isLeaving}>
              Stay
            </Button>
            <Button variant="destructive" onClick={handleLeave} disabled={isLeaving}>
              {isLeaving ? 'Leaving…' : 'Leave Game'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Host Close Confirmation Dialog */}
      {isHost && onCloseRoom && (
        <Dialog open={showCloseDialog} onOpenChange={setShowCloseDialog}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Close Room for Everyone?</DialogTitle>
              <DialogDescription>
                As host, closing the room will terminate the game session and kick all players back to the home screen.
              </DialogDescription>
            </DialogHeader>
            <DialogFooter className="gap-2 sm:gap-0">
              <Button variant="outline" onClick={() => setShowCloseDialog(false)} disabled={isClosing}>
                Cancel
              </Button>
              <Button variant="destructive" onClick={handleClose} disabled={isClosing}>
                {isClosing ? 'Closing…' : 'Close Room'}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
    </header>
  )
}
