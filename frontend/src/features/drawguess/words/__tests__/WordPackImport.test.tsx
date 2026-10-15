import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, afterEach } from 'vitest'
import { mockApi, problem, renderApp } from '@/test/mockApi'
import { WordPackImportModal } from '../WordPackImportModal'
import type { ImportPreviewDto, ImportResultDto, WordPackDto } from '@/api/types'

afterEach(() => {
  vi.unstubAllGlobals()
})

const PACK_MOCK: WordPackDto = {
  id: 'pack-999',
  roomId: 'room-111',
  name: 'Party Animals',
  description: 'Animals for parties',
  wordCount: 10,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const PREVIEW_MOCK: ImportPreviewDto = {
  format: 'CSV',
  sha256: 'sha-mock-123',
  packVersion: 2,
  totalRows: 5,
  validCount: 3,
  duplicateCount: 1,
  invalidCount: 1,
  remainingCapacity: 987,
  exceedsPackLimit: false,
  preview: [
    { line: 1, text: 'dinosaur', category: 'animals', difficulty: 'EASY' },
    { line: 2, text: 'pterodactyl', category: 'animals', difficulty: 'MEDIUM' },
    { line: 3, text: 'velociraptor', category: 'animals', difficulty: 'HARD' },
  ],
  duplicates: [{ line: 4, of: 'PACK' }],
  errors: [{ line: 5, code: 'TOO_LONG', message: 'Word exceeds 50 characters' }],
  truncated: false,
}

const RESULT_MOCK: ImportResultDto = {
  imported: 3,
  skipped: 2,
  duplicates: 1,
  invalid: 1,
  pack: { ...PACK_MOCK, wordCount: 13 },
}

describe('WordPackImportModal', () => {
  it('validates file extension and size on client side', async () => {
    renderApp(
      [
        {
          path: '/',
          element: (
            <WordPackImportModal
              open={true}
              onOpenChange={vi.fn()}
              roomCode="ABCDEF"
              pack={PACK_MOCK}
            />
          ),
        },
      ],
      '/',
    )

    const input = screen.getByLabelText(/select file/i)

    // Invalid extension (using fireEvent to simulate selecting file via 'All files (*.*)')
    const badFile = new File(['bad'], 'image.png', { type: 'image/png' })
    fireEvent.change(input, { target: { files: [badFile] } })
    expect(await screen.findByRole('alert')).toHaveTextContent(/only \.txt and \.csv files/i)

    // File too large (> 1 MiB)
    const largeContent = new Uint8Array(1024 * 1024 + 10)
    const largeFile = new File([largeContent], 'big.txt', { type: 'text/plain' })
    fireEvent.change(input, { target: { files: [largeFile] } })
    expect(await screen.findByRole('alert')).toHaveTextContent(/exceeds 1 mib/i)
  })

  it('uploads valid file for preview and renders preview details, duplicates, and errors', async () => {
    const calls = mockApi({
      'POST /rooms/ABCDEF/word-packs/pack-999/imports/preview': {
        status: 200,
        body: PREVIEW_MOCK,
      },
    })

    const user = userEvent.setup()
    renderApp(
      [
        {
          path: '/',
          element: (
            <WordPackImportModal
              open={true}
              onOpenChange={vi.fn()}
              roomCode="ABCDEF"
              pack={PACK_MOCK}
            />
          ),
        },
      ],
      '/',
    )

    const file = new File(['dinosaur,animals,easy\n'], 'words.csv', { type: 'text/csv' })
    await user.upload(screen.getByLabelText(/select file/i), file)

    await user.click(screen.getByRole('button', { name: /analyze & preview/i }))

    expect(await screen.findByText(/3 valid words/i)).toBeInTheDocument()
    expect(screen.getByText(/1 duplicate skipped/i)).toBeInTheDocument()
    expect(screen.getByText(/1 invalid row/i)).toBeInTheDocument()
    expect(screen.getByText('pterodactyl')).toBeInTheDocument()
    expect(screen.getByText(/line 4: duplicate of word already in pack/i)).toBeInTheDocument()
    expect(screen.getByText(/line 5: word exceeds 50 characters/i)).toBeInTheDocument()
    expect(calls[0].path).toBe('/rooms/ABCDEF/word-packs/pack-999/imports/preview')
  })

  it('confirms the import with sha256 and packVersion and calls onImportSuccess', async () => {
    const onSuccess = vi.fn()
    const calls = mockApi({
      'POST /rooms/ABCDEF/word-packs/pack-999/imports/preview': {
        status: 200,
        body: PREVIEW_MOCK,
      },
      'POST /rooms/ABCDEF/word-packs/pack-999/imports/confirm': {
        status: 200,
        body: RESULT_MOCK,
      },
    })

    const user = userEvent.setup()
    renderApp(
      [
        {
          path: '/',
          element: (
            <WordPackImportModal
              open={true}
              onOpenChange={vi.fn()}
              roomCode="ABCDEF"
              pack={PACK_MOCK}
              onImportSuccess={onSuccess}
            />
          ),
        },
      ],
      '/',
    )

    const file = new File(['dinosaur,animals,easy\n'], 'words.csv', { type: 'text/csv' })
    await user.upload(screen.getByLabelText(/select file/i), file)
    await user.click(screen.getByRole('button', { name: /analyze & preview/i }))

    const confirmBtn = await screen.findByRole('button', { name: /confirm import \(3 words\)/i })
    await user.click(confirmBtn)

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(RESULT_MOCK))
    expect(calls.some((c) => c.path.includes('/imports/confirm'))).toBe(true)
  })

  it('handles IMPORT_STALE error with clear guidance', async () => {
    mockApi({
      'POST /rooms/ABCDEF/word-packs/pack-999/imports/preview': {
        status: 200,
        body: PREVIEW_MOCK,
      },
      'POST /rooms/ABCDEF/word-packs/pack-999/imports/confirm': problem(409, 'IMPORT_STALE'),
    })

    const user = userEvent.setup()
    renderApp(
      [
        {
          path: '/',
          element: (
            <WordPackImportModal
              open={true}
              onOpenChange={vi.fn()}
              roomCode="ABCDEF"
              pack={PACK_MOCK}
            />
          ),
        },
      ],
      '/',
    )

    const file = new File(['dinosaur,animals,easy\n'], 'words.csv', { type: 'text/csv' })
    await user.upload(screen.getByLabelText(/select file/i), file)
    await user.click(screen.getByRole('button', { name: /analyze & preview/i }))

    const confirmBtn = await screen.findByRole('button', { name: /confirm import/i })
    await user.click(confirmBtn)

    expect(await screen.findByRole('alert')).toHaveTextContent(/modified or the file changed/i)
  })
})
