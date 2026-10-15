import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, Sparkles } from 'lucide-react'
import type { DifficultySetting, WordSourceSetting } from '@/api/types'
import { getWordCounts, wordCatalogQueryKeys } from '@/api/wordCatalog'
import { listWordPacks, wordPackQueryKeys } from '@/api/wordPacks'
import { cn } from '@/lib/utils'

export interface EligibleWordCountBadgeProps {
  roomCode: string
  wordSource: WordSourceSetting
  wordCategories: string[]
  difficulty: DifficultySetting
  wordPackIds: string[]
  className?: string
}

export function EligibleWordCountBadge({
  roomCode,
  wordSource,
  wordCategories,
  difficulty,
  wordPackIds,
  className,
}: EligibleWordCountBadgeProps) {
  // Map difficulty setting to catalog difficulty enum
  const diffParam = difficulty.toUpperCase() as 'EASY' | 'MEDIUM' | 'HARD' | 'MIXED'
  const isRandomMix =
    wordCategories.length === 0 ||
    (wordCategories.length === 1 && wordCategories[0] === 'random-mix')
  const catParam = isRandomMix ? 'random-mix' : wordCategories[0]

  // Query catalog counts when built-in words are active
  const { data: catalogCounts, isLoading: isLoadingCatalog } = useQuery({
    queryKey: wordCatalogQueryKeys.counts({ category: catParam, difficulty: diffParam }),
    queryFn: ({ signal }) => getWordCounts({ category: catParam, difficulty: diffParam }, signal),
    enabled: wordSource === 'built-in' || wordSource === 'combined',
  })

  // Query custom packs when custom packs are active
  const { data: packs, isLoading: isLoadingPacks } = useQuery({
    queryKey: wordPackQueryKeys.all(roomCode),
    queryFn: ({ signal }) => listWordPacks(roomCode, signal),
    enabled: Boolean(roomCode && (wordSource === 'custom' || wordSource === 'combined')),
  })

  let builtInTotal = 0
  if (wordSource === 'built-in' || wordSource === 'combined') {
    if (isRandomMix || wordCategories.length === 1) {
      builtInTotal = catalogCounts?.total ?? 0
    } else {
      const byCat = catalogCounts?.byCategory ?? []
      if (byCat.length > 0) {
        builtInTotal = byCat
          .filter((c) => wordCategories.includes(c.key))
          .reduce((sum, c) => sum + c.total, 0)
      } else {
        builtInTotal = catalogCounts?.total ?? 0
      }
    }
  }

  let customTotal = 0
  if (wordSource === 'custom' || wordSource === 'combined') {
    const selectedPacks = (packs ?? []).filter((p) => wordPackIds.includes(p.id))
    customTotal = selectedPacks.reduce((sum, p) => sum + p.wordCount, 0)
  }

  const totalEligible =
    wordSource === 'built-in'
      ? builtInTotal
      : wordSource === 'custom'
        ? customTotal
        : builtInTotal + customTotal

  const isLoading =
    (wordSource === 'built-in' && isLoadingCatalog) ||
    (wordSource === 'custom' && isLoadingPacks) ||
    (wordSource === 'combined' && (isLoadingCatalog || isLoadingPacks))

  const isBelowMinimum = !isLoading && totalEligible < 3

  return (
    <div className={cn('flex flex-col gap-2', className)}>
      <div className="flex items-center justify-between rounded-xl border border-border/80 bg-secondary/40 px-3 py-2 text-xs">
        <span className="flex items-center gap-1.5 font-bold text-muted-foreground">
          <Sparkles className="size-3.5 text-primary" />
          Eligible Pool
        </span>
        <span className="font-extrabold text-foreground">
          {isLoading ? 'Counting…' : `${totalEligible.toLocaleString()} eligible words`}
        </span>
      </div>

      {isBelowMinimum && (
        <div
          role="alert"
          className="flex items-start gap-2 rounded-xl border-2 border-destructive/50 bg-destructive/10 p-3 text-xs font-bold text-destructive"
        >
          <AlertTriangle className="size-4 shrink-0 mt-0.5" />
          <div>
            <p>Minimum 3 words required to start Draw & Guess.</p>
            <p className="font-normal text-destructive/90">
              Current selection only has {totalEligible} words. Please select more categories or add
              words to your custom packs.
            </p>
          </div>
        </div>
      )}
    </div>
  )
}
