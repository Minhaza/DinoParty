import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetServerClock, updateServerClockOffset } from '@/lib/serverClock'

import { formatCountdown, useServerCountdown } from './useServerCountdown'

describe('useServerCountdown', () => {
  beforeEach(() => {
    resetServerClock()
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-14T12:00:00.000Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('formats seconds into mm:ss accurately', () => {
    expect(formatCountdown(0)).toBe('0:00')
    expect(formatCountdown(-5)).toBe('0:00')
    expect(formatCountdown(5)).toBe('0:05')
    expect(formatCountdown(59)).toBe('0:59')
    expect(formatCountdown(60)).toBe('1:00')
    expect(formatCountdown(125)).toBe('2:05')
  })

  it('ticks down smoothly and detects urgent threshold', () => {
    // Deadline is 10 seconds from now
    const deadlineAt = '2026-10-14T12:00:10.000Z'
    const onComplete = vi.fn()

    const { result } = renderHook(() =>
      useServerCountdown({
        deadlineAt,
        totalDurationSeconds: 10,
        onComplete,
      }),
    )

    expect(result.current.remainingSeconds).toBe(10)
    expect(result.current.isExpired).toBe(false)
    expect(result.current.isUrgent).toBe(false)
    expect(result.current.formattedTime).toBe('0:10')
    expect(result.current.announcement).toBe('10 seconds remaining')

    // Advance by 5 seconds -> exactly 5 seconds left (urgent!)
    act(() => {
      vi.advanceTimersByTime(5000)
    })

    expect(result.current.remainingSeconds).toBe(5)
    expect(result.current.isUrgent).toBe(true)
    expect(result.current.announcement).toBe('5 seconds remaining')

    // Advance 5 more seconds -> expired
    act(() => {
      vi.advanceTimersByTime(5000)
    })

    expect(result.current.remainingSeconds).toBe(0)
    expect(result.current.isExpired).toBe(true)
    expect(result.current.isUrgent).toBe(false)
    expect(result.current.announcement).toBe('Time is up!')
    expect(onComplete).toHaveBeenCalledTimes(1)
  })

  it('respects server clock offset when computing countdown', () => {
    // Server is 3 seconds ahead of client: at client 12:00:00, server is 12:00:03
    updateServerClockOffset('2026-10-14T12:00:03.000Z')

    // Deadline is 12:00:10.000Z (7 seconds left on server)
    const deadlineAt = '2026-10-14T12:00:10.000Z'

    const { result } = renderHook(() =>
      useServerCountdown({
        deadlineAt,
      }),
    )

    expect(result.current.remainingSeconds).toBe(7)
  })

  it('handles null deadline without error', () => {
    const { result } = renderHook(() =>
      useServerCountdown({
        deadlineAt: null,
      }),
    )

    expect(result.current.remainingSeconds).toBe(0)
    expect(result.current.isExpired).toBe(true)
  })
})
