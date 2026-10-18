import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowRight } from 'lucide-react'
import * as React from 'react'
import { Link, useNavigate } from 'react-router'

import { ApiError } from '@/api/client'
import { describeError } from '@/api/errors'
import { joinRoom, roomQueryKey } from '@/api/rooms'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useAuthStore } from '@/stores/authStore'

import { RequireGuest } from '../guest/RequireGuest'
import { ROOM_CODE_PATTERN } from './roomCode'

export interface JoinRoomFormProps {
  initialCode?: string
  onJoined?: () => void
  onCancel?: () => void
}

export function JoinRoomForm(props: JoinRoomFormProps) {
  return <RequireGuest>{() => <JoinRoomFields {...props} />}</RequireGuest>
}

function JoinRoomFields({ initialCode = '', onJoined, onCancel }: JoinRoomFormProps) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const lastRoomCode = useAuthStore((s) => s.lastRoomCode)
  const setLastRoomCode = useAuthStore((s) => s.setLastRoomCode)
  const [code, setCode] = React.useState(initialCode.toUpperCase())
  const [localError, setLocalError] = React.useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (roomCode: string) => joinRoom(roomCode),
    onSuccess: (room) => {
      queryClient.setQueryData(roomQueryKey(room.code), room)
      setLastRoomCode(room.code)
      onJoined?.()
      void navigate(`/rooms/${room.code}`)
    },
  })

  const alreadyInRoom =
    mutation.error instanceof ApiError && mutation.error.code === 'ALREADY_IN_ROOM'
  const error = localError ?? (mutation.isError ? describeError(mutation.error) : null)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const clean = code.trim().toUpperCase()
    if (!ROOM_CODE_PATTERN.test(clean)) {
      setLocalError('Room codes are 6 characters (letters A–Z and digits 2–9).')
      return
    }
    setLocalError(null)
    mutation.mutate(clean)
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
      <div className="flex flex-col gap-2">
        <label
          htmlFor="join-code-input"
          className="text-xs font-bold uppercase tracking-wider text-muted-foreground"
        >
          Room code
        </label>
        <Input
          id="join-code-input"
          value={code}
          onChange={(e) => {
            setCode(e.target.value.toUpperCase())
            setLocalError(null)
            mutation.reset()
          }}
          placeholder="e.g. AB3D7K"
          maxLength={6}
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          aria-invalid={error !== null}
          aria-describedby={error ? 'join-code-error' : undefined}
          className="h-14 text-center font-mono text-2xl font-black uppercase tracking-widest"
        />
        {error && (
          <p id="join-code-error" role="alert" className="text-xs font-medium text-destructive">
            {error}
            {alreadyInRoom && lastRoomCode && (
              <>
                {' '}
                <Link className="underline" to={`/rooms/${lastRoomCode}`}>
                  Go to room {lastRoomCode}
                </Link>
              </>
            )}
          </p>
        )}
      </div>
      <div className="flex justify-end gap-2">
        {onCancel && (
          <Button type="button" variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
        )}
        <Button type="submit" size="lg" disabled={mutation.isPending}>
          <span>{mutation.isPending ? 'Joining…' : 'Join room'}</span>
          <ArrowRight className="size-4 stroke-[2.5]" aria-hidden />
        </Button>
      </div>
    </form>
  )
}
