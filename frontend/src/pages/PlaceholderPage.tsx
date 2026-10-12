import { Construction } from 'lucide-react'

interface PlaceholderPageProps {
  title: string
  phase: number
}

/** Honest stand-in for a page whose feature is not built yet. It has no behaviour. */
export function PlaceholderPage({ title, phase }: PlaceholderPageProps) {
  return (
    <section className="flex flex-col items-start gap-3">
      <h1 className="text-3xl font-bold tracking-tight">{title}</h1>
      <p className="inline-flex items-center gap-2 rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-accent-foreground">
        <Construction className="size-4" aria-hidden />
        Coming in Phase {phase}
      </p>
    </section>
  )
}
