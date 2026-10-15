import { apiJson, apiRequest } from './client'
import type {
  AddWordsResultDto,
  CreateWordPackInput,
  ImportPreviewDto,
  ImportResultDto,
  UpdateWordPackInput,
  WordEntryInput,
  WordPackDto,
  WordPackWordsDto,
} from './types'

export const wordPackQueryKeys = {
  all: (roomCode: string) => ['word-packs', roomCode] as const,
  detail: (roomCode: string, packId: string) => ['word-packs', roomCode, packId] as const,
  words: (roomCode: string, packId: string, offset = 0, limit = 50) =>
    ['word-packs', roomCode, packId, 'words', offset, limit] as const,
}

interface ListWordPacksResponse {
  packs: WordPackDto[]
}

export async function listWordPacks(
  roomCode: string,
  signal?: AbortSignal,
): Promise<WordPackDto[]> {
  const res = (await apiRequest(`/rooms/${encodeURIComponent(roomCode)}/word-packs`, {
    signal,
  })) as ListWordPacksResponse
  return res.packs
}

export async function createWordPack(
  roomCode: string,
  input: CreateWordPackInput,
): Promise<WordPackDto> {
  return (await apiJson(
    `/rooms/${encodeURIComponent(roomCode)}/word-packs`,
    'POST',
    input,
  )) as WordPackDto
}

export async function getWordPack(
  roomCode: string,
  packId: string,
  signal?: AbortSignal,
): Promise<WordPackDto> {
  return (await apiRequest(
    `/rooms/${encodeURIComponent(roomCode)}/word-packs/${encodeURIComponent(packId)}`,
    { signal },
  )) as WordPackDto
}

export async function updateWordPack(
  roomCode: string,
  packId: string,
  input: UpdateWordPackInput,
): Promise<WordPackDto> {
  return (await apiJson(
    `/rooms/${encodeURIComponent(roomCode)}/word-packs/${encodeURIComponent(packId)}`,
    'PATCH',
    input,
  )) as WordPackDto
}

export async function deleteWordPack(roomCode: string, packId: string): Promise<void> {
  await apiRequest(
    `/rooms/${encodeURIComponent(roomCode)}/word-packs/${encodeURIComponent(packId)}`,
    { method: 'DELETE' },
  )
}

export async function getWordPackWords(
  roomCode: string,
  packId: string,
  offset = 0,
  limit = 50,
  signal?: AbortSignal,
): Promise<WordPackWordsDto> {
  return (await apiRequest(
    `/rooms/${encodeURIComponent(roomCode)}/word-packs/${encodeURIComponent(packId)}/words?offset=${offset}&limit=${limit}`,
    { signal },
  )) as WordPackWordsDto
}

export async function addWordPackWords(
  roomCode: string,
  packId: string,
  words: WordEntryInput[],
): Promise<AddWordsResultDto> {
  return (await apiJson(
    `/rooms/${encodeURIComponent(roomCode)}/word-packs/${encodeURIComponent(packId)}/words`,
    'POST',
    { words },
  )) as AddWordsResultDto
}

export async function removeWordPackWord(
  roomCode: string,
  packId: string,
  wordId: string,
): Promise<void> {
  await apiRequest(
    `/rooms/${encodeURIComponent(roomCode)}/word-packs/${encodeURIComponent(packId)}/words/${encodeURIComponent(wordId)}`,
    { method: 'DELETE' },
  )
}

export async function previewWordPackImport(
  roomCode: string,
  packId: string,
  file: File,
  signal?: AbortSignal,
): Promise<ImportPreviewDto> {
  const formData = new FormData()
  formData.append('file', file)

  return (await apiRequest(
    `/rooms/${encodeURIComponent(roomCode)}/word-packs/${encodeURIComponent(packId)}/imports/preview`,
    {
      method: 'POST',
      body: formData,
      signal,
    },
  )) as ImportPreviewDto
}

export async function confirmWordPackImport(
  roomCode: string,
  packId: string,
  file: File,
  sha256: string,
  packVersion: number,
  signal?: AbortSignal,
): Promise<ImportResultDto> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('sha256', sha256)
  formData.append('packVersion', String(packVersion))

  return (await apiRequest(
    `/rooms/${encodeURIComponent(roomCode)}/word-packs/${encodeURIComponent(packId)}/imports/confirm`,
    {
      method: 'POST',
      body: formData,
      signal,
    },
  )) as ImportResultDto
}
