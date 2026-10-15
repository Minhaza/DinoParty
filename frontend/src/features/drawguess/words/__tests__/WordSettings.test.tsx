import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, afterEach } from 'vitest'
import { mockApi, renderApp } from '@/test/mockApi'
import { WordSourceSelector } from '../WordSourceSelector'
import { DifficultySelector } from '../DifficultySelector'
import { CategorySelector } from '../CategorySelector'
import { EligibleWordCountBadge } from '../EligibleWordCountBadge'
import { WordSettingsSummary } from '../WordSettingsSummary'
import type { WordCategoriesDto, WordCountsDto } from '@/api/types'

afterEach(() => {
  vi.unstubAllGlobals()
})

const CATEGORIES_MOCK: WordCategoriesDto = {
  locale: 'en',
  categories: [
    { key: 'animals', label: 'Animals' },
    { key: 'food', label: 'Food & Drinks' },
    { key: 'nature', label: 'Nature' },
  ],
  presets: [{ key: 'random-mix', label: 'Random Mix', includes: ['animals', 'food', 'nature'] }],
}

const COUNTS_MOCK: WordCountsDto = {
  locale: 'en',
  category: 'random-mix',
  difficulty: 'MIXED',
  total: 150,
  byDifficulty: { EASY: 50, MEDIUM: 50, HARD: 50 },
  byCategory: [
    { key: 'animals', total: 50, byDifficulty: { EASY: 20, MEDIUM: 20, HARD: 10 } },
    { key: 'food', total: 60, byDifficulty: { EASY: 20, MEDIUM: 20, HARD: 20 } },
    { key: 'nature', total: 40, byDifficulty: { EASY: 10, MEDIUM: 10, HARD: 20 } },
  ],
}

describe('WordSourceSelector', () => {
  it('renders the three modes and fires onChange when selected', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()

    renderApp(
      [{ path: '/', element: <WordSourceSelector value="built-in" onChange={onChange} /> }],
      '/',
    )

    expect(screen.getByRole('radio', { name: /built-in words/i })).toBeChecked()
    expect(screen.getByRole('radio', { name: /custom packs/i })).not.toBeChecked()

    await user.click(screen.getByRole('radio', { name: /custom packs/i }))
    expect(onChange).toHaveBeenCalledWith('custom')

    await user.click(screen.getByRole('radio', { name: /built-in \+ custom/i }))
    expect(onChange).toHaveBeenCalledWith('combined')
  })
})

describe('DifficultySelector', () => {
  it('renders all 4 difficulty levels with descriptions and fires onChange', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()

    renderApp(
      [{ path: '/', element: <DifficultySelector value="mixed" onChange={onChange} /> }],
      '/',
    )

    expect(screen.getByRole('radio', { name: /mixed/i })).toBeChecked()
    expect(screen.getByText(/blend of all levels/i)).toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: /easy/i }))
    expect(onChange).toHaveBeenCalledWith('easy')
  })
})

describe('CategorySelector', () => {
  it('renders categories with counts, handles multi-select and presets', async () => {
    mockApi({
      'GET /word-catalog/categories': { status: 200, body: CATEGORIES_MOCK },
    })

    const onChange = vi.fn()
    const user = userEvent.setup()

    renderApp(
      [
        {
          path: '/',
          element: (
            <CategorySelector
              value={['animals']}
              onChange={onChange}
              categoryCounts={{ animals: 50, food: 60, nature: 40 }}
            />
          ),
        },
      ],
      '/',
    )

    expect(await screen.findByRole('checkbox', { name: /animals/i })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /food/i })).not.toBeChecked()

    // Add category
    await user.click(screen.getByRole('checkbox', { name: /food/i }))
    expect(onChange).toHaveBeenCalledWith(['animals', 'food'])

    // Select All
    await user.click(screen.getByRole('button', { name: /select all/i }))
    expect(onChange).toHaveBeenCalledWith(['animals', 'food', 'nature'])

    // Clear All
    await user.click(screen.getByRole('button', { name: /clear all/i }))
    expect(onChange).toHaveBeenCalledWith([])

    // Random Mix preset
    await user.click(screen.getByRole('button', { name: /random mix/i }))
    expect(onChange).toHaveBeenCalledWith(['random-mix'])
  })

  it('shows an empty-selection warning when no categories are chosen', async () => {
    mockApi({
      'GET /word-catalog/categories': { status: 200, body: CATEGORIES_MOCK },
    })

    renderApp(
      [
        {
          path: '/',
          element: <CategorySelector value={[]} onChange={vi.fn()} />,
        },
      ],
      '/',
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(/no categories selected/i)
  })
})

describe('EligibleWordCountBadge', () => {
  it('displays word count and alerts when pool is below 3', async () => {
    mockApi({
      'GET /word-catalog/counts?category=animals&difficulty=EASY': {
        status: 200,
        body: { ...COUNTS_MOCK, total: 2 },
      },
    })

    renderApp(
      [
        {
          path: '/',
          element: (
            <EligibleWordCountBadge
              roomCode="ABCDEF"
              wordSource="built-in"
              wordCategories={['animals']}
              difficulty="easy"
              wordPackIds={[]}
            />
          ),
        },
      ],
      '/',
    )

    expect(await screen.findByText(/2 eligible words/i)).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(/minimum 3 words required/i)
  })
})

describe('WordSettingsSummary', () => {
  it('renders read-only word source, categories, and difficulty for non-host', () => {
    renderApp(
      [
        {
          path: '/',
          element: (
            <WordSettingsSummary
              wordSource="combined"
              wordCategories={['animals', 'nature']}
              difficulty="easy"
              wordPacks={[
                {
                  id: 'p1',
                  roomId: 'r1',
                  name: 'Dinosaurs',
                  description: '',
                  wordCount: 15,
                  createdAt: '',
                  updatedAt: '',
                },
              ]}
              selectedPackIds={['p1']}
            />
          ),
        },
      ],
      '/',
    )

    expect(screen.getByText(/built-in \+ custom/i)).toBeInTheDocument()
    expect(screen.getByText(/easy/i)).toBeInTheDocument()
    expect(screen.getByText(/dinosaurs/i)).toBeInTheDocument()
  })
})
