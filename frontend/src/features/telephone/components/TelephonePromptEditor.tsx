import * as React from 'react'
import { Send } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import type { SubmissionStatus } from '../telephoneStore'
import {
  codePointLength,
  sanitizeTelephoneText,
  TELEPHONE_TEXT_MAX,
  validateTelephoneText,
} from '../textRules'
import { TelephoneSubmissionStatus } from './TelephoneSubmissionStatus'

export interface TelephonePromptEditorProps {
  mode: 'prompt' | 'describe'
  value: string
  submission: SubmissionStatus
  submitError: string | null
  offline: boolean
  syncing: boolean
  onChange: (text: string) => void
  /** Receives the sanitized text; only called when it passes the [TP-14] rules. */
  onSubmit: (text: string) => void
}

const LABELS = {
  prompt: {
    label: 'Your prompt',
    placeholder: 'A dinosaur baking a birthday cake on the moon…',
    button: 'Send prompt',
  },
  describe: {
    label: 'Your description',
    placeholder: 'I think this is…',
    button: 'Send description',
  },
} as const

/** Text card for PROMPT and DESCRIBE: code-point counter, validation and one-shot submit. */
export function TelephonePromptEditor({
  mode,
  value,
  submission,
  submitError,
  offline,
  syncing,
  onChange,
  onSubmit,
}: TelephonePromptEditorProps) {
  const id = React.useId()
  const counterId = `${id}-counter`
  const errorId = `${id}-error`
  const [touched, setTouched] = React.useState(false)

  const length = codePointLength(sanitizeTelephoneText(value))
  const validation = validateTelephoneText(value)
  const busy = submission === 'submitting' || submission === 'submitted'
  const locked = busy || offline || syncing
  const canSubmit = !locked && validation === null

  const message =
    validation === 'TOO_LONG'
      ? `Keep it to ${TELEPHONE_TEXT_MAX} characters or fewer.`
      : validation === 'EMPTY' && touched
        ? 'Write something first.'
        : null

  const submit = () => {
    setTouched(true)
    if (!canSubmit) return
    onSubmit(sanitizeTelephoneText(value))
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    // Newlines are stripped by the server, so Enter sends; IME composition keeps its own Enter.
    if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
      e.preventDefault()
      submit()
    }
  }

  return (
    <form
      className="flex flex-col gap-3 rounded-2xl border border-border bg-card p-4 shadow-xs sm:p-5"
      onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}
    >
      <label htmlFor={id} className="font-fredoka text-base font-semibold text-foreground">
        {LABELS[mode].label}
      </label>
      <textarea
        id={id}
        value={value}
        rows={3}
        autoComplete="off"
        spellCheck
        disabled={busy}
        readOnly={offline || syncing}
        placeholder={LABELS[mode].placeholder}
        aria-describedby={message ? `${counterId} ${errorId}` : counterId}
        aria-invalid={validation === 'TOO_LONG' || (touched && validation === 'EMPTY')}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={handleKeyDown}
        // Keep the field in view above the mobile keyboard.
        onFocus={(e) => e.currentTarget.scrollIntoView?.({ block: 'center', behavior: 'smooth' })}
        className="min-h-24 w-full resize-none rounded-xl border-2 border-border bg-background px-3.5 py-3 text-base leading-relaxed break-words text-foreground placeholder:text-muted-foreground/70 focus-visible:border-primary focus-visible:ring-[3px] focus-visible:ring-ring/40 focus-visible:outline-none disabled:opacity-60 aria-invalid:border-destructive"
      />
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span
          id={counterId}
          className={cn(
            'text-xs font-semibold tabular-nums',
            length > TELEPHONE_TEXT_MAX ? 'text-destructive' : 'text-muted-foreground',
          )}
        >
          {length}/{TELEPHONE_TEXT_MAX}
        </span>
        {message && (
          <span id={errorId} className="text-xs font-medium text-destructive">
            {message}
          </span>
        )}
      </div>
      <TelephoneSubmissionStatus
        submission={submission}
        submitError={submitError}
        offline={offline}
        syncing={syncing}
      />
      <Button
        type="submit"
        size="lg"
        className="w-full sm:w-auto sm:self-end"
        disabled={!canSubmit}
      >
        <Send className="size-4" aria-hidden="true" />
        {LABELS[mode].button}
      </Button>
    </form>
  )
}
