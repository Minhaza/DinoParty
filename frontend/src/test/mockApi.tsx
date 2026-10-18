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

/** A function handler may return a promise, e.g. one that never settles to hold a request open. */
type Handler = MockReply | ((call: RecordedCall) => MockReply | Promise<MockReply>)

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
      return Promise.resolve(reply).then((r) => {
        // A null body is required for 204 responses.
        const text = r.body === undefined ? null : JSON.stringify(r.body)
        return new Response(text, { status: r.status })
      })
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

/** `GET /games/draw-guess` as the P7-01 backend serves it (api-contracts §2.6). */
export const DRAW_GUESS_DETAIL = {
  key: 'draw-guess',
  name: 'Draw & Guess',
  tagline: 'Easy to learn • High energy',
  minPlayers: 2,
  maxPlayers: 10,
  iconKey: 'draw-guess',
  enabled: true,
  description:
    'Take turns drawing a word while others guess. The more people guess correctly, the more points you earn!',
  howToPlay: [
    'The drawer picks one of three secret words.',
    'Everyone else types guesses while the picture takes shape.',
  ],
  settingsSchema: [
    { key: 'cycles', label: 'Turns per player', type: 'int', default: 2, min: 1, max: 5 },
    { key: 'drawSeconds', label: 'Drawing time', type: 'int', default: 80, min: 30, max: 180 },
    {
      key: 'wordChoiceSeconds',
      label: 'Word choice time',
      type: 'int',
      default: 15,
      min: 5,
      max: 30,
    },
    { key: 'hintReveal', label: 'Letter hints', type: 'bool', default: true },
    {
      key: 'difficulty',
      label: 'Difficulty',
      type: 'enum',
      default: 'mixed',
      options: ['easy', 'medium', 'hard', 'mixed'],
    },
    {
      key: 'wordSource',
      label: 'Word source',
      type: 'enum',
      default: 'built-in',
      options: ['built-in', 'custom', 'combined'],
    },
    {
      key: 'wordCategories',
      label: 'Word categories',
      type: 'list',
      default: ['random-mix'],
      maxItems: 20,
      itemFormat: 'category-key',
      exclusiveOption: 'random-mix',
    },
    {
      key: 'wordPackIds',
      label: 'Custom word packs',
      type: 'list',
      default: [],
      maxItems: 10,
      itemFormat: 'uuid',
    },
  ],
  defaultSettings: {
    cycles: 2,
    drawSeconds: 80,
    wordChoiceSeconds: 15,
    hintReveal: true,
    difficulty: 'mixed',
    wordSource: 'built-in',
    wordCategories: ['random-mix'],
    wordPackIds: [],
  },
}

/** `GET /games/telephone` as the P7-01 backend serves it (api-contracts §2.6). */
export const TELEPHONE_DETAIL = {
  key: 'telephone',
  name: 'Dino Telephone',
  tagline: 'Zero drawing skill needed',
  minPlayers: 4,
  maxPlayers: 8,
  iconKey: 'telephone',
  enabled: true,
  description:
    'A fun chain of drawings and descriptions. Watch how your original idea transforms into something completely different!',
  howToPlay: ['Everyone writes a secret prompt.'],
  settingsSchema: [
    { key: 'promptSeconds', label: 'Prompt time', type: 'int', default: 60, min: 20, max: 120 },
    { key: 'drawSeconds', label: 'Drawing time', type: 'int', default: 90, min: 30, max: 180 },
    {
      key: 'describeSeconds',
      label: 'Describing time',
      type: 'int',
      default: 45,
      min: 20,
      max: 90,
    },
    {
      key: 'entriesPerChain',
      label: 'Entries per album',
      type: 'int',
      default: 0,
      min: 4,
      max: 8,
      autoValue: 0,
    },
    {
      key: 'allowSkipTimeout',
      label: 'Skip on timeout',
      type: 'bool',
      default: true,
      options: [true],
    },
  ],
  defaultSettings: {
    promptSeconds: 60,
    drawSeconds: 90,
    describeSeconds: 45,
    entriesPerChain: 0,
    allowSkipTimeout: true,
  },
}

/** The `GameSummaryDto` part of a detail (what `GET /games` lists). */
export function gameSummary(detail: {
  key: string
  name: string
  tagline: string
  minPlayers: number
  maxPlayers: number
  iconKey: string
  enabled: boolean
}) {
  const { key, name, tagline, minPlayers, maxPlayers, iconKey, enabled } = detail
  return { key, name, tagline, minPlayers, maxPlayers, iconKey, enabled }
}

type GameFixture = Parameters<typeof gameSummary>[0]

export const GAME_DETAILS: GameFixture[] = [DRAW_GUESS_DETAIL, TELEPHONE_DETAIL]

/** mockApi handlers for `GET /games` and `GET /games/{key}` of the given games. */
export function gameHandlers(details: GameFixture[] = GAME_DETAILS) {
  const handlers: Record<string, MockReply> = {
    'GET /games': { status: 200, body: details.map(gameSummary) },
  }
  for (const d of details) handlers[`GET /games/${d.key}`] = { status: 200, body: d }
  return handlers
}
