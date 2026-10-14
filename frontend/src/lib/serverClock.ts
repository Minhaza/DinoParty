/**
 * Server clock synchronization for authoritative timestamp presentation.
 * Estimates clock drift: serverTimeMs = clientTimeMs + offsetMs.
 */

let serverOffsetMs = 0
let hasSynced = false

export function updateServerClockOffset(serverIsoTimestamp: string): number {
  const serverTimeMs = new Date(serverIsoTimestamp).getTime()
  if (Number.isNaN(serverTimeMs)) return serverOffsetMs

  const clientNow = Date.now()
  serverOffsetMs = serverTimeMs - clientNow
  hasSynced = true
  return serverOffsetMs
}

export function getServerClockOffset(): number {
  return serverOffsetMs
}

export function hasClockSynced(): boolean {
  return hasSynced
}

export function getEstimatedServerNow(): number {
  return Date.now() + serverOffsetMs
}

/**
 * Calculates remaining milliseconds until an absolute server deadline timestamp.
 */
export function getRemainingTimeMs(deadlineIsoTimestamp: string | null | undefined): number {
  if (!deadlineIsoTimestamp) return 0
  const deadlineMs = new Date(deadlineIsoTimestamp).getTime()
  if (Number.isNaN(deadlineMs)) return 0

  const serverNow = getEstimatedServerNow()
  return Math.max(0, deadlineMs - serverNow)
}

/**
 * Calculates remaining seconds (ceiling) until an absolute server deadline timestamp.
 */
export function getRemainingSeconds(deadlineIsoTimestamp: string | null | undefined): number {
  const ms = getRemainingTimeMs(deadlineIsoTimestamp)
  return Math.ceil(ms / 1000)
}

export function resetServerClock(): void {
  serverOffsetMs = 0
  hasSynced = false
}
