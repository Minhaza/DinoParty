import { describeError } from '@/api/errors'
import { Button } from '@/components/ui/button'

/** Inline failure for game discovery requests, with a retry the host can reach by keyboard. */
export function LoadError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2 rounded-2xl border-2 border-dashed border-border px-4 py-3">
      <p role="alert" className="text-sm font-medium text-destructive">
        {describeError(error)}
      </p>
      <Button type="button" variant="outline" size="sm" onClick={onRetry}>
        Try again
      </Button>
    </div>
  )
}
