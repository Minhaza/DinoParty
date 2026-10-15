import { apiRequest } from './client'
import type { WordCategoriesDto, WordCountsDto, WordDifficultiesDto } from './types'

export interface WordCountsParams {
  locale?: string
  category?: string
  difficulty?: string
}

export const wordCatalogQueryKeys = {
  categories: (locale?: string) =>
    locale
      ? (['word-catalog', 'categories', locale] as const)
      : (['word-catalog', 'categories'] as const),
  difficulties: () => ['word-catalog', 'difficulties'] as const,
  counts: (params: WordCountsParams) => ['word-catalog', 'counts', params] as const,
}

export async function getWordCategories(
  locale?: string,
  signal?: AbortSignal,
): Promise<WordCategoriesDto> {
  const query = locale ? `?locale=${encodeURIComponent(locale)}` : ''
  return (await apiRequest(`/word-catalog/categories${query}`, { signal })) as WordCategoriesDto
}

export async function getWordDifficulties(signal?: AbortSignal): Promise<WordDifficultiesDto> {
  return (await apiRequest('/word-catalog/difficulties', { signal })) as WordDifficultiesDto
}

export async function getWordCounts(
  params: WordCountsParams = {},
  signal?: AbortSignal,
): Promise<WordCountsDto> {
  const searchParams = new URLSearchParams()
  if (params.locale) searchParams.set('locale', params.locale)
  if (params.category) searchParams.set('category', params.category)
  if (params.difficulty) searchParams.set('difficulty', params.difficulty)

  const query = searchParams.toString() ? `?${searchParams.toString()}` : ''
  return (await apiRequest(`/word-catalog/counts${query}`, { signal })) as WordCountsDto
}
