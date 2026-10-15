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
  badgeClass: string
}[] = [
  {
    key: 'easy',
    label: 'Easy',
    description: 'Everyday words that are simple and fast to draw.',
    badgeClass: 'text-success border-success/40 bg-success/15',
  },
  {
    key: 'medium',
    label: 'Medium',
    description: 'Balanced mix of common items, creatures, and actions.',
    badgeClass: 'text-primary border-primary/40 bg-primary/15',
  },
  {
    key: 'hard',
    label: 'Hard',
    description: 'Tricky words, abstract concepts, and complex objects.',
    badgeClass: 'text-accent border-accent/40 bg-accent/15',
  },
  {
    key: 'mixed',
    label: 'Mixed',
    description: 'A dynamic blend of all levels across rounds.',
    badgeClass: 'text-purple border-purple/40 bg-purple/15',
  },
]

export function DifficultySelector({ value, onChange, disabled }: DifficultySelectorProps) {
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="text-xs font-extrabold uppercase tracking-wider text-muted-foreground">
        Difficulty
      </legend>
      <div
        className="grid grid-cols-1 sm:grid-cols-2 gap-2"
        role="radiogroup"
        aria-label="Difficulty"
      >
        {DIFFICULTIES.map((diff) => {
          const selected = value === diff.key
          return (
            <label
              key={diff.key}
              className={cn(
                'relative flex cursor-pointer flex-col gap-1 rounded-2xl border-2 p-3 text-left transition-all outline-none',
                selected
                  ? 'border-primary bg-primary/10 shadow-tactile ring-2 ring-primary/40'
                  : 'border-border bg-card hover:bg-secondary/70',
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
              <div className="flex items-center justify-between">
                <span className="text-sm font-bold text-foreground">{diff.label}</span>
                <span
                  className={cn(
                    'rounded-full border px-2 py-0.5 text-[10px] font-black uppercase tracking-wider',
                    diff.badgeClass,
                  )}
                >
                  {diff.key}
                </span>
              </div>
              <p className="text-xs font-semibold text-muted-foreground leading-tight">
                {diff.description}
              </p>
            </label>
          )
        })}
      </div>
    </fieldset>
  )
}
