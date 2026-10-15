import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, afterEach } from 'vitest'
import { mockApi, problem, renderApp, roomFixture } from '@/test/mockApi'
import { RoomSettingsForm } from '../RoomSettingsForm'
import type { RoomDto, WordCategoriesDto, WordCountsDto } from '@/api/types'

afterEach(() => {
  vi.unstubAllGlobals()
})

const CATEGORIES_MOCK: WordCategoriesDto = {
  locale: 'en',
  categories: [
    { key: 'animals', label: 'Animals' },
    { key: 'food', label: 'Food' },
  ],
  presets: [{ key: 'random-mix', label: 'Random Mix', includes: ['animals', 'food'] }],
}

const COUNTS_MOCK: WordCountsDto = {
  locale: 'en',
  category: 'random-mix',
  difficulty: 'MIXED',
  total: 100,
  byDifficulty: { EASY: 40, MEDIUM: 40, HARD: 20 },
  byCategory: [
    { key: 'animals', total: 50, byDifficulty: { EASY: 20, MEDIUM: 20, HARD: 10 } },
    { key: 'food', total: 50, byDifficulty: { EASY: 20, MEDIUM: 20, HARD: 10 } },
  ],
}

describe('RoomSettingsForm with Draw & Guess word settings', () => {
  it('renders word controls and saves updated word settings', async () => {
    const room = roomFixture({
      gameKey: 'draw-guess',
      settings: {
        cycles: 2,
        drawSeconds: 80,
        wordSource: 'built-in',
        wordCategories: ['random-mix'],
        difficulty: 'mixed',
        wordPackIds: [],
      },
    }) as unknown as RoomDto

    const calls = mockApi({
      'GET /word-catalog/categories': { status: 200, body: CATEGORIES_MOCK },
      'GET /word-catalog/counts?category=random-mix&difficulty=MIXED': {
        status: 200,
        body: COUNTS_MOCK,
      },
      'PATCH /rooms/AB3D7K/settings': {
        status: 200,
        body: {
          ...room,
          settings: {
            ...room.settings,
            wordSource: 'combined',
            difficulty: 'easy',
          },
        },
      },
    })

    const user = userEvent.setup()
    renderApp([{ path: '/', element: <RoomSettingsForm room={room} /> }], '/')

    expect(await screen.findByRole('radiogroup', { name: /word source/i })).toBeInTheDocument()
    expect(screen.getByRole('radiogroup', { name: /difficulty/i })).toBeInTheDocument()

    // Switch to combined
    await user.click(screen.getByRole('radio', { name: /built-in \+ custom/i }))

    // Switch difficulty to easy
    await user.click(screen.getByRole('radio', { name: /easy/i }))

    // Save settings
    await user.click(screen.getByRole('button', { name: /save settings/i }))

    await waitFor(() => {
      const patchCall = calls.find((c) => c.method === 'PATCH')
      expect(patchCall).toBeDefined()
      const body = patchCall?.body as { settings: Record<string, unknown> }
      expect(body.settings.wordSource).toBe('combined')
      expect(body.settings.difficulty).toBe('easy')
    })
  })

  it('displays WORD_POOL_TOO_SMALL error when server rejects pool size', async () => {
    const room = roomFixture({ gameKey: 'draw-guess' }) as unknown as RoomDto
    mockApi({
      'GET /word-catalog/categories': { status: 200, body: CATEGORIES_MOCK },
      'GET /word-catalog/counts?category=random-mix&difficulty=MIXED': {
        status: 200,
        body: COUNTS_MOCK,
      },
      'PATCH /rooms/AB3D7K/settings': problem(422, 'WORD_POOL_TOO_SMALL'),
    })

    const user = userEvent.setup()
    renderApp([{ path: '/', element: <RoomSettingsForm room={room} /> }], '/')

    await screen.findByRole('radiogroup', { name: /word source/i })
    await user.click(screen.getByRole('button', { name: /save settings/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/not enough words in the selection/i)
  })
})
