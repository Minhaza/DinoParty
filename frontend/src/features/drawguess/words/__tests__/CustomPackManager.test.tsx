import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, afterEach } from 'vitest'
import { mockApi, renderApp } from '@/test/mockApi'
import { CustomPackManagerModal } from '../CustomPackManagerModal'
import type { WordPackDto, WordPackWordsDto } from '@/api/types'

afterEach(() => {
  vi.unstubAllGlobals()
})

const PACK_1: WordPackDto = {
  id: 'pack-1',
  roomId: 'room-1',
  name: 'Prehistoric Beasts',
  description: 'Ancient dinosaurs and creatures',
  wordCount: 2,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const PACK_WORDS_1: WordPackWordsDto = {
  words: [
    { id: 'w1', text: 'triceratops', category: 'animals', difficulty: 'MEDIUM' },
    { id: 'w2', text: 'stegosaurus', category: 'animals', difficulty: 'EASY' },
  ],
  offset: 0,
  limit: 50,
  total: 2,
}

describe('CustomPackManagerModal', () => {
  it('lists existing packs and allows selecting them for gameplay', async () => {
    mockApi({
      'GET /rooms/ABCDEF/word-packs': { status: 200, body: { packs: [PACK_1] } },
    })

    const onSelectPacks = vi.fn()
    const user = userEvent.setup()

    renderApp(
      [
        {
          path: '/',
          element: (
            <CustomPackManagerModal
              open={true}
              onOpenChange={vi.fn()}
              roomCode="ABCDEF"
              selectedPackIds={[]}
              onSelectPacks={onSelectPacks}
            />
          ),
        },
      ],
      '/',
    )

    expect(await screen.findByText('Prehistoric Beasts')).toBeInTheDocument()
    expect(screen.getByText('Ancient dinosaurs and creatures')).toBeInTheDocument()

    const checkbox = screen.getByRole('checkbox', { name: /select pack prehistoric beasts/i })
    expect(checkbox).not.toBeChecked()

    await user.click(checkbox)
    expect(onSelectPacks).toHaveBeenCalledWith(['pack-1'])
  })

  it('creates a new pack', async () => {
    const createdPack: WordPackDto = {
      id: 'pack-2',
      roomId: 'room-1',
      name: 'Space Voyage',
      description: 'Cosmic vocabulary',
      wordCount: 0,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    }

    const calls = mockApi({
      'GET /rooms/ABCDEF/word-packs': { status: 200, body: { packs: [PACK_1] } },
      'POST /rooms/ABCDEF/word-packs': { status: 201, body: createdPack },
    })

    const user = userEvent.setup()
    renderApp(
      [
        {
          path: '/',
          element: (
            <CustomPackManagerModal
              open={true}
              onOpenChange={vi.fn()}
              roomCode="ABCDEF"
              selectedPackIds={[]}
              onSelectPacks={vi.fn()}
            />
          ),
        },
      ],
      '/',
    )

    await screen.findByText('Prehistoric Beasts')
    await user.click(screen.getByRole('button', { name: /\+ new pack/i }))

    await user.type(screen.getByLabelText(/pack name/i), 'Space Voyage')
    await user.type(screen.getByLabelText(/description/i), 'Cosmic vocabulary')
    await user.click(screen.getByRole('button', { name: /create pack/i }))

    await waitFor(() => {
      expect(calls.some((c) => c.method === 'POST' && c.path === '/rooms/ABCDEF/word-packs')).toBe(
        true,
      )
    })
  })

  it('allows renaming/editing an existing pack', async () => {
    const updatedPack = { ...PACK_1, name: 'Jurassic Giants' }
    const calls = mockApi({
      'GET /rooms/ABCDEF/word-packs': { status: 200, body: { packs: [PACK_1] } },
      'PATCH /rooms/ABCDEF/word-packs/pack-1': { status: 200, body: updatedPack },
    })

    const user = userEvent.setup()
    renderApp(
      [
        {
          path: '/',
          element: (
            <CustomPackManagerModal
              open={true}
              onOpenChange={vi.fn()}
              roomCode="ABCDEF"
              selectedPackIds={[]}
              onSelectPacks={vi.fn()}
            />
          ),
        },
      ],
      '/',
    )

    await screen.findByText('Prehistoric Beasts')
    await user.click(screen.getByRole('button', { name: /edit pack prehistoric beasts/i }))

    const nameInput = screen.getByDisplayValue('Prehistoric Beasts')
    await user.clear(nameInput)
    await user.type(nameInput, 'Jurassic Giants')
    await user.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => {
      expect(
        calls.some(
          (c) =>
            c.method === 'PATCH' &&
            c.path === '/rooms/ABCDEF/word-packs/pack-1' &&
            (c.body as { name: string })?.name === 'Jurassic Giants',
        ),
      ).toBe(true)
    })
  })

  it('deletes a pack with confirmation', async () => {
    const calls = mockApi({
      'GET /rooms/ABCDEF/word-packs': { status: 200, body: { packs: [PACK_1] } },
      'DELETE /rooms/ABCDEF/word-packs/pack-1': { status: 204 },
    })

    const onSelectPacks = vi.fn()
    const user = userEvent.setup()
    renderApp(
      [
        {
          path: '/',
          element: (
            <CustomPackManagerModal
              open={true}
              onOpenChange={vi.fn()}
              roomCode="ABCDEF"
              selectedPackIds={['pack-1']}
              onSelectPacks={onSelectPacks}
            />
          ),
        },
      ],
      '/',
    )

    await screen.findByText('Prehistoric Beasts')
    await user.click(screen.getByRole('button', { name: /delete pack prehistoric beasts/i }))

    // Confirm deletion dialog
    expect(
      screen.getByText(/are you sure you want to delete "prehistoric beasts"/i),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /confirm delete/i }))

    await waitFor(() => {
      expect(
        calls.some((c) => c.method === 'DELETE' && c.path === '/rooms/ABCDEF/word-packs/pack-1'),
      ).toBe(true)
    })
    expect(onSelectPacks).toHaveBeenCalledWith([])
  })

  it('views words, adds a word, and deletes a word from a pack', async () => {
    const calls = mockApi({
      'GET /rooms/ABCDEF/word-packs': { status: 200, body: { packs: [PACK_1] } },
      'GET /rooms/ABCDEF/word-packs/pack-1/words?offset=0&limit=50': {
        status: 200,
        body: PACK_WORDS_1,
      },
      'POST /rooms/ABCDEF/word-packs/pack-1/words': {
        status: 200,
        body: { added: 1, skipped: 0, pack: { ...PACK_1, wordCount: 3 } },
      },
      'DELETE /rooms/ABCDEF/word-packs/pack-1/words/w1': { status: 204 },
    })

    const user = userEvent.setup()
    renderApp(
      [
        {
          path: '/',
          element: (
            <CustomPackManagerModal
              open={true}
              onOpenChange={vi.fn()}
              roomCode="ABCDEF"
              selectedPackIds={[]}
              onSelectPacks={vi.fn()}
            />
          ),
        },
      ],
      '/',
    )

    await screen.findByText('Prehistoric Beasts')
    await user.click(screen.getByRole('button', { name: /manage words prehistoric beasts/i }))

    // Word list view
    expect(await screen.findByText('triceratops')).toBeInTheDocument()
    expect(screen.getByText('stegosaurus')).toBeInTheDocument()

    // Add word
    await user.type(screen.getByPlaceholderText(/enter word/i), 'brachiosaurus')
    await user.click(screen.getByRole('button', { name: /add word/i }))

    await waitFor(() => {
      expect(
        calls.some((c) => c.method === 'POST' && c.path.includes('/word-packs/pack-1/words')),
      ).toBe(true)
    })

    // Delete word
    await user.click(screen.getByRole('button', { name: /remove word triceratops/i }))
    await waitFor(() => {
      expect(
        calls.some(
          (c) => c.method === 'DELETE' && c.path === '/rooms/ABCDEF/word-packs/pack-1/words/w1',
        ),
      ).toBe(true)
    })
  })
})
