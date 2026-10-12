import type { RouteObject } from 'react-router'

import { GamesPage } from '@/pages/GamesPage'
import { HomePage } from '@/pages/HomePage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { PlaceholderPage } from '@/pages/PlaceholderPage'

import { Layout } from './Layout'

/** Pages below are labelled placeholders until the phase that builds them (docs/implementation-plan.md). */
export const routes: RouteObject[] = [
  {
    element: <Layout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'games', element: <GamesPage /> },
      { path: 'games/:gameKey', element: <PlaceholderPage title="Game info" phase={2} /> },
      { path: 'rooms/new', element: <PlaceholderPage title="Create room" phase={2} /> },
      { path: 'join/:code?', element: <PlaceholderPage title="Join room" phase={2} /> },
      { path: 'rooms/:code', element: <PlaceholderPage title="Lobby" phase={3} /> },
      { path: 'rooms/:code/play', element: <PlaceholderPage title="Game" phase={4} /> },
      { path: 'rooms/:code/results', element: <PlaceholderPage title="Results" phase={4} /> },
      { path: 'settings', element: <PlaceholderPage title="Settings" phase={2} /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
