import * as React from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AlertCircle, AlertTriangle, CheckCircle2, FileText, Upload } from 'lucide-react'
import { describeError } from '@/api/errors'
import type { ImportPreviewDto, ImportResultDto, WordPackDto } from '@/api/types'
import { confirmWordPackImport, previewWordPackImport, wordPackQueryKeys } from '@/api/wordPacks'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { cn } from '@/lib/utils'

export interface WordPackImportModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  roomCode: string
  pack: WordPackDto
  onImportSuccess?: (result: ImportResultDto) => void
}

const MAX_FILE_SIZE = 1024 * 1024 // 1 MiB

export function WordPackImportModal({
  open,
  onOpenChange,
  roomCode,
  pack,
  onImportSuccess,
}: WordPackImportModalProps) {
  const queryClient = useQueryClient()
  const [selectedFile, setSelectedFile] = React.useState<File | null>(null)
  const [clientError, setClientError] = React.useState<string | null>(null)
  const [isDragging, setIsDragging] = React.useState(false)
  const [preview, setPreview] = React.useState<ImportPreviewDto | null>(null)

  const fileInputRef = React.useRef<HTMLInputElement>(null)

  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      setSelectedFile(null)
      setClientError(null)
      setPreview(null)
      setIsDragging(false)
    }
    onOpenChange(nextOpen)
  }

  const validateFile = (file: File): boolean => {
    setClientError(null)
    const name = file.name.toLowerCase()
    if (!name.endsWith('.txt') && !name.endsWith('.csv')) {
      setClientError('Only .txt and .csv files are supported.')
      return false
    }
    if (file.size > MAX_FILE_SIZE) {
      setClientError('File exceeds 1 MiB limit.')
      return false
    }
    return true
  }

  const handleFileChange = (file: File | null) => {
    if (!file) return
    if (validateFile(file)) {
      setSelectedFile(file)
      setPreview(null)
    } else {
      setSelectedFile(null)
      setPreview(null)
    }
  }

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault()
    setIsDragging(false)
    const file = e.dataTransfer.files?.[0]
    if (file) {
      handleFileChange(file)
    }
  }

  const previewMutation = useMutation({
    mutationFn: () => {
      if (!selectedFile) throw new Error('No file selected')
      return previewWordPackImport(roomCode, pack.id, selectedFile)
    },
    onSuccess: (data) => {
      setPreview(data)
    },
  })

  const confirmMutation = useMutation({
    mutationFn: () => {
      if (!selectedFile || !preview) throw new Error('Preview required before confirmation')
      return confirmWordPackImport(
        roomCode,
        pack.id,
        selectedFile,
        preview.sha256,
        preview.packVersion,
      )
    },
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: wordPackQueryKeys.all(roomCode) })
      void queryClient.invalidateQueries({ queryKey: wordPackQueryKeys.detail(roomCode, pack.id) })
      void queryClient.invalidateQueries({
        queryKey: ['word-packs', roomCode, pack.id, 'words'],
      })
      onImportSuccess?.(result)
      handleOpenChange(false)
    },
  })

  const activeError =
    clientError ??
    (previewMutation.error ? describeError(previewMutation.error) : null) ??
    (confirmMutation.error ? describeError(confirmMutation.error) : null)

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>Import Words to "{pack.name}"</DialogTitle>
          <DialogDescription>
            Upload a TXT or CSV file. Words will be validated and duplicate-checked before saving.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4 py-2">
          {/* File Picker / Drag and Drop Area */}
          <div
            onDragOver={(e) => {
              e.preventDefault()
              setIsDragging(true)
            }}
            onDragLeave={() => setIsDragging(false)}
            onDrop={handleDrop}
            className={cn(
              'relative flex flex-col items-center justify-center rounded-2xl border-2 border-dashed p-6 text-center transition-colors',
              isDragging
                ? 'border-primary bg-primary/10'
                : 'border-border bg-secondary/30 hover:bg-secondary/60',
            )}
          >
            <input
              ref={fileInputRef}
              type="file"
              id="word-pack-file-input"
              aria-label="Select file"
              accept=".txt,.csv,text/plain,text/csv"
              onChange={(e) => handleFileChange(e.target.files?.[0] ?? null)}
              className="sr-only"
            />
            <div className="flex size-12 items-center justify-center rounded-2xl bg-muted text-muted-foreground mb-3">
              <Upload className="size-6" />
            </div>
            <p className="text-sm font-bold text-foreground">
              {selectedFile ? selectedFile.name : 'Drag & drop your TXT or CSV file here'}
            </p>
            <p className="text-xs text-muted-foreground mt-1">
              {selectedFile
                ? `${(selectedFile.size / 1024).toFixed(1)} KB`
                : 'Supports .txt (one word per line) or .csv (word,category,difficulty) up to 1 MiB'}
            </p>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => fileInputRef.current?.click()}
              className="mt-3 rounded-xl font-bold"
            >
              <FileText className="size-3.5 mr-1" />
              {selectedFile ? 'Change File' : 'Select File'}
            </Button>
          </div>

          {/* Active Error Notice */}
          {activeError && (
            <div
              role="alert"
              className="flex items-center gap-2 rounded-xl border border-destructive/40 bg-destructive/15 p-3 text-xs font-bold text-destructive"
            >
              <AlertCircle className="size-4 shrink-0" />
              <span>{activeError}</span>
            </div>
          )}

          {/* Analyze / Preview Button */}
          {selectedFile && !preview && (
            <Button
              type="button"
              onClick={() => previewMutation.mutate()}
              disabled={previewMutation.isPending}
              className="w-full rounded-xl font-bold"
            >
              {previewMutation.isPending ? 'Analyzing…' : 'Analyze & Preview'}
            </Button>
          )}

          {/* Preview Details */}
          {preview && (
            <div className="flex flex-col gap-3 rounded-2xl border-2 border-border bg-card p-4">
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border/60 pb-3">
                <span className="font-extrabold uppercase tracking-wider text-xs text-muted-foreground">
                  Analysis Results
                </span>
                <div className="flex items-center gap-2 text-xs">
                  <span className="rounded-md bg-success/20 text-primary px-2 py-0.5 font-black">
                    {preview.validCount} valid words
                  </span>
                  {preview.duplicateCount > 0 && (
                    <span className="rounded-md bg-warning/25 text-warning-foreground dark:text-warning px-2 py-0.5 font-bold">
                      {preview.duplicateCount} duplicate skipped
                    </span>
                  )}
                  {preview.invalidCount > 0 && (
                    <span className="rounded-md bg-destructive/15 text-destructive px-2 py-0.5 font-bold">
                      {preview.invalidCount} invalid row
                    </span>
                  )}
                </div>
              </div>

              {/* Duplicate warnings */}
              {preview.duplicates.length > 0 && (
                <div className="rounded-xl border border-warning/40 bg-warning/15 p-2.5 text-xs text-warning-foreground dark:text-warning flex flex-col gap-1">
                  <div className="flex items-center gap-1.5 font-bold">
                    <AlertTriangle className="size-3.5" />
                    <span>Duplicate Warnings</span>
                  </div>
                  <ul className="list-disc list-inside font-semibold space-y-0.5">
                    {preview.duplicates.map((dup) => (
                      <li key={`dup-${dup.line}`}>
                        Line {dup.line}: duplicate of word{' '}
                        {dup.of === 'PACK' ? 'already in pack' : 'earlier in file'}
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              {/* Invalid row errors */}
              {preview.errors.length > 0 && (
                <div className="rounded-xl border border-destructive/40 bg-destructive/15 p-2.5 text-xs text-destructive flex flex-col gap-1">
                  <div className="flex items-center gap-1.5 font-bold">
                    <AlertCircle className="size-3.5" />
                    <span>Invalid Rows ({preview.errors.length})</span>
                  </div>
                  <ul className="list-disc list-inside font-semibold space-y-0.5 max-h-28 overflow-y-auto">
                    {preview.errors.map((err) => (
                      <li key={`err-${err.line}`}>
                        Line {err.line}: {err.message}
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              {/* Limit warning */}
              {preview.exceedsPackLimit && (
                <div className="rounded-xl border border-destructive/40 bg-destructive/15 p-2.5 text-xs font-bold text-destructive">
                  This import would exceed the maximum pack capacity of 1,000 words.
                </div>
              )}

              {/* Valid Preview Words */}
              {preview.preview.length > 0 && (
                <div className="flex flex-col gap-1.5">
                  <span className="text-xs font-bold text-muted-foreground">
                    Words to be imported:
                  </span>
                  <div className="flex flex-wrap gap-1.5 max-h-32 overflow-y-auto p-1">
                    {preview.preview.map((row) => (
                      <span
                        key={`prev-${row.line}-${row.text}`}
                        className="inline-flex items-center gap-1 rounded-lg border border-border bg-secondary/50 px-2 py-0.5 text-xs font-bold text-foreground"
                      >
                        <CheckCircle2 className="size-3 text-primary" />
                        <span>{row.text}</span>
                        {row.difficulty && (
                          <span className="text-[10px] text-muted-foreground uppercase">
                            ({row.difficulty})
                          </span>
                        )}
                      </span>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        <DialogFooter>
          <Button
            type="button"
            variant="ghost"
            onClick={() => handleOpenChange(false)}
            disabled={confirmMutation.isPending}
          >
            Cancel
          </Button>
          {preview && (
            <Button
              type="button"
              onClick={() => confirmMutation.mutate()}
              disabled={
                confirmMutation.isPending || preview.validCount === 0 || preview.exceedsPackLimit
              }
              className="font-bold"
            >
              {confirmMutation.isPending
                ? 'Importing…'
                : `Confirm Import (${preview.validCount} words)`}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
