import type { ReactNode } from 'react'

import type { GuestDto } from '@/api/types'
import { Button } from '@/components/ui/button'

import { GuestProfileForm } from './GuestProfileForm'
import { useGuest } from './useGuest'

/**
 * Renders {@code children} once a guest identity exists. First-time visitors pick a nickname and
 * dino right here instead of being bounced to another page.
 */
export function RequireGuest({ children }: { children: (guest: GuestDto) => ReactNode }) {
  const state = useGuest()

  if (state.status === 'loading') {
    return (
      <p role="status" className="py-6 text-center text-sm text-muted-foreground">
        Loading your profile…
      </p>
    )
  }
  if (state.status === 'error') {
    return (
      <div role="alert" className="flex flex-col items-center gap-3 py-6 text-center">
        <p className="text-sm font-medium text-destructive">
          Could not load your profile. Is the server reachable?
        </p>
        <Button variant="secondary" onClick={state.retry}>
          Try again
        </Button>
      </div>
    )
  }
  if (state.status === 'anonymous') {
    return (
      <div className="flex flex-col gap-4">
        <div>
          <h3 className="text-lg font-bold text-foreground">First, pick your dino</h3>
          <p className="text-sm text-muted-foreground">
            Choose a nickname and avatar. No account needed.
          </p>
        </div>
        <GuestProfileForm submitLabel="Continue" />
      </div>
    )
  }
  return <>{children(state.guest)}</>
}
