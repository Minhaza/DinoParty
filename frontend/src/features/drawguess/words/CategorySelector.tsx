import { useQuery } from '@tanstack/react-query'
import { AlertCircle, Check, Shuffle } from 'lucide-react'
import { getWordCategories, wordCatalogQueryKeys } from '@/api/wordCatalog'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export interface CategorySelectorProps {
  value: string[]
  onChange: (categories: string[]) => void
  categoryCounts?: Record<string, number>
  disabled?: boolean
}

export function CategorySelector({
  value,
  onChange,
  categoryCounts = {},
  disabled,
}: CategorySelectorProps) {
  const { data, isLoading } = useQuery({
    queryKey: wordCatalogQueryKeys.categories(),
    queryFn: ({ signal }) => getWordCategories(undefined, signal),
    staleTime: Infinity,
  })

  const categories = data?.categories ?? []
  const allCategoryKeys = categories.map((c) => c.key)
  const isRandomMix = value.length === 1 && value[0] === 'random-mix'
  const isNoneSelected = value.length === 0

  const toggleCategory = (key: string) => {
    if (disabled) return
    // If currently Random Mix, start new selection from just this key
    if (isRandomMix) {
      onChange([key])
      return
    }
    if (value.includes(key)) {
      onChange(value.filter((k) => k !== key))
    } else {
      onChange([...value, key])
    }
  }

  const handleSelectAll = () => {
    if (disabled) return
    onChange(allCategoryKeys)
  }

  const handleClearAll = () => {
    if (disabled) return
    onChange([])
  }

  const handleRandomMix = () => {
    if (disabled) return
    onChange(['random-mix'])
  }

  return (
    <fieldset className="flex flex-col gap-2.5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <legend className="text-xs font-extrabold uppercase tracking-wider text-muted-foreground">
          Categories
        </legend>
        <div className="flex items-center gap-1.5 text-xs">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={handleRandomMix}
            disabled={disabled}
            className={cn(
              'h-7 px-2 text-xs font-bold gap-1 rounded-lg',
              isRandomMix && 'bg-primary/15 text-primary',
            )}
          >
            <Shuffle className="size-3" />
            Random Mix
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={handleSelectAll}
            disabled={disabled}
            className="h-7 px-2 text-xs font-bold rounded-lg"
          >
            Select All
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={handleClearAll}
            disabled={disabled}
            className="h-7 px-2 text-xs font-bold rounded-lg text-muted-foreground hover:text-destructive"
          >
            Clear All
          </Button>
        </div>
      </div>

      {isLoading ? (
        <div className="flex items-center justify-center p-6 text-sm text-muted-foreground">
          Loading categories…
        </div>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 xl:grid-cols-5 gap-1.5">
          {categories.map((cat) => {
            const isChecked = isRandomMix || value.includes(cat.key)
            const count = categoryCounts[cat.key]
            return (
              <label
                key={cat.key}
                className={cn(
                  'relative flex cursor-pointer items-center justify-between gap-2 rounded-lg border-2 px-2.5 py-1.5 text-left transition-all outline-none',
                  isChecked
                    ? 'border-primary/80 bg-primary/10 text-primary font-bold shadow-xs'
                    : 'border-border bg-card text-foreground font-semibold hover:bg-secondary/70',
                  disabled && 'cursor-not-allowed opacity-60',
                )}
              >
                <input
                  type="checkbox"
                  checked={isChecked}
                  disabled={disabled}
                  onChange={() => toggleCategory(cat.key)}
                  className="sr-only"
                  aria-label={cat.label}
                />
                <span className="text-xs truncate">{cat.label}</span>
                <div className="flex items-center gap-1 shrink-0">
                  {typeof count === 'number' && (
                    <span className="text-[10px] font-bold text-muted-foreground/80">{count}</span>
                  )}
                  {isChecked && <Check className="size-3.5 text-primary" />}
                </div>
              </label>
            )
          })}
        </div>
      )}

      {isNoneSelected && (
        <div
          role="alert"
          className="flex items-center gap-2 rounded-xl border border-warning/40 bg-warning/15 px-3 py-2 text-xs font-bold text-warning-foreground dark:text-warning"
        >
          <AlertCircle className="size-4 shrink-0" />
          <span>No categories selected. Game will use Random Mix by default.</span>
        </div>
      )}
    </fieldset>
  )
}
