import * as React from 'react'

import { getRemainingTimeMs } from '@/lib/serverClock'

export interface ServerCountdownOptions {
  deadlineAt: string | null | undefined
  totalDurationSeconds?: number
  onComplete?: () => void
}

export interface ServerCountdownResult {
  remainingSeconds: number
  remainingMs: number
  isExpired: boolean
  isUrgent: boolean // <= 5 seconds remaining
  formattedTime: string
  progressPercent: number
  announcement: string
}

export function formatCountdown(seconds: number): string {
  if (seconds <= 0) return '0:00'
  const mins = Math.floor(seconds / 60)
  const secs = seconds % 60
  return `${mins}:${secs.toString().padStart(2, '0')}`
}

export function useServerCountdown({
  deadlineAt,
  totalDurationSeconds,
  onComplete,
}: ServerCountdownOptions): ServerCountdownResult {
  const [remainingMs, setRemainingMs] = React.useState(() =>
    deadlineAt ? getRemainingTimeMs(deadlineAt) : 0,
  )
  const [announcement, setAnnouncement] = React.useState('')
  const lastAnnouncedSecRef = React.useRef<number | null>(null)
  const completedRef = React.useRef(false)

  // Reset completion flag when deadline changes
  React.useEffect(() => {
    completedRef.current = false
    lastAnnouncedSecRef.current = null
  }, [deadlineAt])

  React.useEffect(() => {
    if (!deadlineAt) return

    const update = () => {
      const ms = getRemainingTimeMs(deadlineAt)
      setRemainingMs(ms)

      const secs = Math.ceil(ms / 1000)

      // Screen reader announcements at milestone seconds
      const milestones = [60, 30, 15, 10, 5, 4, 3, 2, 1]
      if (milestones.includes(secs) && lastAnnouncedSecRef.current !== secs) {
        lastAnnouncedSecRef.current = secs
        setAnnouncement(secs === 1 ? '1 second remaining' : `${secs} seconds remaining`)
      }

      if (ms <= 0 && !completedRef.current) {
        completedRef.current = true
        setAnnouncement('Time is up!')
        onComplete?.()
      }
    }

    // Run initial update
    update()

    // High frequency interval (100ms) ensures smoothness without high CPU usage
    const interval = setInterval(update, 100)
    return () => clearInterval(interval)
  }, [deadlineAt, onComplete])

  const effectiveMs = deadlineAt ? remainingMs : 0
  const remainingSeconds = Math.ceil(effectiveMs / 1000)
  const isExpired = effectiveMs <= 0
  const isUrgent = remainingSeconds > 0 && remainingSeconds <= 5
  const formattedTime = formatCountdown(remainingSeconds)

  const progressPercent = React.useMemo(() => {
    if (!totalDurationSeconds || totalDurationSeconds <= 0) return 0
    const elapsed = totalDurationSeconds - remainingSeconds
    return Math.min(100, Math.max(0, (elapsed / totalDurationSeconds) * 100))
  }, [remainingSeconds, totalDurationSeconds])

  return {
    remainingSeconds,
    remainingMs,
    isExpired,
    isUrgent,
    formattedTime,
    progressPercent,
    announcement,
  }
}
