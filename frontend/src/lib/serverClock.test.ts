import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  getEstimatedServerNow,
  getRemainingSeconds,
  getRemainingTimeMs,
  getServerClockOffset,
  hasClockSynced,
  resetServerClock,
  updateServerClockOffset,
} from './serverClock'

describe('serverClock', () => {
  beforeEach(() => {
    resetServerClock()
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-14T10:00:00.000Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('starts unsynced with zero offset', () => {
    expect(hasClockSynced()).toBe(false)
    expect(getServerClockOffset()).toBe(0)
    expect(getEstimatedServerNow()).toBe(new Date('2026-10-14T10:00:00.000Z').getTime())
  })

  it('updates clock offset when server timestamp is ahead', () => {
    // Client is at 10:00:00.000Z, server is at 10:00:02.500Z (+2500ms)
    const serverTs = '2026-10-14T10:00:02.500Z'
    const offset = updateServerClockOffset(serverTs)

    expect(offset).toBe(2500)
    expect(hasClockSynced()).toBe(true)
    expect(getEstimatedServerNow()).toBe(new Date('2026-10-14T10:00:02.500Z').getTime())
  })

  it('updates clock offset when server timestamp is behind', () => {
    // Client is at 10:00:00.000Z, server is at 09:59:58.000Z (-2000ms)
    const serverTs = '2026-10-14T09:59:58.000Z'
    const offset = updateServerClockOffset(serverTs)

    expect(offset).toBe(-2000)
    expect(hasClockSynced()).toBe(true)
    expect(getEstimatedServerNow()).toBe(new Date('2026-10-14T09:59:58.000Z').getTime())
  })

  it('calculates remaining time based on server clock offset', () => {
    // Server is 3 seconds ahead of client
    updateServerClockOffset('2026-10-14T10:00:03.000Z')

    // Deadline is 10:00:10.000Z server time
    const deadline = '2026-10-14T10:00:10.000Z'

    // At 10:00:00 client time, server is 10:00:03, remaining is 7 seconds
    expect(getRemainingTimeMs(deadline)).toBe(7000)
    expect(getRemainingSeconds(deadline)).toBe(7)

    // Advance client time by 2 seconds
    vi.advanceTimersByTime(2000)
    expect(getRemainingTimeMs(deadline)).toBe(5000)
    expect(getRemainingSeconds(deadline)).toBe(5)

    // Advance client time past deadline
    vi.advanceTimersByTime(6000)
    expect(getRemainingTimeMs(deadline)).toBe(0)
    expect(getRemainingSeconds(deadline)).toBe(0)
  })

  it('handles null, undefined, or invalid deadline timestamps gracefully', () => {
    expect(getRemainingTimeMs(null)).toBe(0)
    expect(getRemainingTimeMs(undefined)).toBe(0)
    expect(getRemainingTimeMs('invalid-date')).toBe(0)
    expect(getRemainingSeconds(null)).toBe(0)
  })
})
