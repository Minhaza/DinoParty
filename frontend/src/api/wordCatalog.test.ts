import { describe, expect, it, afterEach, vi } from 'vitest'
import { mockApi } from '@/test/mockApi'
import {
  getWordCategories,
  getWordDifficulties,
  getWordCounts,
  wordCatalogQueryKeys,
} from './wordCatalog'
import type { WordCategoriesDto, WordDifficultiesDto, WordCountsDto } from './types'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('wordCatalog API', () => {
  it('fetches categories with optional locale', async () => {
    const categoriesData: WordCategoriesDto = {
      locale: 'en',
      categories: [
        { key: 'animals', label: 'Animals' },
        { key: 'sports', label: 'Sports' },
      ],
      presets: [{ key: 'random-mix', label: 'Random Mix', includes: ['animals', 'sports'] }],
    }

    const calls = mockApi({
      'GET /word-catalog/categories?locale=en': { status: 200, body: categoriesData },
    })

    const res = await getWordCategories('en')
    expect(res).toEqual(categoriesData)
    expect(calls[0].path).toBe('/word-catalog/categories?locale=en')
  })

  it('fetches difficulties', async () => {
    const difficultiesData: WordDifficultiesDto = {
      levels: [
        { key: 'EASY', label: 'Easy' },
        { key: 'MEDIUM', label: 'Medium' },
        { key: 'HARD', label: 'Hard' },
      ],
      presets: [{ key: 'MIXED', label: 'Mixed', includes: ['EASY', 'MEDIUM', 'HARD'] }],
    }

    const calls = mockApi({
      'GET /word-catalog/difficulties': { status: 200, body: difficultiesData },
    })

    const res = await getWordDifficulties()
    expect(res).toEqual(difficultiesData)
    expect(calls[0].path).toBe('/word-catalog/difficulties')
  })

  it('fetches word counts with params', async () => {
    const countsData: WordCountsDto = {
      locale: 'en',
      category: 'random-mix',
      difficulty: 'MIXED',
      total: 500,
      byDifficulty: { EASY: 200, MEDIUM: 200, HARD: 100 },
      byCategory: [
        { key: 'animals', total: 100, byDifficulty: { EASY: 40, MEDIUM: 40, HARD: 20 } },
      ],
    }

    const calls = mockApi({
      'GET /word-catalog/counts?category=random-mix&difficulty=MIXED': {
        status: 200,
        body: countsData,
      },
    })

    const res = await getWordCounts({ category: 'random-mix', difficulty: 'MIXED' })
    expect(res).toEqual(countsData)
    expect(calls[0].path).toBe('/word-catalog/counts?category=random-mix&difficulty=MIXED')
  })

  it('provides consistent query keys', () => {
    expect(wordCatalogQueryKeys.categories('en')).toEqual(['word-catalog', 'categories', 'en'])
    expect(wordCatalogQueryKeys.difficulties()).toEqual(['word-catalog', 'difficulties'])
    expect(wordCatalogQueryKeys.counts({ category: 'animals', difficulty: 'EASY' })).toEqual([
      'word-catalog',
      'counts',
      { category: 'animals', difficulty: 'EASY' },
    ])
  })
})
