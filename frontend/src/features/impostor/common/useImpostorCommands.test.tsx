import { renderHook } from '@testing-library/react'
import * as React from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ClientCommand } from '@/api/types'
import type { RealtimeClient } from '@/features/realtime/realtimeClient'
import { RealtimeContext } from '@/features/realtime/realtimeContext'
import { useGameSessionStore } from '@/stores/gameSessionStore'

import { useImpostorCommands } from './useImpostorCommands'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

function setup(sendCommand: (c: ClientCommand) => string) {
  const track = vi.fn()
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <RealtimeContext.Provider value={{ sendCommand } as unknown as RealtimeClient}>
      {children}
    </RealtimeContext.Provider>
  )
  const { result } = renderHook(() => useImpostorCommands(track), { wrapper })
  return { commands: result.current, track }
}

describe('useImpostorCommands', () => {
  beforeEach(() => {
    useGameSessionStore.getState().reset()
    useGameSessionStore.setState({ phaseId: 'phase-vote-1' })
  })

  it('sends every command with a fresh actionId and the current phaseId', () => {
    const sent: ClientCommand[] = []
    const { commands, track } = setup((c) => {
      sent.push(c)
      return c.actionId ?? ''
    })

    expect(commands.chat('  hello  ')).toBe(true)
    expect(commands.vote('guest-2')).toBe(true)
    expect(commands.vote(null)).toBe(true)
    expect(commands.guess('Turtle')).toBe(true)
    expect(
      commands.drawStroke({
        strokeId: 's-1',
        batchNo: 0,
        tool: 'PENCIL',
        color: '#0072B2',
        size: 2,
        points: [[1, 1]],
        final: false,
      }),
    ).toBe(true)

    expect(sent.map((c) => c.type)).toEqual([
      'IMPOSTOR_CHAT',
      'IMPOSTOR_VOTE',
      'IMPOSTOR_VOTE',
      'IMPOSTOR_GUESS',
      'IMPOSTOR_DRAW_STROKE',
    ])
    for (const c of sent) {
      expect(c.phaseId).toBe('phase-vote-1')
      expect(c.actionId).toMatch(UUID)
    }
    expect(new Set(sent.map((c) => c.actionId)).size).toBe(sent.length)
    expect(sent[0].payload).toEqual({ text: 'hello' })
    expect(sent[1].payload).toEqual({ targetGuestId: 'guest-2' })
    expect(sent[2].payload).toEqual({ targetGuestId: null })

    // Each send is tracked under the same actionId it went out with.
    expect(track.mock.calls.map((c) => c[0])).toEqual(sent.map((c) => c.actionId))
    expect(track.mock.calls[1][1]).toEqual({
      type: 'IMPOSTOR_VOTE',
      target: 'guest-2',
      phaseId: 'phase-vote-1',
    })
    expect(track.mock.calls[4][1]).toEqual({
      type: 'IMPOSTOR_DRAW_STROKE',
      strokeId: 's-1',
      phaseId: 'phase-vote-1',
    })
  })

  it('refuses invalid text without sending', () => {
    const sendCommand = vi.fn(() => 'a')
    const { commands } = setup(sendCommand)
    expect(commands.chat('   ')).toBe(false)
    expect(commands.chat('x'.repeat(121))).toBe(false)
    expect(commands.guess('')).toBe(false)
    expect(sendCommand).not.toHaveBeenCalled()
  })

  it('returns false without a phase or while the socket is down', () => {
    const throwing = vi.fn(() => {
      throw new Error('not connected')
    })
    const { commands, track } = setup(throwing)
    expect(commands.vote('guest-2')).toBe(false)
    expect(track).not.toHaveBeenCalled()

    useGameSessionStore.setState({ phaseId: null })
    const sendCommand = vi.fn(() => 'a')
    const second = setup(sendCommand)
    expect(second.commands.chat('hi')).toBe(false)
    expect(sendCommand).not.toHaveBeenCalled()
  })
})
