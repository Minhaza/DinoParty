import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as React from 'react'
import { FolderHeart } from 'lucide-react'

import { describeError, fieldError } from '@/api/errors'
import { roomQueryKey, updateRoomSettings } from '@/api/rooms'
import type { DifficultySetting, RoomDto, SettingsMap, WordSourceSetting } from '@/api/types'
import { listWordPacks, wordPackQueryKeys } from '@/api/wordPacks'
import { Button } from '@/components/ui/button'
import { CategorySelector } from '@/features/drawguess/words/CategorySelector'
import { CustomPackManagerModal } from '@/features/drawguess/words/CustomPackManagerModal'
import { DifficultySelector } from '@/features/drawguess/words/DifficultySelector'
import { EligibleWordCountBadge } from '@/features/drawguess/words/EligibleWordCountBadge'
import { WordSourceSelector } from '@/features/drawguess/words/WordSourceSelector'

import { GAME_CONFIGS, maxPlayerChoices } from './gameConfig'
import { SelectField } from './SelectField'

/** Host-only editor for max players and game settings (PATCH /rooms/{code}/settings). */
export function RoomSettingsForm({ room }: { room: RoomDto }) {
  const queryClient = useQueryClient()
  const config = GAME_CONFIGS[room.gameKey]
  const [maxPlayers, setMaxPlayers] = React.useState(room.maxPlayers)
  const [settings, setSettings] = React.useState<SettingsMap>(room.settings)
  const [saved, setSaved] = React.useState(false)

  // Draw & Guess word state
  const isDrawGuess = room.gameKey === 'draw-guess'
  const wordSource = (settings['wordSource'] as WordSourceSetting) ?? 'built-in'
  const wordCategories = Array.isArray(settings['wordCategories'])
    ? (settings['wordCategories'] as string[])
    : ['random-mix']
  const difficulty = (settings['difficulty'] as DifficultySetting) ?? 'mixed'
  const wordPackIds = Array.isArray(settings['wordPackIds'])
    ? (settings['wordPackIds'] as string[])
    : []

  const [isPackManagerOpen, setIsPackManagerOpen] = React.useState(false)

  // Custom packs query for Draw & Guess
  const { data: packs = [] } = useQuery({
    queryKey: wordPackQueryKeys.all(room.code),
    queryFn: ({ signal }) => listWordPacks(room.code, signal),
    enabled: isDrawGuess && (wordSource === 'custom' || wordSource === 'combined'),
  })

  const mutation = useMutation({
    mutationFn: () => updateRoomSettings(room.code, { maxPlayers, settings }),
    onSuccess: (updated) => {
      queryClient.setQueryData(roomQueryKey(room.code), updated)
      setSaved(true)
    },
  })

  const serverField =
    fieldError(mutation.error, 'maxPlayers') ??
    config.fields.map((f) => fieldError(mutation.error, `settings.${f.key}`)).find(Boolean) ??
    null

  const touch = () => {
    setSaved(false)
    mutation.reset()
  }

  const handleWordSourceChange = (src: WordSourceSetting) => {
    setSettings((s) => ({ ...s, wordSource: src }))
    touch()
  }

  const handleCategoriesChange = (cats: string[]) => {
    setSettings((s) => ({ ...s, wordCategories: cats }))
    touch()
  }

  const handleDifficultyChange = (diff: DifficultySetting) => {
    setSettings((s) => ({ ...s, difficulty: diff }))
    touch()
  }

  const handlePackIdsChange = (ids: string[]) => {
    setSettings((s) => ({ ...s, wordPackIds: ids }))
    touch()
  }

  return (
    <>
      <form
        aria-label="Room settings"
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault()
          mutation.mutate()
        }}
      >
        <SelectField
          label="Max players"
          value={maxPlayers}
          onChange={(v) => {
            setMaxPlayers(v)
            touch()
          }}
          options={maxPlayerChoices(room.gameKey).map((n) => ({
            value: n,
            label: `${n} players`,
          }))}
        />
        {config.fields.map((field) => (
          <SelectField
            key={field.key}
            label={field.label}
            value={Number(settings[field.key] ?? field.defaultValue)}
            options={field.choices}
            onChange={(v) => {
              setSettings((s) => ({ ...s, [field.key]: v }))
              touch()
            }}
          />
        ))}

        {/* Draw & Guess Word Settings */}
        {isDrawGuess && (
          <div className="flex flex-col gap-4 border-t-2 border-dashed border-border pt-4">
            <WordSourceSelector
              value={wordSource}
              onChange={handleWordSourceChange}
              disabled={mutation.isPending}
            />

            {(wordSource === 'built-in' || wordSource === 'combined') && (
              <CategorySelector
                value={wordCategories}
                onChange={handleCategoriesChange}
                disabled={mutation.isPending}
              />
            )}

            <DifficultySelector
              value={difficulty}
              onChange={handleDifficultyChange}
              disabled={mutation.isPending}
            />

            {(wordSource === 'custom' || wordSource === 'combined') && (
              <div className="flex flex-col gap-2 rounded-2xl border-2 border-border/80 bg-secondary/30 p-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5 text-xs font-bold text-foreground">
                    <FolderHeart className="size-4 text-primary" />
                    <span>Custom Packs ({wordPackIds.length} active)</span>
                  </div>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => setIsPackManagerOpen(true)}
                    className="h-7 text-xs font-bold rounded-lg"
                  >
                    Manage Packs
                  </Button>
                </div>
                {wordPackIds.length === 0 ? (
                  <p className="text-xs text-muted-foreground italic">
                    No custom packs selected. Click "Manage Packs" to choose or create packs.
                  </p>
                ) : (
                  <div className="flex flex-wrap gap-1">
                    {packs
                      .filter((p) => wordPackIds.includes(p.id))
                      .map((p) => (
                        <span
                          key={p.id}
                          className="rounded-md border border-border bg-card px-2 py-0.5 text-[11px] font-bold text-foreground"
                        >
                          {p.name} ({p.wordCount})
                        </span>
                      ))}
                  </div>
                )}
              </div>
            )}

            <EligibleWordCountBadge
              roomCode={room.code}
              wordSource={wordSource}
              wordCategories={wordCategories}
              difficulty={difficulty}
              wordPackIds={wordPackIds}
            />
          </div>
        )}

        {mutation.isError && (
          <p role="alert" className="text-sm font-medium text-destructive">
            {serverField ?? describeError(mutation.error)}
          </p>
        )}
        <p aria-live="polite" className="min-h-4 text-sm font-medium text-emerald-600">
          {saved ? 'Settings saved.' : ''}
        </p>
        <Button type="submit" variant="secondary" disabled={mutation.isPending}>
          {mutation.isPending ? 'Saving…' : 'Save settings'}
        </Button>
      </form>

      {isDrawGuess && (
        <CustomPackManagerModal
          open={isPackManagerOpen}
          onOpenChange={setIsPackManagerOpen}
          roomCode={room.code}
          selectedPackIds={wordPackIds}
          onSelectPacks={handlePackIdsChange}
        />
      )}
    </>
  )
}
