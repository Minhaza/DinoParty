import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Copy, Crown, LogOut, Users } from 'lucide-react'
import * as React from 'react'
import { Link, useNavigate, useParams } from 'react-router'

import { ApiError } from '@/api/client'
import { describeError } from '@/api/errors'
import { getRoom, isRoomDto, joinRoom, leaveRoom, roomQueryKey } from '@/api/rooms'
import type { GuestDto, RoomDto, RoomPreviewDto } from '@/api/types'
import { DinoAvatar } from '@/components/DinoAvatar'
import { Badge } from '@/components/ui/badge'
import { Button, buttonVariants } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { RequireGuest } from '@/features/guest/RequireGuest'
import { GAME_CONFIGS } from '@/features/rooms/gameConfig'
import { RoomSettingsForm } from '@/features/rooms/RoomSettingsForm'
import { useAuthStore } from '@/stores/authStore'

/** Lobby polls the REST room endpoint every 2 s until the realtime layer replaces it (Phase 3). */
const POLL_MS = 2000

export function LobbyPage() {
  const { code = '' } = useParams()
  return (
    <section className="mx-auto flex w-full max-w-3xl flex-col gap-6">
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
    refetchInterval: POLL_MS,
    retry: false,
  })
  const data = query.data

  // Keep the "back to my room" hint aligned with what the server says about membership.
  const isMember = data !== undefined && isRoomDto(data)
  const isNonMember = data !== undefined && !isRoomDto(data)
  React.useEffect(() => {
    if (isMember && lastRoomCode !== code) setLastRoomCode(code)
    if (isNonMember && lastRoomCode === code) setLastRoomCode(null)
  }, [isMember, isNonMember, lastRoomCode, code, setLastRoomCode])

  // Announce host changes (e.g. the host left) to assistive tech, derived during render.
  const hostId = data !== undefined && isRoomDto(data) ? data.hostGuestId : null
  const [prevHostId, setPrevHostId] = React.useState(hostId)
  const [notice, setNotice] = React.useState('')
  if (hostId !== prevHostId) {
    setPrevHostId(hostId)
    if (hostId !== null && prevHostId !== null && data !== undefined && isRoomDto(data)) {
      const newHost = data.players.find((p) => p.guestId === hostId)
      setNotice(
        hostId === guest.id
          ? 'You are now the host.'
          : `${newHost?.nickname ?? 'Someone'} is now the host.`,
      )
    }
  }

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
    <MemberLobby room={data} guest={guest} notice={notice} />
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

function MemberLobby({ room, guest, notice }: { room: RoomDto; guest: GuestDto; notice: string }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const setLastRoomCode = useAuthStore((s) => s.setLastRoomCode)
  const [copied, setCopied] = React.useState(false)
  const isHost = room.hostGuestId === guest.id
  const game = GAME_CONFIGS[room.gameKey]

  const leave = useMutation({
    mutationFn: () => leaveRoom(room.code),
    onSuccess: () => {
      setLastRoomCode(null)
      queryClient.removeQueries({ queryKey: roomQueryKey(room.code) })
      void navigate('/')
    },
  })

  const copyInvite = async () => {
    try {
      await navigator.clipboard.writeText(room.inviteUrl)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      setCopied(false)
    }
  }

  const missing = Math.max(0, room.minPlayers - room.players.length)

  return (
    <>
      <Card>
        <CardHeader className="gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="secondary">{game.name}</Badge>
            <Badge variant="muted">
              {room.status === 'WAITING' ? 'Waiting for players' : room.status}
            </Badge>
          </div>
          <CardTitle as="h1" className="flex flex-wrap items-baseline gap-3">
            <span>Lobby</span>
            <span
              aria-label={`Room code ${room.code.split('').join(' ')}`}
              className="font-mono text-3xl font-extrabold tracking-widest text-primary"
            >
              {room.code}
            </span>
          </CardTitle>
          <CardDescription>Share the code or invite link with your friends.</CardDescription>
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="outline" size="sm" onClick={() => void copyInvite()}>
              {copied ? (
                <Check className="size-3.5 text-emerald-600" aria-hidden />
              ) : (
                <Copy className="size-3.5" aria-hidden />
              )}
              Copy invite link
            </Button>
            <span aria-live="polite" className="text-xs font-medium text-emerald-600">
              {copied ? 'Invite link copied.' : ''}
            </span>
          </div>
        </CardHeader>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle as="h2" className="flex items-center gap-2">
            <Users className="size-5" aria-hidden />
            Players ({room.players.length}/{room.maxPlayers})
          </CardTitle>
          {missing > 0 && (
            <CardDescription>
              {game.name} needs at least {room.minPlayers} players; waiting for {missing} more.
            </CardDescription>
          )}
        </CardHeader>
        <CardContent>
          <ul aria-label="Players" className="flex flex-col gap-2">
            {room.players.map((player) => (
              <li
                key={player.guestId}
                className="flex items-center gap-3 rounded-2xl border border-border/70 bg-background/70 p-2.5"
              >
                <DinoAvatar avatarKey={player.avatarKey} size="md" alt="" />
                <span className="min-w-0 flex-1 truncate font-semibold">{player.nickname}</span>
                {player.guestId === guest.id && <Badge variant="outline">You</Badge>}
                {player.isHost && (
                  <Badge variant="warning">
                    <Crown className="size-3" aria-hidden />
                    Host
                  </Badge>
                )}
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>

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
            <RoomSettingsForm room={room} />
          ) : (
            <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-2 text-sm">
              <dt className="text-muted-foreground">Max players</dt>
              <dd className="font-semibold">{room.maxPlayers}</dd>
              {game.fields.map((field) => (
                <React.Fragment key={field.key}>
                  <dt className="text-muted-foreground">{field.label}</dt>
                  <dd className="font-semibold">{String(room.settings[field.key] ?? '–')}</dd>
                </React.Fragment>
              ))}
            </dl>
          )}
        </CardContent>
      </Card>

      <p aria-live="polite" role="status" className="min-h-5 text-sm font-medium text-foreground">
        {notice}
      </p>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          Starting a game arrives in a later phase; for now you can gather players and tune
          settings.
        </p>
        <Button variant="outline" onClick={() => leave.mutate()} disabled={leave.isPending}>
          <LogOut className="size-4" aria-hidden />
          Leave room
        </Button>
      </div>
      {leave.isError && (
        <p role="alert" className="text-sm font-medium text-destructive">
          {describeError(leave.error)}
        </p>
      )}
    </>
  )
}
