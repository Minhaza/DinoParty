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
    description: 'Curated game dictionary',
    icon: BookOpen,
  },
  {
    key: 'custom',
    label: 'Custom packs',
    description: 'Your room custom packs',
    icon: FolderHeart,
  },
  {
    key: 'combined',
    label: 'Built-in + Custom',
    description: 'Blend built-in & custom packs',
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
              className={cn(
                'relative flex cursor-pointer items-center sm:items-start gap-3 rounded-2xl border-2 p-3 text-left transition-all outline-none',
                selected
                  ? 'border-primary bg-primary/10 shadow-tactile ring-2 ring-primary/40'
                  : 'border-border bg-card hover:bg-secondary/70',
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
              <div
                className={cn(
                  'flex size-8 shrink-0 items-center justify-center rounded-xl',
                  selected
                    ? 'bg-primary text-primary-foreground'
                    : 'bg-muted text-muted-foreground',
                )}
              >
                <Icon className="size-4" />
              </div>
              <div className="flex flex-col min-w-0">
                <span className="text-sm font-bold text-foreground leading-snug">
                  {source.label}
                </span>
                <span className="text-xs font-semibold text-muted-foreground leading-tight">
                  {source.description}
                </span>
              </div>
            </label>
          )
        })}
      </div>
    </fieldset>
  )
}
