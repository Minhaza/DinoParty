import * as React from 'react'
import type { WordSourceSetting } from '@/api/types'
import { cn } from '@/lib/utils'
import { BookOpen, FolderHeart, Sparkles } from 'lucide-react'

export interface WordSourceSelectorProps {
  value: WordSourceSetting
  onChange: (value: WordSourceSetting) => void
  disabled?: boolean
}

const SOURCES: {
  key: WordSourceSetting
  label: string
  description: string
  icon: React.ComponentType<{ className?: string }>
}[] = [
  {
    key: 'built-in',
    label: 'Built-in words',
    description: 'Curated dictionary',
    icon: BookOpen,
  },
  {
    key: 'custom',
    label: 'Custom packs',
    description: 'Your room packs',
    icon: FolderHeart,
  },
  {
    key: 'combined',
    label: 'Built-in + Custom',
    description: 'Blend of both',
    icon: Sparkles,
  },
]

export function WordSourceSelector({ value, onChange, disabled }: WordSourceSelectorProps) {
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="text-xs font-extrabold uppercase tracking-wider text-muted-foreground">
        Word Source
      </legend>
      <div
        className="grid grid-cols-1 sm:grid-cols-3 gap-2"
        role="radiogroup"
        aria-label="Word source"
      >
        {SOURCES.map((source) => {
          const selected = value === source.key
          const Icon = source.icon
          return (
            <label
              key={source.key}
              title={source.description}
              className={cn(
                'relative flex cursor-pointer items-center justify-center gap-2 rounded-xl border-2 px-3 py-2 text-center transition-all outline-none',
                selected
                  ? 'border-primary bg-primary/10 text-primary shadow-tactile'
                  : 'border-border bg-card text-foreground hover:bg-secondary/70',
                disabled && 'cursor-not-allowed opacity-60',
              )}
            >
              <input
                type="radio"
                name="wordSource"
                value={source.key}
                checked={selected}
                disabled={disabled}
                onChange={() => onChange(source.key)}
                className="sr-only"
                aria-label={source.label}
              />
              <Icon className="size-4 shrink-0" />
              <span className="text-sm font-bold leading-snug">{source.label}</span>
            </label>
          )
        })}
      </div>
    </fieldset>
  )
}
