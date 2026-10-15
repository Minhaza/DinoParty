import type { DifficultySetting, WordPackDto, WordSourceSetting } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { BookOpen, FolderHeart, Sparkles } from 'lucide-react'

export interface WordSettingsSummaryProps {
  wordSource?: WordSourceSetting
  wordCategories?: string[]
  difficulty?: DifficultySetting
  wordPacks?: WordPackDto[]
  selectedPackIds?: string[]
}

const SOURCE_LABELS: Record<WordSourceSetting, { label: string; icon: typeof BookOpen }> = {
  'built-in': { label: 'Built-in words', icon: BookOpen },
  custom: { label: 'Custom packs', icon: FolderHeart },
  combined: { label: 'Built-in + Custom', icon: Sparkles },
}

const DIFFICULTY_LABELS: Record<DifficultySetting, string> = {
  easy: 'Easy',
  medium: 'Medium',
  hard: 'Hard',
  mixed: 'Mixed',
}

export function WordSettingsSummary({
  wordSource = 'built-in',
  wordCategories = ['random-mix'],
  difficulty = 'mixed',
  wordPacks = [],
  selectedPackIds = [],
}: WordSettingsSummaryProps) {
  const sourceInfo = SOURCE_LABELS[wordSource] ?? SOURCE_LABELS['built-in']
  const Icon = sourceInfo.icon
  const isRandomMix =
    wordCategories.length === 0 ||
    (wordCategories.length === 1 && wordCategories[0] === 'random-mix')

  const selectedPacks = wordPacks.filter((p) => selectedPackIds.includes(p.id))

  return (
    <div className="flex flex-col gap-3 rounded-2xl border-2 border-border/80 bg-secondary/30 p-3.5 text-xs">
      <div className="flex items-center justify-between">
        <span className="font-extrabold uppercase tracking-wider text-muted-foreground">
          Word Pool Settings
        </span>
        <Badge variant="outline" className="gap-1 font-bold">
          <Icon className="size-3 text-primary" />
          {sourceInfo.label}
        </Badge>
      </div>

      <div className="grid grid-cols-2 gap-2 text-foreground">
        <div>
          <span className="font-bold text-muted-foreground block mb-0.5">Difficulty</span>
          <span className="font-extrabold capitalize">{DIFFICULTY_LABELS[difficulty]}</span>
        </div>

        {(wordSource === 'built-in' || wordSource === 'combined') && (
          <div>
            <span className="font-bold text-muted-foreground block mb-0.5">Categories</span>
            <span className="font-extrabold">
              {isRandomMix ? 'Random Mix (all)' : `${wordCategories.length} selected`}
            </span>
          </div>
        )}
      </div>

      {(wordSource === 'custom' || wordSource === 'combined') && (
        <div className="border-t border-border/60 pt-2 flex flex-col gap-1.5">
          <span className="font-bold text-muted-foreground">
            Custom Packs ({selectedPacks.length})
          </span>
          {selectedPacks.length === 0 ? (
            <span className="italic text-muted-foreground">No packs selected</span>
          ) : (
            <div className="flex flex-wrap gap-1.5">
              {selectedPacks.map((pack) => (
                <span
                  key={pack.id}
                  className="inline-flex items-center gap-1 rounded-lg border border-border bg-card px-2 py-1 font-bold text-foreground"
                >
                  <span>{pack.name}</span>
                  <span className="text-[10px] text-muted-foreground">({pack.wordCount})</span>
                </span>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
