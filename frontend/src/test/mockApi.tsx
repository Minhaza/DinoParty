import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router'

export interface RecordedCall {
  method: string
  path: string
  body: unknown
  authorization: string | null
}

export interface MockReply {
  status: number
  body?: unknown
}

type Handler = MockReply | ((call: RecordedCall) => MockReply)

/**
 * Stubs `fetch` with canned replies keyed by `"METHOD /path"` (path without the `/api/v1` prefix).
 * Unknown requests fail loudly so tests cannot silently depend on unmocked calls.
 */
export function mockApi(handlers: Record<string, Handler>): RecordedCall[] {
  const calls: RecordedCall[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((input: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      const path = String(input).replace(/^\/api\/v1/, '')
      const headers = new Headers(init?.headers)
      const call: RecordedCall = {
        method,
        path,
        body: typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : null,
        authorization: headers.get('Authorization'),
      }
      calls.push(call)
      const handler = handlers[`${method} ${path}`]
      if (!handler) return Promise.reject(new Error(`Unmocked request: ${method} ${path}`))
      const reply = typeof handler === 'function' ? handler(call) : handler
      // A null body is required for 204 responses.
      const text = reply.body === undefined ? null : JSON.stringify(reply.body)
      return Promise.resolve(new Response(text, { status: reply.status }))
    }),
  )
  return calls
}

export function problem(status: number, code: string, extra: object = {}) {
  return { status, body: { status, code, title: code, ...extra } }
}

/** Renders {@code routes} in a memory router with a fresh, non-retrying query client. */
export function renderApp(routes: RouteObject[], initialEntry: string) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  const router = createMemoryRouter(routes, { initialEntries: [initialEntry] })
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { ...view, router, client }
}

export function renderAt(element: ReactElement, path = '/', routePath = '*') {
  return renderApp([{ path: routePath, element }], path)
}

export const GUEST = {
  id: '11111111-1111-1111-1111-111111111111',
  nickname: 'Rexy',
  avatarKey: 'dino-rex',
  avatarColor: null,
  createdAt: '2026-01-01T00:00:00.000Z',
}

export function roomFixture(overrides: Record<string, unknown> = {}) {
  return {
    id: '22222222-2222-2222-2222-222222222222',
    code: 'AB3D7K',
    inviteUrl: 'http://localhost:5173/join/AB3D7K',
    status: 'WAITING',
    gameKey: 'draw-guess',
    hostGuestId: GUEST.id,
    minPlayers: 2,
    maxPlayers: 8,
    settings: { cycles: 2, drawSeconds: 80, wordChoiceSeconds: 15, hintReveal: true },
    players: [
      {
        guestId: GUEST.id,
        nickname: 'Rexy',
        avatarKey: 'dino-rex',
        avatarColor: null,
        ready: false,
        connection: 'CONNECTED',
        isHost: true,
        score: null,
      },
    ],
    sessionId: null,
    createdAt: '2026-01-01T00:00:00.000Z',
    ...overrides,
  }
}
