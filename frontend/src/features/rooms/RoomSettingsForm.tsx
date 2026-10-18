import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as React from 'react'
import { FolderHeart } from 'lucide-react'

import { describeError, fieldError, otherFieldErrors } from '@/api/errors'
import { roomQueryKey, updateRoomSettings } from '@/api/rooms'
import type { DifficultySetting, RoomDto, SettingsMap, WordSourceSetting } from '@/api/types'
import { listWordPacks, wordPackQueryKeys } from '@/api/wordPacks'
import { Button } from '@/components/ui/button'
import { CategorySelector } from '@/features/drawguess/words/CategorySelector'
import { CustomPackManagerModal } from '@/features/drawguess/words/CustomPackManagerModal'
import { DifficultySelector } from '@/features/drawguess/words/DifficultySelector'
import { EligibleWordCountBadge } from '@/features/drawguess/words/EligibleWordCountBadge'
import { WordSourceSelector } from '@/features/drawguess/words/WordSourceSelector'

import { gameModule } from '@/features/games/gameModules'
import { LoadError } from '@/features/games/LoadError'
import { editableFields, playerCountChoices } from '@/features/games/settings/schemaChoices'
import { SettingsFields } from '@/features/games/settings/SettingsFields'
import { useGameDetail } from '@/features/games/useGames'

import { SelectField } from './SelectField'

/** Host-only editor for max players and game settings (PATCH /rooms/{code}/settings). */
export function RoomSettingsForm({ room }: { room: RoomDto }) {
  const queryClient = useQueryClient()
  const detailQuery = useGameDetail(room.gameKey)
  const detail = detailQuery.data
  const claimedKeys = gameModule(room.gameKey)?.lobbyPanelFields ?? []
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

  const shownFields = new Set([
    'maxPlayers',
    ...(detail ? editableFields(detail.settingsSchema, claimedKeys) : []).map(
      (f) => `settings.${f.key}`,
    ),
  ])
  const unshownErrors = otherFieldErrors(mutation.error, shownFields)

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
        {detail ? (
          <div className="grid gap-3 sm:grid-cols-3 sm:gap-4">
            <SelectField
              label="Max players"
              value={maxPlayers}
              onChange={(v) => {
                setMaxPlayers(v)
                touch()
              }}
              options={playerCountChoices(detail).map((n) => ({
                value: n,
                label: `${n} players`,
              }))}
              error={fieldError(mutation.error, 'maxPlayers')}
            />
            <SettingsFields
              schema={detail.settingsSchema}
              claimedKeys={claimedKeys}
              values={settings}
              onChange={(key, value) => {
                setSettings((s) => ({ ...s, [key]: value }))
                touch()
              }}
              errorFor={(key) => fieldError(mutation.error, `settings.${key}`)}
              disabled={mutation.isPending}
            />
          </div>
        ) : detailQuery.isError ? (
          <LoadError error={detailQuery.error} onRetry={() => void detailQuery.refetch()} />
        ) : (
          <div
            className="grid gap-3 sm:grid-cols-3 sm:gap-4"
            aria-busy="true"
            aria-label="Loading settings"
          >
            {[0, 1, 2].map((i) => (
              <div
                key={i}
                className="h-10 animate-pulse rounded-xl bg-secondary motion-reduce:animate-none"
              />
            ))}
          </div>
        )}

        {/* Draw & Guess Word Settings */}
        {isDrawGuess && (
          <div className="grid gap-4 border-t-2 border-dashed border-border pt-4 lg:grid-cols-2 lg:gap-x-8">
            <div className="flex flex-col gap-4">
              <WordSourceSelector
                value={wordSource}
                onChange={handleWordSourceChange}
                disabled={mutation.isPending}
              />

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
            {(wordSource === 'built-in' || wordSource === 'combined') && (
              <CategorySelector
                value={wordCategories}
                onChange={handleCategoriesChange}
                disabled={mutation.isPending}
              />
            )}
          </div>
        )}

        {mutation.isError && (
          <p role="alert" className="text-sm font-medium text-destructive">
            {unshownErrors.length > 0 ? unshownErrors.join(' ') : describeError(mutation.error)}
          </p>
        )}
        <p aria-live="polite" className="min-h-4 text-sm font-medium text-emerald-600">
          {saved ? 'Settings saved.' : ''}
        </p>
        <Button type="submit" variant="secondary" disabled={!detail || mutation.isPending}>
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
