import { apiRequestRaw, ApiError } from './client'

/** Mirrors `GET /health` in docs/api-contracts.md §2. */
export interface HealthResponse {
  status: 'UP' | 'DOWN'
  version: string
  db: 'UP' | 'DOWN'
  time: string
}

export const healthQueryKey = ['health'] as const

function isHealthResponse(value: unknown): value is HealthResponse {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return (
    (v.status === 'UP' || v.status === 'DOWN') &&
    (v.db === 'UP' || v.db === 'DOWN') &&
    typeof v.version === 'string' &&
    typeof v.time === 'string'
  )
}

/**
 * The backend answers 503 with a regular health body when the database is down, so a 503 with that
 * shape is a successful reading of "DOWN", not a transport failure.
 */
export async function getHealth(signal?: AbortSignal): Promise<HealthResponse> {
  const { status, body } = await apiRequestRaw('/health', { signal })
  if ((status === 200 || status === 503) && isHealthResponse(body)) return body
  throw new ApiError(status, null)
}
