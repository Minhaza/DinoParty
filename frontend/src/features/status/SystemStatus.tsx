import { useQuery } from '@tanstack/react-query'
import { CircleCheck, CircleX, LoaderCircle } from 'lucide-react'

import { getHealth, healthQueryKey } from '@/api/health'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'

const REFRESH_MS = 15_000

/** Live reading of `GET /api/v1/health`; the only backend-connected widget in Phase 1. */
export function SystemStatus() {
  const { data, isPending, isError } = useQuery({
    queryKey: healthQueryKey,
    queryFn: ({ signal }) => getHealth(signal),
    refetchInterval: REFRESH_MS,
    retry: false,
  })

  let label: string
  let detail: string
  let icon: React.ReactNode
  if (isPending) {
    label = 'Checking…'
    detail = 'Contacting the backend.'
    icon = <LoaderCircle className="size-5 animate-spin motion-reduce:animate-none" aria-hidden />
  } else if (isError) {
    label = 'Backend unreachable'
    detail = 'The API did not answer. Is the backend running?'
    icon = <CircleX className="size-5 text-destructive" aria-hidden />
  } else if (data.status === 'UP') {
    label = 'Backend UP'
    detail = `Database ${data.db} · version ${data.version}`
    icon = <CircleCheck className="size-5 text-success" aria-hidden />
  } else {
    label = 'Backend DOWN'
    detail = `Database ${data.db} · version ${data.version}`
    icon = <CircleX className="size-5 text-destructive" aria-hidden />
  }

  return (
    <Card aria-labelledby="system-status-title">
      <CardHeader>
        <CardTitle id="system-status-title">System status</CardTitle>
        <CardDescription>Connection to the DinoParty backend.</CardDescription>
      </CardHeader>
      <CardContent>
        <div role="status" aria-live="polite" className="flex items-center gap-3">
          {icon}
          <div>
            <p className="font-medium">{label}</p>
            <p className="text-sm text-muted-foreground">{detail}</p>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
