import type { DifficultySetting } from '@/api/types'
import { cn } from '@/lib/utils'

export interface DifficultySelectorProps {
  value: DifficultySetting
  onChange: (value: DifficultySetting) => void
  disabled?: boolean
}

const DIFFICULTIES: {
  key: DifficultySetting
  label: string
  description: string
}[] = [
  {
    key: 'easy',
    label: 'Easy',
    description: 'Simple, fast-to-draw words.',
  },
  {
    key: 'medium',
    label: 'Medium',
    description: 'Common items, creatures, actions.',
  },
  {
    key: 'hard',
    label: 'Hard',
    description: 'Abstract and complex words.',
  },
  {
    key: 'mixed',
    label: 'Mixed',
    description: 'A blend of all levels.',
  },
]

export function DifficultySelector({ value, onChange, disabled }: DifficultySelectorProps) {
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="text-xs font-extrabold uppercase tracking-wider text-muted-foreground">
        Difficulty
      </legend>
      <div
        className="grid grid-cols-2 sm:grid-cols-4 gap-2"
        role="radiogroup"
        aria-label="Difficulty"
      >
        {DIFFICULTIES.map((diff) => {
          const selected = value === diff.key
          return (
            <label
              key={diff.key}
              title={diff.description}
              className={cn(
                'relative flex cursor-pointer items-center justify-center rounded-xl border-2 px-3 py-2 text-center transition-all outline-none',
                selected
                  ? 'border-primary bg-primary/10 text-primary shadow-tactile'
                  : 'border-border bg-card text-foreground hover:bg-secondary/70',
                disabled && 'cursor-not-allowed opacity-60',
              )}
            >
              <input
                type="radio"
                name="difficulty"
                value={diff.key}
                checked={selected}
                disabled={disabled}
                onChange={() => onChange(diff.key)}
                className="sr-only"
                aria-label={diff.label}
              />
              <span className="text-sm font-bold">{diff.label}</span>
            </label>
          )
        })}
      </div>
    </fieldset>
  )
}
