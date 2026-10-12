import { SystemStatus } from '@/features/status/SystemStatus'

export function HomePage() {
  return (
    <div className="flex flex-col gap-8">
      <section className="flex flex-col gap-3">
        <h1 className="text-4xl font-bold tracking-tight">DinoParty</h1>
        <p className="max-w-prose text-lg text-muted-foreground">
          Quick browser party games for friends. Rooms and games are on the way; for now this page
          only shows whether the backend is reachable.
        </p>
      </section>
      <div className="max-w-md">
        <SystemStatus />
      </div>
    </div>
  )
}
