import * as React from 'react'
import { MessageCircle, Send } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

import { playerName } from './copy'
import { codePointLength, sanitizeChatText, validateChatText } from './textRules'
import { IMPOSTOR_CHAT_MAX, type ImpostorChatLine } from './types'

/** After an error the send button rests this long, so a rate limit is not hammered. */
export const CHAT_ERROR_COOLDOWN_MS = 1500

export interface DiscussionChatProps {
  messages: ImpostorChatLine[]
  playersById: ReadonlyMap<string, { nickname: string }>
  currentGuestId: string | null | undefined
  /** True only in the voting phase for players in the game ([DI-8]). */
  enabled: boolean
  error?: string | null
  /** Returns `false` when the line could not be sent. */
  onSend: (text: string) => boolean
}

/** Public discussion while voting ([DI-8]). Lines are plain text nodes, never HTML. */
export function DiscussionChat({
  messages,
  playersById,
  currentGuestId,
  enabled,
  error,
  onSend,
}: DiscussionChatProps) {
  const inputId = React.useId()
  const counterId = React.useId()
  const [value, setValue] = React.useState('')
  const [resting, setResting] = React.useState(false)
  const listRef = React.useRef<HTMLOListElement | null>(null)

  // A new error makes the send button rest briefly instead of letting the player spam into the limit.
  React.useEffect(() => {
    if (!error) return
    const start = setTimeout(() => setResting(true), 0)
    const stop = setTimeout(() => setResting(false), CHAT_ERROR_COOLDOWN_MS)
    return () => {
      clearTimeout(start)
      clearTimeout(stop)
    }
  }, [error])

  React.useEffect(() => {
    const list = listRef.current
    if (list) list.scrollTop = list.scrollHeight
  }, [messages.length])

  if (!enabled) return null

  const length = codePointLength(sanitizeChatText(value))
  const invalid = validateChatText(value) !== null

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (invalid || resting) return
    if (onSend(value)) setValue('')
  }

  return (
    <section
      aria-label="Discussion"
      className="flex min-h-0 flex-col gap-2 rounded-2xl border border-border bg-card p-3 shadow-xs"
    >
      <h2 className="flex items-center gap-2 px-1 font-fredoka text-base font-semibold">
        <MessageCircle aria-hidden="true" className="size-4 text-primary" />
        Talk it over
      </h2>

      <ol
        ref={listRef}
        aria-live="polite"
        aria-relevant="additions"
        aria-label="Discussion messages"
        className="flex max-h-56 min-h-24 flex-col gap-1 overflow-y-auto rounded-xl bg-muted/40 p-2 text-sm"
      >
        {messages.length === 0 && (
          <li className="text-muted-foreground">No messages yet. Who looked suspicious?</li>
        )}
        {messages.map((m, i) => (
          <li key={`${m.ts}-${m.guestId}-${i}`} className="break-words">
            <span className="font-semibold">
              {m.guestId === currentGuestId ? 'You' : playerName(playersById, m.guestId)}:
            </span>{' '}
            {m.text}
          </li>
        ))}
      </ol>

      <form onSubmit={handleSubmit} className="flex flex-col gap-1">
        <label htmlFor={inputId} className="sr-only">
          Message
        </label>
        <div className="flex items-center gap-2">
          <Input
            id={inputId}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder="Say something…"
            aria-describedby={counterId}
            aria-invalid={length > IMPOSTOR_CHAT_MAX}
            autoComplete="off"
          />
          <Button
            type="submit"
            size="sm"
            disabled={invalid || resting}
            className="shrink-0 gap-1.5"
          >
            <Send aria-hidden="true" className="size-4" />
            Send
          </Button>
        </div>
        <div className="flex items-center justify-between gap-2 px-1 text-xs">
          {error ? (
            <span role="status" className="font-medium text-destructive">
              {error}
            </span>
          ) : (
            <span />
          )}
          <span
            id={counterId}
            className={length > IMPOSTOR_CHAT_MAX ? 'text-destructive' : 'text-muted-foreground'}
          >
            {length}/{IMPOSTOR_CHAT_MAX}
          </span>
        </div>
      </form>
    </section>
  )
}
