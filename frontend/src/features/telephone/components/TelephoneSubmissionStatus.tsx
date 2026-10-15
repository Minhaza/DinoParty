import { AlertCircle, Loader2, WifiOff } from 'lucide-react'

import type { SubmissionStatus } from '../telephoneStore'

export interface TelephoneSubmissionStatusProps {
  submission: SubmissionStatus
  submitError: string | null
  offline: boolean
  syncing: boolean
}

/** One status line under an editor: sending, rejected, offline or catching up with the server. */
export function TelephoneSubmissionStatus({
  submission,
  submitError,
  offline,
  syncing,
}: TelephoneSubmissionStatusProps) {
  if (offline) {
    return (
      <p role="status" className="flex items-center gap-2 text-sm text-amber-800">
        <WifiOff className="size-4 shrink-0" aria-hidden="true" />
        Reconnecting… your saved draft is safe on the server.
      </p>
    )
  }
  if (syncing) {
    return (
      <p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="size-4 shrink-0 motion-safe:animate-spin" aria-hidden="true" />
        Time's up — catching up with the game…
      </p>
    )
  }
  if (submission === 'submitting') {
    return (
      <p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="size-4 shrink-0 motion-safe:animate-spin" aria-hidden="true" />
        Sending…
      </p>
    )
  }
  if (submission === 'error' && submitError) {
    return (
      <p role="alert" className="flex items-center gap-2 text-sm font-medium text-destructive">
        <AlertCircle className="size-4 shrink-0" aria-hidden="true" />
        {submitError}
      </p>
    )
  }
  return null
}
