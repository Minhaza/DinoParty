import * as React from 'react'
import { Send } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

export interface GuessInputProps {
  isDrawer: boolean
  isSolved: boolean
  isDrawingPhase: boolean
  onSubmitGuess: (text: string) => void
}

export function GuessInput({
  isDrawer,
  isSolved,
  isDrawingPhase,
  onSubmitGuess,
}: GuessInputProps) {
  const [value, setValue] = React.useState('')
  const [error, setError] = React.useState<string | null>(null)

  const handleSubmit = (e?: React.FormEvent) => {
    e?.preventDefault()
    const trimmed = value.trim()

    if (!trimmed) {
      setError('Please enter a guess')
      return
    }

    if (trimmed.length > 80) {
      setError('Guess must be 80 characters or fewer')
      return
    }

    setError(null)
    onSubmitGuess(trimmed)
    setValue('')
  }

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setValue(e.target.value)
    if (error) setError(null)
  }

  const isDisabled = isDrawer || !isDrawingPhase

  const placeholder = isDrawer
    ? 'You are drawing this round'
    : !isDrawingPhase
      ? 'Waiting for next round…'
      : isSolved
        ? 'Chat with other solvers…'
        : 'Type your guess here…'

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-1 w-full">
      <div className="flex items-center gap-2">
        <Input
          type="text"
          value={value}
          onChange={handleChange}
          disabled={isDisabled}
          maxLength={80}
          placeholder={placeholder}
          aria-label={placeholder}
          className="h-10 text-sm bg-card border-border/80 focus-visible:ring-primary shadow-xs"
        />
        <Button
          type="submit"
          size="sm"
          disabled={isDisabled || !value.trim()}
          className="h-10 px-3.5 gap-1.5 shrink-0 font-bold"
          aria-label="Send guess"
        >
          <Send className="size-4" />
          <span className="hidden sm:inline">Send</span>
        </Button>
      </div>
      {error && <span className="text-xs text-destructive font-medium px-1">{error}</span>}
    </form>
  )
}
