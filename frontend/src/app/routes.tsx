import type { RouteObject } from 'react-router'

import { CreateRoomPage } from '@/pages/CreateRoomPage'
import { GameSessionPage } from '@/pages/GameSessionPage'
import { GamesPage } from '@/pages/GamesPage'
import { HomePage } from '@/pages/HomePage'
import { JoinRoomPage } from '@/pages/JoinRoomPage'
import { LobbyPage } from '@/pages/LobbyPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { PlaceholderPage } from '@/pages/PlaceholderPage'
import { SettingsPage } from '@/pages/SettingsPage'

import { Layout } from './Layout'

/** Pages below are labelled placeholders until the phase that builds them (docs/implementation-plan.md). */
export const routes: RouteObject[] = [
  {
    element: <Layout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'games', element: <GamesPage /> },
      { path: 'games/:gameKey', element: <PlaceholderPage title="Game info" phase={2} /> },
      { path: 'rooms/new', element: <CreateRoomPage /> },
      { path: 'join/:code?', element: <JoinRoomPage /> },
      { path: 'rooms/:code', element: <LobbyPage /> },
      { path: 'rooms/:code/play', element: <GameSessionPage /> },
      { path: 'rooms/:code/results', element: <GameSessionPage /> },
      { path: 'settings', element: <SettingsPage /> },
      // DEV-only fixture previews (P9-05): `import.meta.env.DEV` is a build-time `false` in production,
      // so the route and its lazy chunk (with the fixtures) are dropped from the bundle.
      ...(import.meta.env.DEV
        ? [{ path: '__dev/impostor', lazy: () => import('@/dev/ImpostorPreviewPage') }]
        : []),
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
