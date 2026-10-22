import * as React from 'react'
import { CheckCircle2, HelpCircle } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ServerCountdown } from '@/features/session/ServerCountdown'

import { codePointLength, sanitizeGuessText, validateGuessText } from './textRules'
import { IMPOSTOR_GUESS_MAX, type ImpostorCategory } from './types'

export interface ImpostorGuessInputProps {
  /** The viewer is the caught impostor and may guess ([DI-11]). */
  isGuesser: boolean
  impostorName: string
  category: ImpostorCategory | null
  deadlineAt: string | null
  submitted: boolean
  error?: string | null
  /** Returns `false` when the guess could not be sent. */
  onGuess: (text: string) => boolean
}

/** One guess at the word for the caught impostor; everyone else watches the clock. */
export function ImpostorGuessInput({
  isGuesser,
  impostorName,
  category,
  deadlineAt,
  submitted,
  error,
  onGuess,
}: ImpostorGuessInputProps) {
  const inputId = React.useId()
  const [value, setValue] = React.useState('')
  const [sending, setSending] = React.useState(false)
  const invalid = validateGuessText(value) !== null
  const length = codePointLength(sanitizeGuessText(value))

  if (!isGuesser) {
    return (
      <section
        aria-label="Final guess"
        className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card p-5 text-center shadow-xs"
      >
        <HelpCircle aria-hidden="true" className="size-8 text-primary" />
        <p className="font-fredoka text-lg font-semibold" aria-live="polite">
          The impostor is guessing the word…
        </p>
        <p className="text-sm text-muted-foreground">
          {impostorName} was caught. One right guess still wins them the round.
        </p>
        <ServerCountdown deadlineAt={deadlineAt} size="sm" />
      </section>
    )
  }

  // An error frees the input again for another try (the server allows one accepted guess).
  const locked = submitted || (sending && !error)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (invalid || locked) return
    if (onGuess(value)) setSending(true)
  }

  return (
    <section
      aria-label="Final guess"
      className="flex flex-col gap-3 rounded-2xl border border-primary/40 bg-card p-5 shadow-xs"
    >
      <h2 className="font-fredoka text-lg font-semibold">You were caught — name the word!</h2>
      <p className="text-sm text-muted-foreground">
        One guess only.{category ? ` The category is ${category.label}.` : ''}
      </p>
      {submitted ? (
        <p role="status" className="inline-flex items-center gap-2 font-semibold">
          <CheckCircle2 aria-hidden="true" className="size-5 text-primary" />
          Guess locked in. Fingers crossed!
        </p>
      ) : (
        <form onSubmit={handleSubmit} className="flex flex-col gap-1">
          <label htmlFor={inputId} className="text-sm font-semibold">
            Your guess
          </label>
          <div className="flex items-center gap-2">
            <Input
              id={inputId}
              value={value}
              onChange={(e) => setValue(e.target.value)}
              disabled={locked}
              autoComplete="off"
              aria-invalid={length > IMPOSTOR_GUESS_MAX}
            />
            <Button type="submit" disabled={invalid || locked} className="shrink-0">
              Lock it in
            </Button>
          </div>
          <span className="px-1 text-right text-xs text-muted-foreground">
            {length}/{IMPOSTOR_GUESS_MAX}
          </span>
        </form>
      )}
      {error && (
        <p role="alert" className="text-sm font-medium text-destructive">
          {error}
        </p>
      )}
      <ServerCountdown deadlineAt={deadlineAt} size="sm" />
    </section>
  )
}
