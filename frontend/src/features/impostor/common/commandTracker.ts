import type { TrackedImpostorCommand } from './impostorState'

export const MAX_TRACKED_COMMANDS = 256

export interface CommandTracker {
  track: (actionId: string, command: TrackedImpostorCommand) => void
  take: (actionId: string | null | undefined) => TrackedImpostorCommand | null
  get: (actionId: string | null | undefined) => TrackedImpostorCommand | null
  clear: () => void
}

export function createCommandTracker(maxEntries = MAX_TRACKED_COMMANDS): CommandTracker {
  const map = new Map<string, TrackedImpostorCommand>()
  return {
    track: (actionId, command) => {
      if (map.size >= maxEntries) {
        const oldest = map.keys().next().value
        if (oldest !== undefined) map.delete(oldest)
      }
      map.set(actionId, command)
    },
    take: (actionId) => {
      if (!actionId) return null
      const cmd = map.get(actionId) ?? null
      map.delete(actionId)
      return cmd
    },
    get: (actionId) => {
      if (!actionId) return null
      return map.get(actionId) ?? null
    },
    clear: () => {
      map.clear()
    },
  }
}
