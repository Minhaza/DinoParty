import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AlertCircle,
  Check,
  CheckCircle2,
  Copy,
  Crown,
  Loader2,
  LogOut,
  Play,
  RotateCcw,
  UserX,
  Users,
  WifiOff,
} from 'lucide-react'
import * as React from 'react'
import { Link, useNavigate, useParams } from 'react-router'

import { ApiError } from '@/api/client'
import { describeError } from '@/api/errors'
import { getRoom, isRoomDto, joinRoom, kickPlayer, leaveRoom, roomQueryKey } from '@/api/rooms'
import type { GuestDto, PlayerDto, RoomDto, RoomPreviewDto } from '@/api/types'
import { DinoAvatar } from '@/components/DinoAvatar'
import { Badge } from '@/components/ui/badge'
import { Button, buttonVariants } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { RequireGuest } from '@/features/guest/RequireGuest'
import { useRealtimeRoom } from '@/features/realtime/useRealtimeRoom'
import { GAME_CONFIGS } from '@/features/rooms/gameConfig'
import { RoomSettingsForm } from '@/features/rooms/RoomSettingsForm'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/stores/authStore'
import { useRoomStore } from '@/stores/roomStore'

export function LobbyPage() {
  const { code = '' } = useParams()
  return (
    <section className="mx-auto flex w-full max-w-5xl flex-col gap-5">
      <RequireGuest>{(guest) => <Lobby code={code.toUpperCase()} guest={guest} />}</RequireGuest>
    </section>
  )
}

function Lobby({ code, guest }: { code: string; guest: GuestDto }) {
  const lastRoomCode = useAuthStore((s) => s.lastRoomCode)
  const setLastRoomCode = useAuthStore((s) => s.setLastRoomCode)

  const query = useQuery({
    queryKey: roomQueryKey(code),
    queryFn: ({ signal }) => getRoom(code, signal),
    retry: false,
  })
  const data = query.data

  const isMember = data !== undefined && isRoomDto(data)
  const isNonMember = data !== undefined && !isRoomDto(data)

  React.useEffect(() => {
    if (isMember && lastRoomCode !== code) setLastRoomCode(code)
    if (isNonMember && lastRoomCode === code) setLastRoomCode(null)
  }, [isMember, isNonMember, lastRoomCode, code, setLastRoomCode])

  if (query.isPending) {
    return (
      <p role="status" className="py-10 text-center text-muted-foreground">
        Loading room {code}…
      </p>
    )
  }

  if (query.isError && data === undefined) {
    if (query.error instanceof ApiError && query.error.code === 'ROOM_NOT_FOUND') {
      return <Notice title="Room not found" body={`No room with the code ${code} exists.`} />
    }
    return (
      <div role="alert" className="flex flex-col items-center gap-3 py-10 text-center">
        <p className="font-medium text-destructive">{describeError(query.error)}</p>
        <Button variant="secondary" onClick={() => void query.refetch()}>
          Try again
        </Button>
      </div>
    )
  }

  if (data === undefined) return null

  return isRoomDto(data) ? (
    <MemberLobby initialRoom={data} guest={guest} />
  ) : (
    <PreviewLobby preview={data} />
  )
}

function Notice({ title, body }: { title: string; body: string }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle as="h1">{title}</CardTitle>
        <CardDescription>{body}</CardDescription>
      </CardHeader>
      <CardContent>
        <Link to="/join" className={buttonVariants()}>
          Join another room
        </Link>
      </CardContent>
    </Card>
  )
}

function PreviewLobby({ preview }: { preview: RoomPreviewDto }) {
  const queryClient = useQueryClient()
  const setLastRoomCode = useAuthStore((s) => s.setLastRoomCode)
  const join = useMutation({
    mutationFn: () => joinRoom(preview.code),
    onSuccess: (room) => {
      queryClient.setQueryData(roomQueryKey(room.code), room)
      setLastRoomCode(room.code)
    },
  })
  const game = GAME_CONFIGS[preview.gameKey]

  if (preview.status === 'CLOSED') {
    return <Notice title="Room closed" body={`Room ${preview.code} has been closed.`} />
  }
  const joinable = preview.status === 'WAITING' && preview.playerCount < preview.maxPlayers

  return (
    <Card>
      <CardHeader>
        <CardTitle as="h1">Join room {preview.code}</CardTitle>
        <CardDescription>
          {game.name} hosted by {preview.hostNickname} · {preview.playerCount}/{preview.maxPlayers}{' '}
          players
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {!joinable && (
          <p role="status" className="text-sm text-muted-foreground">
            {preview.status === 'WAITING'
              ? 'This room is full.'
              : 'This room has already started playing.'}
          </p>
        )}
        {join.isError && (
          <p role="alert" className="text-sm font-medium text-destructive">
            {describeError(join.error)}
          </p>
        )}
        <div>
          <Button disabled={!joinable || join.isPending} onClick={() => join.mutate()}>
            {join.isPending ? 'Joining…' : 'Join room'}
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

function MemberLobby({ initialRoom, guest }: { initialRoom: RoomDto; guest: GuestDto }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const setLastRoomCode = useAuthStore((s) => s.setLastRoomCode)
  const resetRoomStore = useRoomStore((s) => s.reset)

  const {
    room: realtimeRoom,
    isHost,
    isReady,
    isTogglingReady,
    isStartingGame,
    startError,
    connectionStatus,
    terminalReason,
    reconnectAttempt,
    isOnline,
    lastError,
    clearLastError,
    toggleReady,
    startGame,
  } = useRealtimeRoom(initialRoom.code, true, guest.id, initialRoom)

  const currentRoom = realtimeRoom ?? initialRoom

  const [copied, setCopied] = React.useState(false)
  const [kickTarget, setKickTarget] = React.useState<string | null>(null)
  const game = GAME_CONFIGS[currentRoom.gameKey]

  // Track host changes to announce them to assistive technology
  const hostId = currentRoom.hostGuestId
  const [prevHostId, setPrevHostId] = React.useState(hostId)
  const [hostNotice, setHostNotice] = React.useState('')

  if (hostId !== prevHostId) {
    setPrevHostId(hostId)
    if (hostId && prevHostId) {
      const newHost = currentRoom.players.find((p) => p.guestId === hostId)
      setHostNotice(
        hostId === guest.id
          ? 'You are now the host.'
          : `${newHost?.nickname ?? 'Someone'} is now the host.`,
      )
    }
  }

  const leave = useMutation({
    mutationFn: () => leaveRoom(currentRoom.code),
    onSuccess: () => {
      setLastRoomCode(null)
      resetRoomStore()
      queryClient.removeQueries({ queryKey: roomQueryKey(currentRoom.code) })
      void navigate('/')
    },
  })

  const handleKick = async (guestId: string) => {
    setKickTarget(guestId)
    try {
      await kickPlayer(currentRoom.code, guestId)
    } catch (err) {
      console.error('Failed to kick player', err)
    } finally {
      setKickTarget(null)
    }
  }

  const copyInvite = async () => {
    try {
      await navigator.clipboard.writeText(currentRoom.inviteUrl)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      setCopied(false)
    }
  }

  const connectedPlayers = currentRoom.players.filter((p) => p.connection !== 'DISCONNECTED')
  const missing = Math.max(0, currentRoom.minPlayers - currentRoom.players.length)
  const allReady = currentRoom.players.length > 0 && currentRoom.players.every((p) => p.ready)
  const canStart =
    isHost &&
    currentRoom.status === 'WAITING' &&
    connectedPlayers.length >= currentRoom.minPlayers &&
    !isStartingGame

  if (currentRoom.status === 'CLOSED') {
    return <Notice title="Room closed" body={`Room ${currentRoom.code} has been closed.`} />
  }

  return (
    <>
      {/* Session Replaced Alert (Single-tab restriction) */}
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

      {/* Reconnecting Banner */}
      {connectionStatus === 'RECONNECTING' && (
        <div
          role="status"
          aria-live="polite"
          className="flex items-center gap-2.5 rounded-2xl border border-amber-500/50 bg-amber-500/10 px-4 py-2.5 text-sm font-medium text-amber-800 dark:text-amber-300"
        >
          <Loader2 className="size-4 animate-spin text-amber-600 dark:text-amber-400" />
          <span>Reconnecting to lobby… (attempt {reconnectAttempt})</span>
        </div>
      )}

      {/* Offline Alert */}
      {(!isOnline || connectionStatus === 'DISCONNECTED') &&
        terminalReason !== 'SESSION_REPLACED' && (
          <div
            role="status"
            className="flex items-center justify-between gap-3 rounded-2xl border border-border bg-muted/60 px-4 py-2.5 text-sm text-muted-foreground"
          >
            <div className="flex items-center gap-2">
              <WifiOff className="size-4 text-muted-foreground" />
              <span>You are currently offline.</span>
            </div>
          </div>
        )}

      {/* Error Banner */}
      {lastError && (
        <div
          role="alert"
          className="flex items-center justify-between gap-3 rounded-2xl border border-destructive/50 bg-destructive/10 px-4 py-2 text-sm text-destructive"
        >
          <span className="font-medium">{lastError}</span>
          <Button variant="ghost" size="sm" onClick={clearLastError} className="h-7 px-2">
            Dismiss
          </Button>
        </div>
      )}

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_320px] lg:items-start">
        <div className="flex flex-col gap-5">
          {/* Lobby header: room identity */}
          <Card>
            <CardHeader className="gap-3">
              <div className="flex flex-wrap items-center gap-2">
                <Badge variant={currentRoom.gameKey === 'draw-guess' ? 'default' : 'accent'}>
                  {game.name}
                </Badge>
                <Badge variant="muted">
                  {currentRoom.status === 'WAITING' ? 'Waiting for players' : currentRoom.status}
                </Badge>
                {connectionStatus === 'CONNECTED' && (
                  <Badge variant="success">
                    <span className="size-2 rounded-full bg-success" aria-hidden />
                    Live
                  </Badge>
                )}
              </div>
              <CardTitle as="h1" className="flex flex-wrap items-center gap-3 text-3xl sm:text-4xl">
                <span>Lobby</span>
                <span
                  aria-label={`Room code ${currentRoom.code.split('').join(' ')}`}
                  className="rounded-2xl border-2 border-dashed border-primary/60 bg-primary/10 px-4 py-1 font-display text-2xl font-bold tracking-[0.25em] text-primary sm:text-3xl"
                >
                  {currentRoom.code}
                </span>
              </CardTitle>
              <CardDescription>Share the code or invite link with your friends.</CardDescription>
              <div className="flex flex-wrap items-center gap-2">
                <Button variant="outline" size="sm" onClick={() => void copyInvite()}>
                  {copied ? (
                    <Check className="size-3.5 text-primary" aria-hidden />
                  ) : (
                    <Copy className="size-3.5" aria-hidden />
                  )}
                  Copy invite link
                </Button>
                <span aria-live="polite" className="text-xs font-bold text-primary">
                  {copied ? 'Invite link copied.' : ''}
                </span>
              </div>
            </CardHeader>
          </Card>

          {/* Players */}
          <Card>
            <CardHeader>
              <CardTitle as="h2" className="flex items-center gap-2">
                <Users className="size-5 text-primary" aria-hidden />
                Players ({currentRoom.players.length}/{currentRoom.maxPlayers})
              </CardTitle>
              {missing > 0 && (
                <CardDescription>
                  {game.name} needs at least {currentRoom.minPlayers} players; waiting for {missing}{' '}
                  more.
                </CardDescription>
              )}
            </CardHeader>
            <CardContent>
              <ul aria-label="Players" className="grid gap-3 sm:grid-cols-2">
                {currentRoom.players.map((player) => (
                  <PlayerListItem
                    key={player.guestId}
                    player={player}
                    isCurrentGuest={player.guestId === guest.id}
                    canKick={isHost && player.guestId !== guest.id}
                    isKicking={kickTarget === player.guestId}
                    onKick={() => void handleKick(player.guestId)}
                  />
                ))}
              </ul>
              {currentRoom.players.length < currentRoom.maxPlayers && (
                <p className="mt-3 rounded-2xl border-2 border-dashed border-border px-4 py-3 text-center text-sm font-bold text-muted-foreground">
                  Waiting for players…
                </p>
              )}
            </CardContent>
          </Card>

          {startError && (
            <p role="alert" className="text-sm font-bold text-destructive">
              {startError}
            </p>
          )}
        </div>

        <aside className="flex flex-col gap-5">
          {/* Ready toggle and host start */}
          <Card>
            <CardContent className="flex flex-col gap-4 pt-6">
              <Button
                variant={isReady ? 'outline' : 'default'}
                size="lg"
                onClick={() => void toggleReady()}
                disabled={isTogglingReady || currentRoom.status !== 'WAITING'}
                aria-pressed={isReady}
              >
                {isTogglingReady ? (
                  <Loader2 className="size-4 animate-spin" />
                ) : isReady ? (
                  <CheckCircle2 className="size-4 text-primary" />
                ) : (
                  <Check className="size-4" />
                )}
                {isReady ? 'Cancel ready' : 'Ready up'}
              </Button>
              <p className="text-center text-sm font-semibold text-muted-foreground">
                {isReady ? 'You are marked ready!' : 'Click ready when you are set.'}
              </p>

              {isHost && (
                <div className="flex flex-col gap-2 border-t-2 border-dashed border-border pt-4">
                  <Button
                    variant="accent"
                    size="lg"
                    onClick={() => void startGame()}
                    disabled={!canStart}
                  >
                    {isStartingGame ? (
                      <Loader2 className="size-4 animate-spin" />
                    ) : (
                      <Play className="size-4 fill-current" />
                    )}
                    {currentRoom.status === 'STARTING' ? 'Starting…' : 'Start game'}
                  </Button>
                  {missing > 0 ? (
                    <span className="text-center text-xs font-bold text-muted-foreground">
                      Needs at least {currentRoom.minPlayers} players
                    </span>
                  ) : !allReady ? (
                    <span className="text-center text-xs font-bold text-muted-foreground">
                      Waiting for players to ready up
                    </span>
                  ) : (
                    <span className="text-center text-xs font-bold text-primary">
                      All players ready!
                    </span>
                  )}
                </div>
              )}
            </CardContent>
          </Card>

          {/* Settings */}
          <Card>
            <CardHeader>
              <CardTitle as="h2">Settings</CardTitle>
              <CardDescription>
                {isHost
                  ? 'You are the host. Changes apply to everyone in the lobby.'
                  : 'Only the host can change these.'}
              </CardDescription>
            </CardHeader>
            <CardContent>
              {isHost ? (
                <RoomSettingsForm room={currentRoom} />
              ) : (
                <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-2 text-sm">
                  <dt className="font-semibold text-muted-foreground">Max players</dt>
                  <dd className="font-bold">{currentRoom.maxPlayers}</dd>
                  {game.fields.map((field) => (
                    <React.Fragment key={field.key}>
                      <dt className="font-semibold text-muted-foreground">{field.label}</dt>
                      <dd className="font-bold">
                        {String(currentRoom.settings[field.key] ?? '–')}
                      </dd>
                    </React.Fragment>
                  ))}
                </dl>
              )}
            </CardContent>
          </Card>

          <p aria-live="polite" role="status" className="min-h-5 text-sm font-bold text-foreground">
            {hostNotice}
          </p>

          <Button variant="outline" onClick={() => leave.mutate()} disabled={leave.isPending}>
            <LogOut className="size-4" aria-hidden />
            Leave room
          </Button>
          <p className="text-center text-xs font-semibold text-muted-foreground">
            Gather players and tune settings. Once ready, the host can start the game!
          </p>
        </aside>
      </div>
      {leave.isError && (
        <p role="alert" className="text-sm font-medium text-destructive">
          {describeError(leave.error)}
        </p>
      )}
    </>
  )
}

function PlayerListItem({
  player,
  isCurrentGuest,
  canKick,
  isKicking,
  onKick,
}: {
  player: PlayerDto
  isCurrentGuest: boolean
  canKick: boolean
  isKicking: boolean
  onKick: () => void
}) {
  const isDisconnected = player.connection === 'DISCONNECTED'

  return (
    <li
      className={cn(
        'flex items-center justify-between gap-3 rounded-2xl border-2 p-3 transition-all',
        isDisconnected
          ? 'border-dashed border-warning/70 bg-warning/10 opacity-80'
          : player.ready
            ? 'border-primary/60 bg-primary/10'
            : 'border-border bg-secondary/60',
      )}
    >
      <div className="flex min-w-0 items-center gap-3">
        <div className="relative shrink-0">
          <DinoAvatar
            avatarKey={player.avatarKey}
            color={player.avatarColor ?? undefined}
            size="lg"
            alt=""
            className={cn(isDisconnected && 'grayscale')}
          />
          {player.isHost && (
            <Crown
              className="absolute -right-1.5 -top-2 size-5 fill-warning text-foreground drop-shadow"
              aria-hidden
            />
          )}
        </div>
        <div className="flex min-w-0 flex-col gap-1">
          <span className="truncate font-display text-base font-semibold text-foreground">
            {player.nickname}
          </span>
          <div className="flex flex-wrap items-center gap-1.5">
            {isCurrentGuest && <Badge variant="outline">You</Badge>}
            {player.isHost && (
              <Badge variant="warning">
                <Crown className="size-3" aria-hidden />
                Host
              </Badge>
            )}
            {isDisconnected ? (
              <Badge variant="outline" className="gap-1 border-warning/70 text-xs">
                <WifiOff className="size-3" aria-hidden />
                Offline
              </Badge>
            ) : player.ready ? (
              <Badge variant="success" className="text-xs">
                <Check className="size-3" aria-hidden />
                Ready
              </Badge>
            ) : (
              <Badge variant="muted" className="text-xs">
                Not ready
              </Badge>
            )}
          </div>
          {isDisconnected && (
            <span className="text-xs font-semibold text-muted-foreground">
              Disconnected (reconnecting…)
            </span>
          )}
        </div>
      </div>

      {canKick && (
        <Button
          variant="ghost"
          size="sm"
          onClick={onKick}
          disabled={isKicking}
          aria-label={`Kick ${player.nickname}`}
          className="h-8 shrink-0 px-2 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
        >
          {isKicking ? (
            <Loader2 className="size-3.5 animate-spin" />
          ) : (
            <UserX className="size-3.5" />
          )}
        </Button>
      )}
    </li>
  )
}
