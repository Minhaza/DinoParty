import { useAuthStore } from '@/stores/authStore'

/** Base path of the REST API; proxied to the backend by Vite in dev (see vite.config.ts). */
export const API_BASE = '/api/v1'

/** RFC 9457 problem body with the extension fields from docs/api-contracts.md §1. */
export interface ApiProblem {
  type?: string
  title?: string
  status: number
  code?: string
  detail?: string
  traceId?: string
  fieldErrors?: { field: string; message: string }[]
}

export class ApiError extends Error {
  readonly status: number
  readonly problem: ApiProblem | null

  constructor(status: number, problem: ApiProblem | null) {
    super(problem?.title ?? `Request failed with status ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }

  get code(): string | undefined {
    return this.problem?.code
  }
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (text === '') return null
  try {
    return JSON.parse(text) as unknown
  } catch {
    return null
  }
}

function isProblem(value: unknown): value is ApiProblem {
  return typeof value === 'object' && value !== null && 'status' in value
}

export interface RawResponse {
  status: number
  ok: boolean
  body: unknown
}

/** Performs a request and returns status + parsed body without throwing on HTTP errors. */
export async function apiRequestRaw(path: string, init?: RequestInit): Promise<RawResponse> {
  const token = useAuthStore.getState().token
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  })
  // A rejected token means the session is gone (expired or revoked): forget it so the UI offers
  // a fresh sign-up instead of retrying a dead token.
  if (response.status === 401 && token && useAuthStore.getState().token === token) {
    useAuthStore.getState().clear()
  }
  return { status: response.status, ok: response.ok, body: await readJson(response) }
}

/** Performs a request; non-2xx responses throw an {@link ApiError} carrying the problem body. */
export async function apiRequest(path: string, init?: RequestInit): Promise<unknown> {
  const { status, ok, body } = await apiRequestRaw(path, init)
  if (!ok) throw new ApiError(status, isProblem(body) ? body : null)
  return body
}

/** JSON request helper for mutating calls. */
export function apiJson(path: string, method: 'POST' | 'PATCH', body: unknown): Promise<unknown> {
  return apiRequest(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}
