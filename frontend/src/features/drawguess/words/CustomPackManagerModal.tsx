import * as React from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, ArrowLeft, Edit2, FileUp, FolderPlus, Plus, Trash2 } from 'lucide-react'
import { describeError } from '@/api/errors'
import type { WordPackDto } from '@/api/types'
import {
  addWordPackWords,
  createWordPack,
  deleteWordPack,
  getWordPackWords,
  listWordPacks,
  removeWordPackWord,
  updateWordPack,
  wordPackQueryKeys,
} from '@/api/wordPacks'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'

import { WordPackImportModal } from './WordPackImportModal'

export interface CustomPackManagerModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  roomCode: string
  selectedPackIds: string[]
  onSelectPacks: (ids: string[]) => void
}

export function CustomPackManagerModal({
  open,
  onOpenChange,
  roomCode,
  selectedPackIds,
  onSelectPacks,
}: CustomPackManagerModalProps) {
  const queryClient = useQueryClient()

  // State
  const [activePackId, setActivePackId] = React.useState<string | null>(null)
  const [isCreating, setIsCreating] = React.useState(false)
  const [newPackName, setNewPackName] = React.useState('')
  const [newPackDesc, setNewPackDesc] = React.useState('')

  const [editingPack, setEditingPack] = React.useState<WordPackDto | null>(null)
  const [editName, setEditName] = React.useState('')
  const [editDesc, setEditDesc] = React.useState('')

  const [deletingPack, setDeletingPack] = React.useState<WordPackDto | null>(null)
  const [isImportOpen, setIsImportOpen] = React.useState(false)

  // Word input state in detail view
  const [newWordText, setNewWordText] = React.useState('')
  const [newWordCategory, setNewWordCategory] = React.useState('')
  const [newWordDifficulty, setNewWordDifficulty] = React.useState<'EASY' | 'MEDIUM' | 'HARD'>(
    'MEDIUM',
  )
  const [wordsPage, setWordsPage] = React.useState(0)

  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      setActivePackId(null)
      setIsCreating(false)
      setEditingPack(null)
      setDeletingPack(null)
      setIsImportOpen(false)
      setNewWordText('')
    }
    onOpenChange(nextOpen)
  }

  // Queries
  const { data: packs = [], isLoading: isLoadingPacks } = useQuery({
    queryKey: wordPackQueryKeys.all(roomCode),
    queryFn: ({ signal }) => listWordPacks(roomCode, signal),
    enabled: open && Boolean(roomCode),
  })

  const activePack = packs.find((p) => p.id === activePackId) ?? null

  const { data: wordsData, isLoading: isLoadingWords } = useQuery({
    queryKey: wordPackQueryKeys.words(roomCode, activePackId ?? '', wordsPage * 50, 50),
    queryFn: ({ signal }) =>
      getWordPackWords(roomCode, activePackId ?? '', wordsPage * 50, 50, signal),
    enabled: open && Boolean(roomCode && activePackId),
  })

  // Mutations
  const createMutation = useMutation({
    mutationFn: () =>
      createWordPack(roomCode, {
        name: newPackName.trim(),
        description: newPackDesc.trim() || undefined,
      }),
    onSuccess: (newPack) => {
      void queryClient.invalidateQueries({ queryKey: wordPackQueryKeys.all(roomCode) })
      setIsCreating(false)
      setNewPackName('')
      setNewPackDesc('')
      // Auto select the newly created pack
      onSelectPacks([...selectedPackIds, newPack.id])
    },
  })

  const editMutation = useMutation({
    mutationFn: () => {
      if (!editingPack) throw new Error('No pack to edit')
      return updateWordPack(roomCode, editingPack.id, {
        name: editName.trim(),
        description: editDesc.trim(),
      })
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: wordPackQueryKeys.all(roomCode) })
      setEditingPack(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: () => {
      if (!deletingPack) throw new Error('No pack to delete')
      return deleteWordPack(roomCode, deletingPack.id)
    },
    onSuccess: () => {
      if (deletingPack) {
        onSelectPacks(selectedPackIds.filter((id) => id !== deletingPack.id))
      }
      void queryClient.invalidateQueries({ queryKey: wordPackQueryKeys.all(roomCode) })
      setDeletingPack(null)
      if (activePackId === deletingPack?.id) {
        setActivePackId(null)
      }
    },
  })

  const addWordMutation = useMutation({
    mutationFn: () => {
      if (!activePackId || !newWordText.trim()) throw new Error('Invalid word input')
      return addWordPackWords(roomCode, activePackId, [
        {
          text: newWordText.trim(),
          category: newWordCategory.trim() || undefined,
          difficulty: newWordDifficulty,
        },
      ])
    },
    onSuccess: () => {
      setNewWordText('')
      setNewWordCategory('')
      void queryClient.invalidateQueries({ queryKey: wordPackQueryKeys.all(roomCode) })
      void queryClient.invalidateQueries({
        queryKey: ['word-packs', roomCode, activePackId, 'words'],
      })
    },
  })

  const removeWordMutation = useMutation({
    mutationFn: (wordId: string) => {
      if (!activePackId) throw new Error('No active pack')
      return removeWordPackWord(roomCode, activePackId, wordId)
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: wordPackQueryKeys.all(roomCode) })
      void queryClient.invalidateQueries({
        queryKey: ['word-packs', roomCode, activePackId, 'words'],
      })
    },
  })

  const toggleSelectPack = (packId: string) => {
    if (selectedPackIds.includes(packId)) {
      onSelectPacks(selectedPackIds.filter((id) => id !== packId))
    } else {
      onSelectPacks([...selectedPackIds, packId])
    }
  }

  return (
    <>
      <Dialog open={open} onOpenChange={handleOpenChange}>
        <DialogContent className="max-w-2xl max-h-[85vh] flex flex-col">
          <DialogHeader>
            <div className="flex items-center gap-2">
              {activePackId && (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => setActivePackId(null)}
                  className="size-8 p-0 rounded-full"
                  aria-label="Back to packs"
                >
                  <ArrowLeft className="size-4" />
                </Button>
              )}
              <DialogTitle>
                {activePack ? `Words in "${activePack.name}"` : 'Manage Custom Word Packs'}
              </DialogTitle>
            </div>
            <DialogDescription>
              {activePack
                ? 'Add, remove, or import words for this pack. Words remain private to the room host.'
                : 'Create and select custom packs for your room. Up to 10 packs can be active at once.'}
            </DialogDescription>
          </DialogHeader>

          {/* MAIN PACK LIST VIEW */}
          {!activePack && (
            <div className="flex flex-col gap-4 overflow-y-auto py-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-extrabold uppercase tracking-wider text-muted-foreground">
                  Room Packs ({packs.length}/20)
                </span>
                {!isCreating && (
                  <Button
                    type="button"
                    size="sm"
                    onClick={() => setIsCreating(true)}
                    className="h-8 gap-1.5 rounded-xl font-bold"
                  >
                    <Plus className="size-3.5" />+ New Pack
                  </Button>
                )}
              </div>

              {/* Create Pack Form */}
              {isCreating && (
                <form
                  onSubmit={(e) => {
                    e.preventDefault()
                    createMutation.mutate()
                  }}
                  className="flex flex-col gap-3 rounded-2xl border-2 border-primary/50 bg-primary/5 p-4"
                >
                  <span className="text-sm font-bold text-foreground">Create New Word Pack</span>
                  <div className="flex flex-col gap-1.5">
                    <label
                      htmlFor="new-pack-name"
                      className="text-xs font-bold text-muted-foreground"
                    >
                      Pack Name *
                    </label>
                    <Input
                      id="new-pack-name"
                      value={newPackName}
                      onChange={(e) => setNewPackName(e.target.value)}
                      placeholder="e.g. Science Fiction, Office Inside Jokes"
                      maxLength={80}
                      required
                    />
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <label
                      htmlFor="new-pack-desc"
                      className="text-xs font-bold text-muted-foreground"
                    >
                      Description (optional)
                    </label>
                    <Input
                      id="new-pack-desc"
                      value={newPackDesc}
                      onChange={(e) => setNewPackDesc(e.target.value)}
                      placeholder="Short note about what words are included"
                      maxLength={280}
                    />
                  </div>
                  {createMutation.isError && (
                    <p className="text-xs font-bold text-destructive">
                      {describeError(createMutation.error)}
                    </p>
                  )}
                  <div className="flex justify-end gap-2 mt-1">
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => setIsCreating(false)}
                    >
                      Cancel
                    </Button>
                    <Button
                      type="submit"
                      size="sm"
                      disabled={createMutation.isPending || !newPackName.trim()}
                      className="font-bold"
                    >
                      {createMutation.isPending ? 'Creating…' : 'Create Pack'}
                    </Button>
                  </div>
                </form>
              )}

              {/* Pack List */}
              {isLoadingPacks ? (
                <div className="py-8 text-center text-sm text-muted-foreground">
                  Loading custom packs…
                </div>
              ) : packs.length === 0 && !isCreating ? (
                <div className="flex flex-col items-center justify-center rounded-2xl border-2 border-dashed border-border p-8 text-center">
                  <div className="flex size-12 items-center justify-center rounded-2xl bg-secondary mb-3 text-muted-foreground">
                    <FolderPlus className="size-6" />
                  </div>
                  <p className="text-sm font-bold text-foreground">No custom packs yet</p>
                  <p className="text-xs text-muted-foreground mt-1 max-w-sm">
                    Create a custom pack to curate your own secret words, or import words from a TXT
                    or CSV file.
                  </p>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => setIsCreating(true)}
                    className="mt-4 rounded-xl font-bold"
                  >
                    + Create First Pack
                  </Button>
                </div>
              ) : (
                <div className="flex flex-col gap-2.5">
                  {packs.map((pack) => {
                    const isSelected = selectedPackIds.includes(pack.id)
                    return (
                      <div
                        key={pack.id}
                        className={cn(
                          'flex flex-col sm:flex-row sm:items-center justify-between gap-3 rounded-2xl border-2 p-3.5 transition-all',
                          isSelected
                            ? 'border-primary/80 bg-primary/5 shadow-xs'
                            : 'border-border bg-card hover:bg-secondary/40',
                        )}
                      >
                        <div className="flex items-start gap-3 min-w-0">
                          <label className="flex items-center mt-1 cursor-pointer">
                            <input
                              type="checkbox"
                              checked={isSelected}
                              onChange={() => toggleSelectPack(pack.id)}
                              className="size-4 rounded-md border-border text-primary focus:ring-primary"
                              aria-label={`Select pack ${pack.name}`}
                            />
                          </label>
                          <div className="flex flex-col min-w-0">
                            <div className="flex items-center gap-2">
                              <span className="text-sm font-bold text-foreground truncate">
                                {pack.name}
                              </span>
                              <Badge
                                variant="secondary"
                                className="text-[10px] font-bold px-1.5 py-0"
                              >
                                {pack.wordCount} words
                              </Badge>
                            </div>
                            {pack.description && (
                              <p className="text-xs text-muted-foreground truncate mt-0.5">
                                {pack.description}
                              </p>
                            )}
                          </div>
                        </div>

                        <div className="flex items-center gap-1.5 shrink-0 self-end sm:self-center">
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={() => {
                              setActivePackId(pack.id)
                              setWordsPage(0)
                            }}
                            className="h-8 text-xs font-bold rounded-lg text-primary hover:bg-primary/10"
                            aria-label={`Manage words ${pack.name}`}
                          >
                            Manage Words
                          </Button>
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={() => {
                              setEditingPack(pack)
                              setEditName(pack.name)
                              setEditDesc(pack.description || '')
                            }}
                            className="size-8 p-0 rounded-lg text-muted-foreground hover:text-foreground"
                            aria-label={`Edit pack ${pack.name}`}
                          >
                            <Edit2 className="size-3.5" />
                          </Button>
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={() => setDeletingPack(pack)}
                            className="size-8 p-0 rounded-lg text-muted-foreground hover:text-destructive"
                            aria-label={`Delete pack ${pack.name}`}
                          >
                            <Trash2 className="size-3.5" />
                          </Button>
                        </div>
                      </div>
                    )
                  })}
                </div>
              )}
            </div>
          )}

          {/* ACTIVE PACK WORD VIEW */}
          {activePack && (
            <div className="flex flex-col gap-4 overflow-y-auto py-2">
              {/* Pack Word Stats & Actions */}
              <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl bg-secondary/50 p-3">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-extrabold text-foreground">{activePack.name}</span>
                  <Badge variant="outline" className="font-bold">
                    {activePack.wordCount} words
                  </Badge>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setIsImportOpen(true)}
                  className="h-8 gap-1.5 rounded-xl font-bold"
                >
                  <FileUp className="size-3.5 text-primary" />
                  Import TXT/CSV
                </Button>
              </div>

              {/* Add Word Form */}
              <form
                onSubmit={(e) => {
                  e.preventDefault()
                  addWordMutation.mutate()
                }}
                className="flex flex-col sm:flex-row gap-2"
              >
                <Input
                  value={newWordText}
                  onChange={(e) => setNewWordText(e.target.value)}
                  placeholder="Enter word (e.g. dinosaur)"
                  className="flex-1 h-9 rounded-xl"
                  maxLength={50}
                  required
                />
                <select
                  value={newWordDifficulty}
                  onChange={(e) =>
                    setNewWordDifficulty(e.target.value as 'EASY' | 'MEDIUM' | 'HARD')
                  }
                  className="h-9 rounded-xl border-2 border-input bg-secondary/60 px-2 text-xs font-bold focus:outline-none"
                >
                  <option value="EASY">Easy</option>
                  <option value="MEDIUM">Medium</option>
                  <option value="HARD">Hard</option>
                </select>
                <Button
                  type="submit"
                  size="sm"
                  disabled={addWordMutation.isPending || !newWordText.trim()}
                  className="h-9 rounded-xl font-bold shrink-0"
                >
                  {addWordMutation.isPending ? 'Adding…' : 'Add Word'}
                </Button>
              </form>

              {/* Word List Table */}
              {isLoadingWords ? (
                <div className="py-8 text-center text-sm text-muted-foreground">Loading words…</div>
              ) : (wordsData?.words ?? []).length === 0 ? (
                <div className="py-8 text-center text-sm text-muted-foreground">
                  No words in this pack yet. Add one above or import a file!
                </div>
              ) : (
                <div className="flex flex-col gap-1.5 max-h-60 overflow-y-auto pr-1">
                  {wordsData?.words.map((word) => (
                    <div
                      key={word.id}
                      className="flex items-center justify-between rounded-xl border border-border bg-card px-3 py-1.5 text-xs"
                    >
                      <div className="flex items-center gap-2">
                        <span className="font-bold text-foreground">{word.text}</span>
                        {word.difficulty && (
                          <span className="text-[10px] font-black uppercase text-muted-foreground">
                            [{word.difficulty}]
                          </span>
                        )}
                      </div>
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        onClick={() => removeWordMutation.mutate(word.id)}
                        disabled={removeWordMutation.isPending}
                        className="size-6 p-0 rounded-md text-muted-foreground hover:text-destructive"
                        aria-label={`Remove word ${word.text}`}
                      >
                        <Trash2 className="size-3" />
                      </Button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => handleOpenChange(false)}>
              Done
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Edit Pack Dialog */}
      {editingPack && (
        <Dialog open={true} onOpenChange={() => setEditingPack(null)}>
          <DialogContent className="max-w-md">
            <DialogHeader>
              <DialogTitle>Edit Pack Details</DialogTitle>
            </DialogHeader>
            <form
              onSubmit={(e) => {
                e.preventDefault()
                editMutation.mutate()
              }}
              className="flex flex-col gap-3 py-2"
            >
              <div className="flex flex-col gap-1.5">
                <label htmlFor="edit-pack-name" className="text-xs font-bold text-muted-foreground">
                  Pack Name *
                </label>
                <Input
                  id="edit-pack-name"
                  value={editName}
                  onChange={(e) => setEditName(e.target.value)}
                  maxLength={80}
                  required
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <label htmlFor="edit-pack-desc" className="text-xs font-bold text-muted-foreground">
                  Description
                </label>
                <Input
                  id="edit-pack-desc"
                  value={editDesc}
                  onChange={(e) => setEditDesc(e.target.value)}
                  maxLength={280}
                />
              </div>
              {editMutation.isError && (
                <p className="text-xs font-bold text-destructive">
                  {describeError(editMutation.error)}
                </p>
              )}
              <DialogFooter>
                <Button type="button" variant="ghost" onClick={() => setEditingPack(null)}>
                  Cancel
                </Button>
                <Button type="submit" disabled={editMutation.isPending || !editName.trim()}>
                  {editMutation.isPending ? 'Saving…' : 'Save Changes'}
                </Button>
              </DialogFooter>
            </form>
          </DialogContent>
        </Dialog>
      )}

      {/* Delete Pack Confirmation Dialog */}
      {deletingPack && (
        <Dialog open={true} onOpenChange={() => setDeletingPack(null)}>
          <DialogContent className="max-w-md">
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2 text-destructive">
                <AlertTriangle className="size-5" />
                Delete Word Pack?
              </DialogTitle>
              <DialogDescription>
                Are you sure you want to delete "{deletingPack.name}"? This will permanently remove
                all {deletingPack.wordCount} words in this pack.
              </DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button type="button" variant="ghost" onClick={() => setDeletingPack(null)}>
                Cancel
              </Button>
              <Button
                type="button"
                variant="destructive"
                onClick={() => deleteMutation.mutate()}
                disabled={deleteMutation.isPending}
              >
                {deleteMutation.isPending ? 'Deleting…' : 'Confirm Delete'}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}

      {/* Import Modal */}
      {activePack && (
        <WordPackImportModal
          open={isImportOpen}
          onOpenChange={setIsImportOpen}
          roomCode={roomCode}
          pack={activePack}
        />
      )}
    </>
  )
}
