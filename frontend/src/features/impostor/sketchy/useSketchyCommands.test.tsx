import { renderHook } from '@testing-library/react'
import * as React from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ClientCommand } from '@/api/types'
import type { RealtimeClient } from '@/features/realtime/realtimeClient'
import { RealtimeContext } from '@/features/realtime/realtimeContext'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import { useSketchyCommands } from './useSketchyCommands'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

function setup(sendCommand: (c: ClientCommand) => string) {
  const track = vi.fn()
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <RealtimeContext.Provider value={{ sendCommand } as unknown as RealtimeClient}>
      {children}
    </RealtimeContext.Provider>
  )
  const { result } = renderHook(() => useSketchyCommands(track), { wrapper })
  return { commands: result.current, track }
}

describe('useSketchyCommands', () => {
  beforeEach(() => {
    useGameSessionStore.getState().reset()
    useGameSessionStore.setState({ phaseId: 'phase-sketch-1' })
  })

  it('sends Sketchy Saurus commands with actionId and current phaseId', () => {
    const sent: ClientCommand[] = []
    const { commands, track } = setup((c) => {
      sent.push(c)
      return c.actionId ?? ''
    })

    expect(
      commands.drawStroke({
        strokeId: 's-1',
        batchNo: 0,
        tool: 'PENCIL',
        color: '#2563EB',
        size: 3,
        points: [[100, 100]],
        final: false,
      }),
    ).toBe(true)
    expect(commands.undo()).toBe(true)
    expect(commands.clear()).toBe(true)
    expect(commands.submit()).toBe(true)
    expect(commands.chat('Looking good!')).toBe(true)
    expect(commands.vote('ss-g2')).toBe(true)
    expect(commands.guess('Amber')).toBe(true)

    expect(sent.map((c) => c.type)).toEqual([
      'SKETCHY_DRAW_STROKE',
      'SKETCHY_DRAW_UNDO',
      'SKETCHY_DRAW_CLEAR',
      'SKETCHY_SUBMIT',
      'IMPOSTOR_CHAT',
      'IMPOSTOR_VOTE',
      'IMPOSTOR_GUESS',
    ])
    for (const c of sent) {
      expect(c.actionId).toMatch(UUID)
      expect(c.phaseId).toBe('phase-sketch-1')
    }
    expect(track).toHaveBeenCalledTimes(7)
  })

  it('returns false when no phase is active or socket throws', () => {
    useGameSessionStore.setState({ phaseId: null })
    const { commands } = setup(() => 'id')
    expect(commands.submit()).toBe(false)

    useGameSessionStore.setState({ phaseId: 'phase-1' })
    const failing = setup(() => {
      throw new Error('Socket closed')
    })
    expect(failing.commands.submit()).toBe(false)
  })
})
