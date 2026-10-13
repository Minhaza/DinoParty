import type { RouteObject } from 'react-router'

import { CreateRoomPage } from '@/pages/CreateRoomPage'
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
      { path: 'rooms/:code/play', element: <PlaceholderPage title="Game" phase={4} /> },
      { path: 'rooms/:code/results', element: <PlaceholderPage title="Results" phase={4} /> },
      { path: 'settings', element: <SettingsPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
