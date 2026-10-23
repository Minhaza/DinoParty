import { render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { ClientCommand, GameSnapshot } from '@/api/types'
import type { RealtimeClient } from '@/features/realtime/realtimeClient'
import { RealtimeContext } from '@/features/realtime/realtimeContext'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import {
  ARTIST_ROLE,
  DECOY_WORD,
  SECRET_WORD,
  sketchySnapshot,
  SS_PLAYERS,
  SS_ROSTER,
  UNAWARE_IMPOSTOR_ROLE,
  VIEWER_ARTIST,
} from './fixtures'
import { SketchySaurusStage } from './SketchySaurusStage'
import { useSketchySaurusStore } from './sketchySaurusStore'
import type { SketchySaurusSnapshot } from './types'

const sendCommand = vi.fn((c: ClientCommand) => c.actionId ?? 'a')

/** Renders one viewer's screen and returns its HTML with the secret word masked. */
function html(snapshot: SketchySaurusSnapshot): string {
  useGameSessionStore.getState().reset()
  useSketchySaurusStore.getState().reset()
  useGameSessionStore.getState().applySnapshot({
    ...snapshot,
    gameKey: 'sketchy-saurus',
    players: SS_ROSTER,
  } as unknown as GameSnapshot)
  useSketchySaurusStore.getState().applySnapshot(snapshot)
  const { container, unmount } = render(
    <RealtimeContext.Provider value={{ sendCommand } as unknown as RealtimeClient}>
      <SketchySaurusStage players={SS_PLAYERS} currentGuestId={VIEWER_ARTIST} />
    </RealtimeContext.Provider>,
  )
  const out = container.innerHTML
    .replaceAll(SECRET_WORD, '«WORD»')
    .replaceAll(DECOY_WORD, '«WORD»')
    // React's useId counter differs between renders; it carries no information.
    .replace(/_r_[0-9a-z]+_/g, '_r_')
  unmount()
  return out
}

describe('unaware impostor looks like an artist ([SS-3])', () => {
  beforeEach(() => {
    // Freeze the clock: the countdown text must not tick between the two renders.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2099-01-01T00:00:00.000Z'))
    HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue(null)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it.each([
    ['ROLE_REVEAL', {}],
    ['SKETCH', { submitted: false }],
    ['SKETCH', { submitted: true }],
    ['GALLERY', {}],
    ['ROUND_END', {}],
  ] as const)('%s %j renders identically except for the word', (phase, over) => {
    const artist = html(sketchySnapshot(phase, ARTIST_ROLE, over))
    const unaware = html(sketchySnapshot(phase, UNAWARE_IMPOSTOR_ROLE, over))
    expect(unaware).toBe(artist)
  })
})
