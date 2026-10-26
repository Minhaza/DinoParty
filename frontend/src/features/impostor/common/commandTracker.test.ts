import { describe, expect, it } from 'vitest'

import { createCommandTracker } from './commandTracker'

describe('createCommandTracker', () => {
  it('tracks, retrieves, and takes commands by actionId', () => {
    const tracker = createCommandTracker(5)
    tracker.track('a1', { type: 'IMPOSTOR_VOTE', target: 'u1' })
    tracker.track('a2', { type: 'IMPOSTOR_CHAT' })

    expect(tracker.get('a1')).toEqual({ type: 'IMPOSTOR_VOTE', target: 'u1' })
    expect(tracker.take('a1')).toEqual({ type: 'IMPOSTOR_VOTE', target: 'u1' })
    expect(tracker.get('a1')).toBeNull()
    expect(tracker.take('a1')).toBeNull()

    expect(tracker.get('a2')).toEqual({ type: 'IMPOSTOR_CHAT' })
    tracker.clear()
    expect(tracker.get('a2')).toBeNull()
  })

  it('evicts oldest entries when exceeding maxEntries', () => {
    const tracker = createCommandTracker(2)
    tracker.track('a1', { type: '1' })
    tracker.track('a2', { type: '2' })
    tracker.track('a3', { type: '3' })

    expect(tracker.get('a1')).toBeNull()
    expect(tracker.get('a2')).toEqual({ type: '2' })
    expect(tracker.get('a3')).toEqual({ type: '3' })
  })

  it('handles null and undefined actionIds safely', () => {
    const tracker = createCommandTracker(5)
    expect(tracker.get(null)).toBeNull()
    expect(tracker.get(undefined)).toBeNull()
    expect(tracker.take(null)).toBeNull()
    expect(tracker.take(undefined)).toBeNull()
  })
})
