import { describe, expect, it, afterEach, vi } from 'vitest'
import { mockApi } from '@/test/mockApi'
import {
  listWordPacks,
  createWordPack,
  getWordPack,
  updateWordPack,
  deleteWordPack,
  getWordPackWords,
  addWordPackWords,
  removeWordPackWord,
  previewWordPackImport,
  confirmWordPackImport,
  wordPackQueryKeys,
} from './wordPacks'
import type {
  WordPackDto,
  WordPackWordsDto,
  AddWordsResultDto,
  ImportPreviewDto,
  ImportResultDto,
} from './types'

afterEach(() => {
  vi.unstubAllGlobals()
})

const PACK_FIXTURE: WordPackDto = {
  id: 'pack-123',
  roomId: 'room-abc',
  name: 'Party Pack',
  description: 'Party words',
  wordCount: 10,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

describe('wordPacks API', () => {
  it('lists word packs for a room', async () => {
    const calls = mockApi({
      'GET /rooms/ABCDEF/word-packs': {
        status: 200,
        body: { packs: [PACK_FIXTURE] },
      },
    })

    const res = await listWordPacks('ABCDEF')
    expect(res).toEqual([PACK_FIXTURE])
    expect(calls[0].path).toBe('/rooms/ABCDEF/word-packs')
  })

  it('creates a word pack', async () => {
    const calls = mockApi({
      'POST /rooms/ABCDEF/word-packs': {
        status: 201,
        body: PACK_FIXTURE,
      },
    })

    const res = await createWordPack('ABCDEF', { name: 'Party Pack', description: 'Party words' })
    expect(res).toEqual(PACK_FIXTURE)
    expect(calls[0].body).toEqual({ name: 'Party Pack', description: 'Party words' })
  })

  it('gets a word pack by id', async () => {
    const calls = mockApi({
      'GET /rooms/ABCDEF/word-packs/pack-123': {
        status: 200,
        body: PACK_FIXTURE,
      },
    })

    const res = await getWordPack('ABCDEF', 'pack-123')
    expect(res).toEqual(PACK_FIXTURE)
    expect(calls[0].path).toBe('/rooms/ABCDEF/word-packs/pack-123')
  })

  it('updates a word pack', async () => {
    const updated = { ...PACK_FIXTURE, name: 'Renamed Pack' }
    const calls = mockApi({
      'PATCH /rooms/ABCDEF/word-packs/pack-123': {
        status: 200,
        body: updated,
      },
    })

    const res = await updateWordPack('ABCDEF', 'pack-123', { name: 'Renamed Pack' })
    expect(res).toEqual(updated)
    expect(calls[0].body).toEqual({ name: 'Renamed Pack' })
  })

  it('deletes a word pack', async () => {
    const calls = mockApi({
      'DELETE /rooms/ABCDEF/word-packs/pack-123': {
        status: 204,
      },
    })

    await deleteWordPack('ABCDEF', 'pack-123')
    expect(calls[0].method).toBe('DELETE')
  })

  it('gets paginated words for a pack', async () => {
    const wordsPage: WordPackWordsDto = {
      words: [{ id: 'w1', text: 'dinosaur', category: 'animals', difficulty: 'EASY' }],
      offset: 0,
      limit: 50,
      total: 1,
    }
    const calls = mockApi({
      'GET /rooms/ABCDEF/word-packs/pack-123/words?offset=0&limit=50': {
        status: 200,
        body: wordsPage,
      },
    })

    const res = await getWordPackWords('ABCDEF', 'pack-123', 0, 50)
    expect(res).toEqual(wordsPage)
    expect(calls[0].path).toBe('/rooms/ABCDEF/word-packs/pack-123/words?offset=0&limit=50')
  })

  it('adds words to a pack', async () => {
    const addResult: AddWordsResultDto = {
      added: 1,
      skipped: 0,
      pack: { ...PACK_FIXTURE, wordCount: 11 },
    }
    const calls = mockApi({
      'POST /rooms/ABCDEF/word-packs/pack-123/words': {
        status: 200,
        body: addResult,
      },
    })

    const res = await addWordPackWords('ABCDEF', 'pack-123', [
      { text: 'pterodactyl', category: 'animals', difficulty: 'MEDIUM' },
    ])
    expect(res).toEqual(addResult)
    expect(calls[0].body).toEqual({
      words: [{ text: 'pterodactyl', category: 'animals', difficulty: 'MEDIUM' }],
    })
  })

  it('removes a word from a pack', async () => {
    const calls = mockApi({
      'DELETE /rooms/ABCDEF/word-packs/pack-123/words/w1': {
        status: 204,
      },
    })

    await removeWordPackWord('ABCDEF', 'pack-123', 'w1')
    expect(calls[0].method).toBe('DELETE')
  })

  it('previews word pack import with multipart form-data', async () => {
    const previewData: ImportPreviewDto = {
      format: 'TXT',
      sha256: 'abc123sha',
      packVersion: 1,
      totalRows: 2,
      validCount: 2,
      duplicateCount: 0,
      invalidCount: 0,
      remainingCapacity: 990,
      exceedsPackLimit: false,
      preview: [{ line: 1, text: 'dino', category: 'general', difficulty: 'MEDIUM' }],
      duplicates: [],
      errors: [],
      truncated: false,
    }

    const calls = mockApi({
      'POST /rooms/ABCDEF/word-packs/pack-123/imports/preview': {
        status: 200,
        body: previewData,
      },
    })

    const file = new File(['dino\ntrex'], 'words.txt', { type: 'text/plain' })
    const res = await previewWordPackImport('ABCDEF', 'pack-123', file)
    expect(res).toEqual(previewData)
    expect(calls[0].path).toBe('/rooms/ABCDEF/word-packs/pack-123/imports/preview')
  })

  it('confirms word pack import with file and metadata', async () => {
    const importResult: ImportResultDto = {
      imported: 2,
      skipped: 0,
      duplicates: 0,
      invalid: 0,
      pack: { ...PACK_FIXTURE, wordCount: 12 },
    }

    const calls = mockApi({
      'POST /rooms/ABCDEF/word-packs/pack-123/imports/confirm': {
        status: 200,
        body: importResult,
      },
    })

    const file = new File(['dino\ntrex'], 'words.txt', { type: 'text/plain' })
    const res = await confirmWordPackImport('ABCDEF', 'pack-123', file, 'abc123sha', 1)
    expect(res).toEqual(importResult)
    expect(calls[0].path).toBe('/rooms/ABCDEF/word-packs/pack-123/imports/confirm')
  })

  it('provides consistent query keys', () => {
    expect(wordPackQueryKeys.all('ABCDEF')).toEqual(['word-packs', 'ABCDEF'])
    expect(wordPackQueryKeys.detail('ABCDEF', 'pack-123')).toEqual([
      'word-packs',
      'ABCDEF',
      'pack-123',
    ])
    expect(wordPackQueryKeys.words('ABCDEF', 'pack-123', 0, 50)).toEqual([
      'word-packs',
      'ABCDEF',
      'pack-123',
      'words',
      0,
      50,
    ])
  })
})
