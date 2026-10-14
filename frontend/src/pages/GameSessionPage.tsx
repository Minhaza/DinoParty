import * as React from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router'
import { Loader2 } from 'lucide-react'

import { closeRoom, getRoom, isRoomDto, kickPlayer, leaveRoom, roomQueryKey } from '@/api/rooms'
import type { GuestDto, RoomDto } from '@/api/types'
import { buttonVariants } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { RequireGuest } from '@/features/guest/RequireGuest'
import { useRealtimeRoom } from '@/features/realtime/useRealtimeRoom'
import { GAME_CONFIGS } from '@/features/rooms/gameConfig'
import { GameSessionLayout } from '@/features/session/GameSessionLayout'
import { GameStage } from '@/features/session/GameStage'
import { useAuthStore } from '@/stores/authStore'
import { useGameSessionStore } from '@/stores/gameSessionStore'
import { useRoomStore } from '@/stores/roomStore'

export function GameSessionPage() {
  const { code } = useParams<{ code: string }>()

  if (!code) {
    return <Notice title="Missing room" body="No room code was specified." />
  }

  return (
    <RequireGuest>
      {(guest) => <GuestSessionRoute code={code.toUpperCase()} guest={guest} />}
    </RequireGuest>
  )
}

function GuestSessionRoute({
  code,
  guest,
}: {
  code: string
  guest: GuestDto
}) {
  const queryClient = useQueryClient()
  const roomQuery = useQuery({
    queryKey: roomQueryKey(code),
    queryFn: ({ signal }) => getRoom(code, signal),
  })

  if (roomQuery.isLoading) {
    return (
      <div className="flex min-h-[50vh] flex-col items-center justify-center gap-3">
        <Loader2 className="size-8 animate-spin text-primary" />
        <p className="text-sm text-muted-foreground font-medium">Connecting to session…</p>
      </div>
    )
  }

  if (roomQuery.isError || !roomQuery.data) {
    return (
      <Notice
        title="Could not load room"
        body="We couldn't connect to this game room. It may have been closed or expired."
      />
    )
  }

  const roomData = roomQuery.data
  if (!isRoomDto(roomData)) {
    // Guest is not a member of this room yet
    return (
      <Card className="max-w-md mx-auto my-12">
        <CardHeader>
          <CardTitle>Join Room First</CardTitle>
          <CardDescription>
            You need to join room {code} before participating in the game session.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Link to={`/rooms/${code}`} className={buttonVariants()}>
            Go to Lobby
          </Link>
        </CardContent>
      </Card>
    )
  }

  return <ActiveSessionView initialRoom={roomData} guest={guest} queryClient={queryClient} />
}

function ActiveSessionView({
  initialRoom,
  guest,
  queryClient,
}: {
  initialRoom: RoomDto
  guest: GuestDto
  queryClient: ReturnType<typeof useQueryClient>
}) {
  const navigate = useNavigate()
  const setLastRoomCode = useAuthStore((s) => s.setLastRoomCode)
  const resetRoomStore = useRoomStore((s) => s.reset)

  const {
    room: realtimeRoom,
    isHost,
    connectionStatus,
    terminalReason,
    reconnectAttempt,
    isOnline,
    lastError,
    clearLastError,
    voteRematch,
  } = useRealtimeRoom(initialRoom.code, true, guest.id, initialRoom)

  const session = useGameSessionStore()
  const currentRoom = realtimeRoom ?? initialRoom
  const gameConfig = GAME_CONFIGS[currentRoom.gameKey] ?? {
    name: 'Game Session',
    description: 'Active party game session',
  }

  // If room returns to WAITING status, navigate back to lobby
  React.useEffect(() => {
    if (currentRoom.status === 'WAITING') {
      void navigate(`/rooms/${currentRoom.code}`)
    }
  }, [currentRoom.status, currentRoom.code, navigate])

  const leave = useMutation({
    mutationFn: () => leaveRoom(currentRoom.code),
    onSuccess: () => {
      setLastRoomCode(null)
      resetRoomStore()
      queryClient.removeQueries({ queryKey: roomQueryKey(currentRoom.code) })
      void navigate('/')
    },
  })

  const close = useMutation({
    mutationFn: () => closeRoom(currentRoom.code),
    onSuccess: () => {
      setLastRoomCode(null)
      resetRoomStore()
      queryClient.removeQueries({ queryKey: roomQueryKey(currentRoom.code) })
      void navigate('/')
    },
  })

  const handleKickPlayer = async (guestId: string) => {
    try {
      await kickPlayer(currentRoom.code, guestId)
    } catch (err) {
      console.error('Failed to kick player', err)
    }
  }

  if (currentRoom.status === 'CLOSED') {
    return <Notice title="Room closed" body={`Room ${currentRoom.code} has been closed.`} />
  }

  return (
    <GameSessionLayout
      roomCode={currentRoom.code}
      gameTitle={gameConfig.name}
      phase={session.phase}
      deadlineAt={session.deadlineAt}
      players={currentRoom.players}
      currentGuestId={guest.id}
      hostGuestId={currentRoom.hostGuestId}
      scores={session.scores}
      standings={session.results?.standings}
      connectionStatus={connectionStatus}
      terminalReason={terminalReason}
      reconnectAttempt={reconnectAttempt}
      isOnline={isOnline}
      isHost={isHost}
      lastError={lastError}
      interruptedReason={session.abortReason || session.interruptedSession?.endReason}
      isServerRestart={session.interruptedSession?.endReason === 'SERVER_RESTART'}
      onClearError={clearLastError}
      onDismissInterrupted={() => session.clearInterruptedNotice()}
      onLeaveRoom={() => leave.mutateAsync()}
      onCloseRoom={isHost ? () => close.mutateAsync() : undefined}
      onKickPlayer={isHost ? handleKickPlayer : undefined}
    >
      <GameStage
        roomStatus={currentRoom.status}
        gameKey={currentRoom.gameKey}
        players={currentRoom.players}
        currentGuestId={guest.id}
        isHost={isHost}
        onVoteRematch={voteRematch}
        onReturnToLobby={() => navigate(`/rooms/${currentRoom.code}`)}
      />
    </GameSessionLayout>
  )
}

function Notice({ title, body }: { title: string; body: string }) {
  return (
    <Card className="max-w-md mx-auto my-12">
      <CardHeader>
        <CardTitle as="h1">{title}</CardTitle>
        <CardDescription>{body}</CardDescription>
      </CardHeader>
      <CardContent>
        <Link to="/" className={buttonVariants()}>
          Back to home
        </Link>
      </CardContent>
    </Card>
  )
}
